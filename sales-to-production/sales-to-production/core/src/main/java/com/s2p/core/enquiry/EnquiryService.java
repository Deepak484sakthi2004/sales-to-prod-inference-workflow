package com.s2p.core.enquiry;

import com.s2p.contracts.Command;
import com.s2p.contracts.Crm;
import com.s2p.contracts.Erp;
import com.s2p.contracts.Mail;
import com.s2p.contracts.Status;
import com.s2p.core.CoreContext;
import com.s2p.core.engine.Money;
import com.s2p.core.engine.PromiseAssessor;
import com.s2p.core.engine.PromiseEngine;
import com.s2p.core.engine.WorkCalendar;
import com.s2p.core.governance.ActionPayload;
import com.s2p.core.governance.DateCommitment;
import com.s2p.core.governance.DecisionRecord;
import com.s2p.core.governance.ProposedAction;
import com.s2p.core.knowledge.Assessment;
import com.s2p.core.knowledge.Confidence;
import com.s2p.core.knowledge.Describable;
import com.s2p.core.knowledge.Fact;
import com.s2p.core.persistence.CoreStore;
import com.s2p.core.persistence.StoreCollection;
import com.s2p.core.planning.OrderPlanningService;
import com.s2p.core.policy.Policy;
import com.s2p.core.state.CentralView;
import com.s2p.core.state.Source;

import com.fasterxml.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads customer mail and turns it into a drafted quotation, a drafted clarification, a recorded order or a drafted
 * status reply. Nothing it drafts reaches the customer without a recorded decision. Mail is read by the language
 * model through {@link EnquiryReader}; if the model cannot be reached, the mail stays unread and is read on a later
 * poll. Which mail has been read, what each produced and the duplicate fingerprints are kept in the {@link CoreStore},
 * so a restarted core, or a second core sharing the same MongoDB, does not read a mail twice.
 */
public final class EnquiryService {
    public enum Outcome {
        QUOTATION_DRAFTED, CLARIFICATION_DRAFTED, DUPLICATE, ORDER_RECORDED, STATUS_REPLY_DRAFTED, UNKNOWN_SENDER,
        NOT_ACTIONABLE, CHANGE_REQUEST_ROUTED
    }

    public record QuotedLine(String sku, String product, int qty, BigDecimal unitPrice, String priceListId,
                             LocalDate requestedDate, LocalDate promisedDate, boolean requestedSupported,
                             int qtyByRequested, Integer slackWorkingDays, String assumption) {
    }

    public record EnquiryResult(String mailId, String messageId, String from, String accountId, String accountName,
                                Outcome outcome, String reference, String headline, List<QuotedLine> lines,
                                List<String> missing, List<String> actionIds, String draftEmail)
            implements Describable {
        @Override
        public String describe() {
            StringBuilder b = new StringBuilder(headline);
            lines.forEach(l -> b.append("\n  - ").append(l.qty()).append(" x ").append(l.sku()).append(" @ ")
                    .append(Money.inr(l.unitPrice())).append(" (").append(l.priceListId()).append("), requested ")
                    .append(WorkCalendar.fmt(l.requestedDate())).append(", quoted ").append(WorkCalendar.fmt(l.promisedDate()))
                    .append(l.requestedSupported() ? " (requested date supported" : " (requested date NOT supported; "
                            + l.qtyByRequested() + " units by then")
                    .append(l.slackWorkingDays() == null ? ")" : ", slack " + l.slackWorkingDays() + " working days)"));
            if (!missing.isEmpty()) {
                b.append("\n  Missing: ").append(String.join("; ", missing));
            }
            if (!actionIds.isEmpty()) {
                b.append("\n  Actions: ").append(String.join(", ", actionIds));
            }
            if (draftEmail != null) {
                b.append("\n  Draft to customer (not sent):\n    ").append(draftEmail.replace("\n", "\n    "));
            }
            return b.toString();
        }
    }

    record ProductMatch(String sku, double confidence, String evidence, List<String> candidates, String assumption) {
    }

    private final CoreContext ctx;
    private final OrderPlanningService planner;
    private final EnquiryReader reader;
    private final PricingService pricing = new PricingService();
    private final StoreCollection<String> processedMailIds;
    private final StoreCollection<EnquiryResult> byMessageId;
    private final StoreCollection<String> fingerprints;
    private final StoreCollection<Assessment<EnquiryResult>> history;

    public EnquiryService(CoreContext ctx, OrderPlanningService planner, EnquiryReader reader) {
        this.ctx = ctx;
        this.planner = planner;
        this.reader = reader;
        CoreStore store = ctx.store();
        this.processedMailIds = store.collection("enquiry_processed", String.class);
        this.byMessageId = store.collection("enquiry_results", EnquiryResult.class);
        this.fingerprints = store.collection("enquiry_fingerprints", String.class);
        this.history = store.collection("enquiry_history", new TypeReference<Assessment<EnquiryResult>>() {
        });
    }

    public synchronized List<Assessment<EnquiryResult>> poll() {
        CentralView v = ctx.state().view();
        List<Assessment<EnquiryResult>> out = new ArrayList<>();
        for (Mail.Message m : v.inbox().stream().sorted(Comparator.comparing(Mail.Message::receivedAt)).toList()) {
            if (!processedMailIds.insertIfAbsent(m.id(), m.messageId(), Map.of())) {
                continue;
            }
            Optional<Assessment<EnquiryResult>> a = process(m, ctx.state().view());
            if (a.isEmpty()) {
                processedMailIds.delete(m.id());
                continue;
            }
            history.upsert(m.id(), a.get(), Map.of());
            out.add(a.get());
        }
        return out;
    }

    /** Every mail read so far, newest first, with the assessment the system made at the time. */
    public synchronized List<Assessment<EnquiryResult>> history() {
        List<Assessment<EnquiryResult>> out = new ArrayList<>(history.all());
        java.util.Collections.reverse(out);
        return out;
    }

    public synchronized int unprocessed(CentralView v) {
        return (int) v.inbox().stream().filter(m -> !processedMailIds.exists(m.id())).count();
    }

    /**
     * Reads one mail and acts on it. Duplicates and unknown senders are settled before the model is asked, so they cost
     * no model call. Empty when the model cannot be reached: nothing has been decided and the mail is read later.
     */
    synchronized Optional<Assessment<EnquiryResult>> process(Mail.Message m, CentralView v) {
        EnquiryResult original = byMessageId.get(m.messageId()).orElse(null);
        if (original != null) {
            return Optional.of(duplicate(m, v, original, "it has the same Message-ID as " + original.mailId()
                    + " (the mailbox delivered it twice)", 1.0));
        }
        String fp = sha256(address(m.from()) + "|" + normalise(m.body()));
        Optional<EnquiryResult> fpOriginal = fingerprints.get(fp).flatMap(byMessageId::get);
        if (fpOriginal.isPresent()) {
            return Optional.of(duplicate(m, v, fpOriginal.get(), "it comes from the same sender with the same body as "
                    + fpOriginal.get().mailId() + " under a new Message-ID (a resend)", 0.9));
        }
        fingerprints.upsert(fp, m.messageId(), Map.of());

        String email = address(m.from());
        Optional<Crm.Contact> contact = v.contactByEmail(email);
        Optional<Crm.Account> account = contact.flatMap(c -> v.account(c.accountId()))
                .or(() -> v.accountByDomain(email.substring(email.indexOf('@') + 1)));
        Assessment<EnquiryResult> a;
        if (account.isEmpty()) {
            a = unknownSender(m, v);
        } else {
            Optional<MailReading> reading = reader.read(m, v);
            if (reading.isEmpty()) {
                return Optional.empty();
            }
            MailReading ex = reading.get();
            String firstName = contact.map(c -> c.name().split(" ")[0]).orElse("Sir/Madam");
            a = switch (ex.intent()) {
                case ENQUIRY -> enquiry(m, v, ex, account.get(), firstName);
                case ACCEPTANCE -> acceptance(m, v, ex, account.get(), firstName);
                case STATUS_QUERY -> statusQuery(m, v, account.get(), firstName);
                case CHANGE_REQUEST, CANCELLATION -> changeRequest(m, v, ex, account.get());
                case OTHER -> simple(m, v, account.get(), Outcome.NOT_ACTIONABLE,
                        "Not an enquiry, order or status question; left for a person to read.", List.of());
            };
        }
        byMessageId.upsert(m.messageId(), a.answer(), Map.of());
        return Optional.of(a);
    }

    // ---------------------------------------------------------------- enquiries

    private Assessment<EnquiryResult> enquiry(Mail.Message m, CentralView v, MailReading ex,
                                              Crm.Account acct, String firstName) {
        Policy p = ctx.policy().get();
        List<String> missing = new ArrayList<>();
        List<ProductMatch> matches = new ArrayList<>();
        for (MailReading.Line lr : ex.lines()) {
            ProductMatch pm = resolve(lr, acct.id(), v);
            matches.add(pm);
            if (pm.sku() == null) {
                missing.add(pm.evidence());
            }
            if (lr.qty() == null) {
                missing.add("quantity" + (pm.sku() == null ? "" : " of " + pm.sku()));
            }
        }
        if (ex.lines().isEmpty()) {
            missing.add("which product and how many");
        }
        if (!missing.isEmpty()) {
            return clarification(m, v, ex, acct, firstName, missing, matches);
        }
        if (!v.canCommit()) {
            return simple(m, v, acct, Outcome.NOT_ACTIONABLE, "Cannot quote a delivery date: "
                    + v.freshness(Source.PLANNING).describe() + "; " + v.freshness(Source.ERP).describe()
                    + ". The enquiry is held until data is current.", List.of());
        }

        List<QuotedLine> quoted = new ArrayList<>();
        List<Erp.QuoteLine> erpLines = new ArrayList<>();
        List<DateCommitment> commitments = new ArrayList<>();
        Confidence.Builder conf = Confidence.start();
        List<Fact> basis = new ArrayList<>();
        Set<String> notChecked = new LinkedHashSet<>();
        List<String> wouldChange = new ArrayList<>();
        LocalDate quoteValid = null;
        List<String> priceNotes = new ArrayList<>();
        for (int i = 0; i < ex.lines().size(); i++) {
            MailReading.Line lr = ex.lines().get(i);
            ProductMatch pm = matches.get(i);
            Optional<PricingService.Priced> priced = pricing.price(acct.id(), pm.sku(), lr.qty(), v, p);
            if (priced.isEmpty()) {
                return simple(m, v, acct, Outcome.NOT_ACTIONABLE, "No valid price list for " + pm.sku()
                        + " today; routed to sales to set a price before anything is quoted.", List.of());
            }
            PricingService.Priced pr = priced.get();
            priceNotes.addAll(pr.notes());
            quoteValid = quoteValid == null || pr.quoteValidTo().isBefore(quoteValid) ? pr.quoteValidTo() : quoteValid;
            List<Confidence.Factor> inputs = new ArrayList<>(lr.factors());
            if (pm.confidence() < 0.99) {
                inputs.add(new Confidence.Factor("product identification", pm.confidence(), pm.evidence()));
            }
            PromiseAssessor.Assessed as = ctx.promises().assess(new PromiseEngine.Request(pm.sku(), lr.qty(),
                    lr.requestedDate(), acct.id(), null), v, p, inputs);
            PromiseEngine.Result r = as.result();
            conf.applyAll(as.confidence().factors());
            as.basis().stream().filter(f -> basis.stream().noneMatch(x -> x.statement().equals(f.statement())))
                    .forEach(basis::add);
            notChecked.addAll(as.notChecked());
            as.wouldChange().stream().filter(w -> !wouldChange.contains(w)).forEach(wouldChange::add);
            if (pm.assumption() != null) {
                wouldChange.add(pm.assumption());
            }
            LocalDate promised = r.supportedDate();
            if (promised == null) {
                return simple(m, v, acct, Outcome.NOT_ACTIONABLE, "No supportable date for " + lr.qty() + " x " + pm.sku()
                        + " inside the planning horizon (" + r.bindingConstraint() + "); routed to planning.", List.of());
            }
            String product = v.product(pm.sku()).map(Erp.Product::name).orElse(pm.sku());
            quoted.add(new QuotedLine(pm.sku(), product, lr.qty(), pr.unitPrice(), pr.priceListId(), lr.requestedDate(),
                    promised, r.feasible() && lr.requestedDate() != null, r.qtyByRequested(),
                    r.feasible() ? r.slackWorkingDays() : null, pm.assumption()));
            erpLines.add(new Erp.QuoteLine(pm.sku(), lr.qty(), pr.unitPrice(), pr.priceListId(), lr.requestedDate(), promised));
            commitments.add(new DateCommitment(acct.id(), pm.sku(), lr.qty(), promised, null));
        }
        Command<Erp.Quotation> q = ctx.erp().createQuotation(new Erp.NewQuotation("QUOTE:" + m.messageId(), acct.id(),
                m.threadId(), erpLines, quoteValid));
        ctx.state().refresh(Source.ERP);
        Confidence confidence = conf.build();
        String body = quoteEmail(firstName, q.value(), quoted, quoteValid, priceNotes, v);
        Mail.Outbound mail = new Mail.Outbound("send-quote:" + q.value().id(), m.from(), "Re: " + m.subject()
                + " - Quotation " + q.value().id(), body, m.threadId(), m.messageId());
        DecisionRecord rec = ctx.log().record("QUOTE_DRAFTED", "system", acct.id(), null, q.value().id(),
                "Drafted " + q.value().id() + ": " + quoted.stream().map(l -> l.qty() + " x " + l.sku() + " by "
                        + WorkCalendar.fmt(l.promisedDate())).collect(Collectors.joining(", ")),
                quoted.get(0).promisedDate(), quoted.get(0).qty(), basis.stream().map(Fact::describe).toList(), v,
                confidence.score(), null, null, null, p.version());
        ProposedAction action = ctx.actions().propose(ProposedAction.draft("SEND_QUOTATION",
                "Send " + q.value().id() + " to " + acct.name() + " (" + Money.inr(q.value().value()) + ")",
                new ActionPayload.SendQuotation(acct.id(), q.value().id(), mail), "SALES_REP",
                "send-quote:" + q.value().id(), Map.of("confidence", confidence.score()), commitments, rec.id()));
        String headline = (q.replayed() ? "Quotation already existed: " : "Drafted ") + q.value().id() + " for "
                + acct.name() + " (" + Money.inr(q.value().value()) + "). " + quoted.stream().map(l -> l.requestedSupported()
                ? l.qty() + " x " + l.sku() + " by " + WorkCalendar.fmt(l.promisedDate()) + " is supported"
                : l.requestedDate() == null ? l.qty() + " x " + l.sku() + " earliest " + WorkCalendar.fmt(l.promisedDate())
                : l.qty() + " x " + l.sku() + " by " + WorkCalendar.fmt(l.requestedDate()) + " is NOT supported; quoted "
                + WorkCalendar.fmt(l.promisedDate())).collect(Collectors.joining("; "))
                + ". Waiting for a sales rep to approve " + action.id() + "."
                + (priceNotes.isEmpty() ? "" : " Price note for sales: " + String.join(" ", priceNotes));
        notChecked.addAll(priceNotes.isEmpty() ? List.of() : List.of("Commercial approval of the price notes: "
                + String.join(" ", priceNotes)));
        EnquiryResult res = new EnquiryResult(m.id(), m.messageId(), m.from(), acct.id(), acct.name(),
                Outcome.QUOTATION_DRAFTED, q.value().id(), headline, quoted, List.of(), List.of(action.id()), body);
        return new Assessment<>("Enquiry " + m.id() + ": " + m.subject(), headline, res, confidence,
                CoreContext.fresh(v, Source.MAILBOX, Source.CRM, Source.ERP, Source.PLANNING), basis,
                List.copyOf(notChecked), wouldChange, rec.id());
    }

    private String quoteEmail(String firstName, Erp.Quotation q, List<QuotedLine> lines, LocalDate validTo,
                              List<String> priceNotes, CentralView v) {
        StringBuilder b = new StringBuilder("Dear ").append(firstName).append(",\n\nThank you for your enquiry. Our quotation ")
                .append(q.id()).append(":\n");
        for (QuotedLine l : lines) {
            b.append("\n- ").append(l.qty()).append(" x ").append(l.product()).append(" (").append(l.sku()).append(") at ")
                    .append(Money.inr(l.unitPrice())).append(" each = ")
                    .append(Money.inr(l.unitPrice().multiply(BigDecimal.valueOf(l.qty())))).append(", taxes extra.\n  ");
            if (l.requestedSupported()) {
                b.append("Delivery: ").append(WorkCalendar.fmt(l.promisedDate())).append(", as you requested.");
            } else if (l.requestedDate() != null) {
                b.append("Delivery: ").append(WorkCalendar.fmt(l.promisedDate())).append(". We cannot deliver all ")
                        .append(l.qty()).append(" by ").append(WorkCalendar.fmt(l.requestedDate()));
                if (l.qtyByRequested() > 0) {
                    b.append("; we can deliver ").append(l.qtyByRequested()).append(" by then and the rest by ")
                            .append(WorkCalendar.fmt(l.promisedDate())).append(" if a split delivery helps");
                }
                b.append(".");
            } else {
                b.append("Delivery: ").append(WorkCalendar.fmt(l.promisedDate())).append(".");
            }
            if (l.assumption() != null) {
                b.append("\n  ").append(l.assumption().startsWith("Assumed") ? "We have assumed "
                        + l.product().toLowerCase(Locale.ROOT) + ", as on your previous orders. If you need a different type, "
                        + "tell us and we will requote." : "");
            }
        }
        LocalDate hold = WorkCalendar.addWorkingDays(v.today(), 1);
        b.append("\n\nThese delivery dates hold if we receive your purchase order by ").append(WorkCalendar.fmt(hold))
                .append(", 17:00; production capacity is not reserved until then. Prices are valid until ")
                .append(WorkCalendar.fmt(validTo)).append(".\n\nRegards,\nSales team, Precision Couplings");
        return b.toString();
    }

    /**
     * The product for a line. The model names it when the customer's words identify one product; when several fit,
     * this customer's order history decides if it points to exactly one of them (and that is stated as an assumption);
     * otherwise the customer is asked.
     */
    ProductMatch resolve(MailReading.Line lr, String accountId, CentralView v) {
        if (lr.sku() != null) {
            return new ProductMatch(lr.sku(), 0.97, "matched " + lr.sku() + ": " + lr.evidence(), List.of(lr.sku()), null);
        }
        List<String> skus = lr.candidates();
        if (skus.isEmpty()) {
            return new ProductMatch(null, 0, "which product" + (lr.text().isBlank() ? " (none named in the message)"
                    : ": nothing in the catalogue matches '" + lr.text() + "'"), List.of(), null);
        }
        if (skus.size() == 1) {
            return new ProductMatch(skus.get(0), 0.95, "matched " + skus.get(0) + ": " + lr.evidence(), skus, null);
        }
        Map<String, String> history = new HashMap<>();
        for (Erp.SalesOrder o : v.erp().orders()) {
            if (o.accountId().equals(accountId)) {
                o.lines().stream().filter(l -> skus.contains(l.sku())).forEach(l -> history.putIfAbsent(l.sku(), o.id()));
            }
        }
        if (history.size() == 1) {
            String sku = history.keySet().iterator().next();
            String others = skus.stream().filter(x -> !x.equals(sku)).collect(Collectors.joining(", "));
            return new ProductMatch(sku, 0.85, "'" + lr.text() + "' fits " + String.join(" and ", skus) + "; " + sku
                    + " is the only one this customer has ordered (" + history.get(sku) + ")", skus,
                    "Assumed " + sku + " from order history (" + history.get(sku) + "). If the customer meant " + others
                            + ", the price, stock and date change and the quote must be redone.");
        }
        return new ProductMatch(null, 0, "which type: " + String.join(" or ", skus)
                + (history.isEmpty() ? " (no order history to go on)" : " (they have ordered " + String.join(" and ",
                history.keySet()) + ")"), skus, null);
    }

    private Assessment<EnquiryResult> clarification(Mail.Message m, CentralView v, MailReading ex,
                                                    Crm.Account acct, String firstName, List<String> missing,
                                                    List<ProductMatch> matches) {
        StringBuilder b = new StringBuilder("Dear ").append(firstName)
                .append(",\n\nThank you for your enquiry. So that we can quote an accurate price and delivery date, please confirm:\n");
        for (String item : missing) {
            b.append("\n- ").append(customerWording(item, matches, v));
        }
        for (ProductMatch pm : matches) {
            if (pm.assumption() != null) {
                String name = v.product(pm.sku()).map(Erp.Product::name).orElse(pm.sku());
                b.append("\n- Is it the ").append(name).append(" (").append(pm.sku())
                        .append(") you ordered before? We make other sizes as well.");
            }
        }
        if (ex.lines().stream().allMatch(l -> l.requestedDate() == null)) {
            b.append("\n- The date you need them delivered by.");
        }
        if (ex.urgent()) {
            b.append("\n\nWe understand this is urgent and will reply within two working hours of hearing from you.");
        }
        b.append("\n\nRegards,\nSales team, Precision Couplings");
        Mail.Outbound mail = new Mail.Outbound("clarify:" + m.messageId(), m.from(), "Re: " + m.subject(), b.toString(),
                m.threadId(), m.messageId());
        DecisionRecord rec = ctx.log().record("CLARIFICATION_DRAFTED", "system", acct.id(), null, null,
                "Asked " + acct.name() + " for: " + String.join("; ", missing), null, null, ex.evidence(), v, 1.0,
                null, null, null, ctx.policy().get().version());
        ProposedAction a = ctx.actions().propose(ProposedAction.draft("SEND_EMAIL", "Ask " + acct.name()
                        + " for missing details", new ActionPayload.SendEmail(acct.id(), mail), "SALES_REP",
                "clarify:" + m.messageId(), Map.of(), List.of(), rec.id()));
        List<String> assumed = matches.stream().filter(pm -> pm.assumption() != null).map(ProductMatch::sku).toList();
        String headline = "Cannot quote yet: missing " + String.join("; ", missing)
                + (assumed.isEmpty() ? "" : " (product inferred as " + String.join(", ", assumed) + " from order history, "
                + "to be confirmed)") + ". Drafted a question to "
                + acct.name() + " for a sales rep to approve (" + a.id() + ").";
        EnquiryResult res = new EnquiryResult(m.id(), m.messageId(), m.from(), acct.id(), acct.name(),
                Outcome.CLARIFICATION_DRAFTED, a.id(), headline, List.of(), missing, List.of(a.id()), b.toString());
        return new Assessment<>("Enquiry " + m.id() + ": " + m.subject(), headline, res,
                Confidence.start().apply("missing inputs", 1.0, "nothing was guessed: every missing item is asked for").build(),
                CoreContext.fresh(v, Source.MAILBOX, Source.CRM, Source.ERP),
                ex.lines().stream().map(l -> v.fact(Source.MAILBOX, m.id(), "Read: " + l.evidence())).toList(),
                List.of("Stock and capacity were not checked: there is nothing specific enough to check yet."),
                List.of("A reply with size and quantity lets the system quote within one polling cycle."), rec.id());
    }

    private String customerWording(String item, List<ProductMatch> matches, CentralView v) {
        if (item.startsWith("quantity")) {
            return "The quantity you need.";
        }
        if (item.startsWith("which")) {
            String sizes = matches.stream().flatMap(pm -> pm.candidates().stream()).distinct()
                    .map(s -> v.product(s).map(Erp.Product::name).orElse(s)).collect(Collectors.joining(", "));
            return sizes.isEmpty() ? "Which product you need (type and size)." : "Which size or type you need ("
                    + "we make " + sizes + ").";
        }
        return item;
    }

    // ---------------------------------------------------------------- order confirmations

    private Assessment<EnquiryResult> acceptance(Mail.Message m, CentralView v, MailReading ex,
                                                 Crm.Account acct, String firstName) {
        Optional<Erp.Quotation> quote = ex.quotationRef() != null ? v.quotation(ex.quotationRef())
                : v.erp().quotations().stream().filter(q -> m.threadId().equals(q.sourceRef())).findFirst();
        if (quote.isEmpty() || !quote.get().accountId().equals(acct.id())) {
            return simple(m, v, acct, Outcome.NOT_ACTIONABLE, "Looks like an order confirmation but no open quotation "
                    + "of " + acct.name() + " matches " + (ex.quotationRef() == null ? "this thread" : ex.quotationRef())
                    + "; routed to a sales rep.", List.of());
        }
        Erp.Quotation q = quote.get();
        Command<Erp.SalesOrder> c;
        try {
            c = ctx.erp().createOrder(new Erp.NewOrder("ORDER:" + q.id(), q.id(), ex.customerPo()));
        } catch (IllegalStateException e) {
            return simple(m, v, acct, Outcome.NOT_ACTIONABLE, "Order not recorded: " + e.getMessage()
                    + ". Routed to a sales rep.", List.of());
        }
        Erp.SalesOrder so = c.value();
        if (c.replayed()) {
            DecisionRecord rec = ctx.log().record("DUPLICATE_IGNORED", "system", acct.id(), so.id(), q.id(),
                    "Second confirmation for " + q.id() + " ignored; " + so.id() + " already exists", null, null,
                    List.of("ERP idempotency key ORDER:" + q.id()), v, 1.0, null, null, null, ctx.policy().get().version());
            String headline = "Duplicate confirmation: " + so.id() + " already exists for " + q.id()
                    + " (created " + so.createdAt().toLocalTime().withNano(0) + "). No second order was created.";
            EnquiryResult res = new EnquiryResult(m.id(), m.messageId(), m.from(), acct.id(), acct.name(),
                    Outcome.DUPLICATE, so.id(), headline, List.of(), List.of(), List.of(), null);
            return new Assessment<>("Mail " + m.id(), headline, res,
                    Confidence.start().apply("idempotency", 1.0, "the ERP returned the existing order for key ORDER:" + q.id()).build(),
                    CoreContext.fresh(v, Source.MAILBOX, Source.ERP), List.of(v.fact(Source.ERP, so.id(), "order exists")),
                    List.of(), List.of(), rec.id());
        }
        ctx.state().refresh(Source.ERP);
        Assessment<OrderPlanningService.ProductionPlan> plan = planner.plan(so.id());
        CentralView after = ctx.state().view();
        List<DateCommitment> commitments = new ArrayList<>();
        StringBuilder b = new StringBuilder("Dear ").append(firstName).append(",\n\nThank you for your order ")
                .append(so.customerPo() == null ? "" : "(your PO " + so.customerPo() + ") ").append("against ").append(q.id())
                .append(". Our order reference is ").append(so.id()).append(".\n");
        boolean allSupported = true;
        for (OrderPlanningService.PlanLine l : plan.answer().lines()) {
            boolean ok = l.committedSupported();
            allSupported &= ok;
            LocalDate date = ok ? l.committed() : l.earliestDelivery();
            commitments.add(new DateCommitment(acct.id(), l.sku(), l.qty(), date, so.id()));
            b.append("\n- ").append(l.qty()).append(" x ").append(l.sku()).append(": delivery ")
                    .append(WorkCalendar.fmt(date)).append(ok ? "." : " (not " + WorkCalendar.fmt(l.committed())
                            + " as quoted: capacity changed before your order arrived).");
        }
        b.append("\n\nWe will keep your order status page up to date.\n\nRegards,\nSales team, Precision Couplings");
        Mail.Outbound mail = new Mail.Outbound("ack:" + so.id(), m.from(), "Order confirmation " + so.id(), b.toString(),
                m.threadId(), m.messageId());
        DecisionRecord rec = ctx.log().record("ORDER_RECORDED", "system", acct.id(), so.id(), q.id(),
                "Recorded " + so.id() + " from " + q.id() + (allSupported ? "; quoted dates still supported"
                        : "; quoted date no longer supported"), commitments.get(0).date(), commitments.get(0).qty(),
                plan.basis().stream().map(Fact::describe).toList(), after, plan.confidence().score(), null, null, null,
                ctx.policy().get().version());
        ProposedAction ack = ctx.actions().propose(ProposedAction.draft("SEND_EMAIL", "Confirm " + so.id() + " to "
                        + acct.name(), new ActionPayload.SendEmail(acct.id(), mail), "SALES_REP", "ack:" + so.id(),
                Map.of(), commitments, rec.id()));
        List<String> actionIds = new ArrayList<>(plan.answer().actionIds());
        actionIds.add(ack.id());
        String headline = "Recorded " + so.id() + " for " + acct.name() + " from " + q.id() + " (PO " + so.customerPo()
                + "). " + plan.headline() + " Order confirmation drafted for a sales rep (" + ack.id() + ").";
        EnquiryResult res = new EnquiryResult(m.id(), m.messageId(), m.from(), acct.id(), acct.name(),
                Outcome.ORDER_RECORDED, so.id(), headline, List.of(), List.of(), actionIds, b.toString());
        return new Assessment<>("Mail " + m.id() + ": " + m.subject(), headline, res, plan.confidence(), plan.freshness(),
                plan.basis(), plan.notChecked(), plan.wouldChange(), rec.id());
    }

    // ---------------------------------------------------------------- status questions by mail

    private Assessment<EnquiryResult> statusQuery(Mail.Message m, CentralView v, Crm.Account acct, String firstName) {
        List<Status.CustomerStatus> st = ctx.statusFeed().statuses(acct.id()).data().stream()
                .filter(s -> !s.stage().equals("SHIPPED") || s.publishedAt().isAfter(v.at().minusDays(7))).toList();
        StringBuilder b = new StringBuilder("Dear ").append(firstName).append(",\n\nHere is where your open orders stand:\n");
        for (Status.CustomerStatus s : st) {
            b.append("\n- ").append(s.orderRef()).append(s.customerPo() == null ? "" : " (your PO " + s.customerPo() + ")")
                    .append(": ").append(s.message()).append(" Delivery ")
                    .append(s.currentEstimate() == null ? "date being reconfirmed" : WorkCalendar.fmt(s.currentEstimate()))
                    .append(" (last updated ").append(WorkCalendar.fmt(s.publishedAt().toLocalDate())).append(").");
        }
        b.append("\n\nRegards,\nSales team, Precision Couplings");
        Mail.Outbound mail = new Mail.Outbound("status-reply:" + m.messageId(), m.from(), "Re: " + m.subject(),
                b.toString(), m.threadId(), m.messageId());
        ProposedAction a = ctx.actions().propose(ProposedAction.draft("SEND_EMAIL", "Reply to " + acct.name()
                        + " with published order status", new ActionPayload.SendEmail(acct.id(), mail), "SALES_REP",
                "status-reply:" + m.messageId(), Map.of(), List.of(), null));
        String headline = "Status question from " + acct.name() + ": reply drafted from the published customer status ("
                + st.size() + " order(s)), waiting for approval " + a.id() + ".";
        return simple(m, v, acct, Outcome.STATUS_REPLY_DRAFTED, headline, List.of(a.id()), b.toString());
    }

    private Assessment<EnquiryResult> unknownSender(Mail.Message m, CentralView v) {
        String headline = "Sender " + address(m.from()) + " is not a known contact or customer domain. No quote, no "
                + "data shared; routed to a sales rep to qualify the lead.";
        DecisionRecord rec = ctx.log().record("UNKNOWN_SENDER", "system", null, null, null, headline, null, null,
                List.of("not sent to the language model: the sender is not a customer"), v, 1.0, null, null, null,
                ctx.policy().get().version());
        EnquiryResult res = new EnquiryResult(m.id(), m.messageId(), m.from(), null, null, Outcome.UNKNOWN_SENDER, null,
                headline, List.of(), List.of("customer identity"), List.of(), null);
        return new Assessment<>("Mail " + m.id(), headline, res,
                Confidence.start().apply("sender", 1.0, "no CRM contact or account domain matches").build(),
                CoreContext.fresh(v, Source.MAILBOX, Source.CRM), List.of(), List.of("Credit and KYC for a new customer."),
                List.of("Adding the sender to the CRM lets the enquiry be quoted on the next poll."), rec.id());
    }

    private Assessment<EnquiryResult> duplicate(Mail.Message m, CentralView v, EnquiryResult original, String why,
                                                double confidence) {
        String headline = "Duplicate of " + original.mailId() + ": " + why + ". Nothing new created; original outcome was "
                + original.outcome() + (original.reference() == null ? "" : " (" + original.reference() + ")") + ".";
        DecisionRecord rec = ctx.log().record("DUPLICATE_IGNORED", "system", original.accountId(), null,
                original.reference(), headline, null, null, List.of(), v, confidence, null, null, null,
                ctx.policy().get().version());
        EnquiryResult res = new EnquiryResult(m.id(), m.messageId(), m.from(), original.accountId(),
                original.accountName(), Outcome.DUPLICATE, original.reference(), headline, List.of(), List.of(), List.of(), null);
        return new Assessment<>("Mail " + m.id(), headline, res,
                Confidence.start().apply("duplicate match", confidence, why).build(),
                CoreContext.fresh(v, Source.MAILBOX), List.of(v.fact(Source.MAILBOX, m.id(), "Message-ID " + m.messageId())),
                List.of(), List.of("A message that differs in quantity, product or date would be treated as a new enquiry."),
                rec.id());
    }

    private Assessment<EnquiryResult> simple(Mail.Message m, CentralView v, Crm.Account acct, Outcome outcome,
                                             String headline, List<String> actions) {
        return simple(m, v, acct, outcome, headline, actions, null);
    }

    private Assessment<EnquiryResult> simple(Mail.Message m, CentralView v, Crm.Account acct, Outcome outcome,
                                             String headline, List<String> actions, String draft) {
        DecisionRecord rec = ctx.log().record(outcome.name(), "system", acct.id(), null, null, headline, null, null,
                List.of(), v, null, null, null, null, ctx.policy().get().version());
        EnquiryResult res = new EnquiryResult(m.id(), m.messageId(), m.from(), acct.id(), acct.name(), outcome, null,
                headline, List.of(), List.of(), actions, draft);
        return new Assessment<>("Mail " + m.id() + ": " + m.subject(), headline, res,
                Confidence.start().apply("routing", 0.9, "intent read from wording").build(),
                CoreContext.fresh(v, Source.MAILBOX, Source.CRM, Source.ERP, Source.PLANNING), List.of(), List.of(),
                List.of(), rec.id());
    }

    /**
     * A change or cancellation of an order. Nothing is changed: an order change is a commercial and a planning
     * decision. The system identifies the order, records what was asked and routes it to a person.
     */
    private Assessment<EnquiryResult> changeRequest(Mail.Message m, CentralView v, MailReading ex,
                                                    Crm.Account acct) {
        List<Erp.SalesOrder> open = v.openOrders().stream().filter(o -> o.accountId().equals(acct.id())).toList();
        Optional<Erp.SalesOrder> order = open.stream().filter(o -> matches(o, ex.orderRef()) || matches(o, ex.customerPo()))
                .findFirst();
        String assumed = null;
        if (order.isEmpty() && open.size() == 1) {
            order = Optional.of(open.get(0));
            assumed = "the mail names no order; " + open.get(0).id() + " is this customer's only open order";
        }
        boolean cancel = ex.intent() == MailReading.Intent.CANCELLATION;
        MailReading.Line lr = ex.lines().isEmpty() ? null : ex.lines().get(0);
        StringBuilder h = new StringBuilder(acct.name()).append(cancel ? " asks to cancel " : " asks to change ")
                .append(order.map(Erp.SalesOrder::id).orElse("an order the mail does not identify"));
        if (!cancel && lr != null && (lr.qty() != null || lr.requestedDate() != null)) {
            h.append(":");
            if (lr.qty() != null) {
                h.append(" quantity ").append(lr.qty());
            }
            if (lr.requestedDate() != null) {
                h.append(lr.qty() != null ? "," : "").append(" delivery ").append(WorkCalendar.fmt(lr.requestedDate()));
            }
        }
        h.append(". Nothing has been changed: an order ").append(cancel ? "cancellation" : "change")
                .append(" needs the sales rep and a planner")
                .append(order.isEmpty() ? ", and first the order must be identified with the customer." : ".");
        if (assumed != null) {
            h.append(" (Assumed: ").append(assumed).append(".)");
        }
        return simple(m, v, acct, Outcome.CHANGE_REQUEST_ROUTED, h.toString(), List.of());
    }

    private static boolean matches(Erp.SalesOrder o, String ref) {
        return ref != null && (o.id().equalsIgnoreCase(ref.trim()) || ref.trim().equalsIgnoreCase(o.customerPo()));
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String address(String from) {
        int lt = from.indexOf('<');
        return (lt >= 0 ? from.substring(lt + 1, from.indexOf('>')) : from).trim().toLowerCase(Locale.ROOT);
    }

    private static String normalise(String body) {
        if (body == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (char c : body.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                b.append(c);
            } else if (b.length() > 0 && b.charAt(b.length() - 1) != ' ') {
                b.append(' ');
            }
        }
        return b.toString().trim();
    }
}
