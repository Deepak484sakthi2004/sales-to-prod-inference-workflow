package com.s2p.core.scenarios;

import com.s2p.contracts.Mail;
import com.s2p.contracts.Planning;
import com.s2p.contracts.Ports;
import com.s2p.contracts.Status;
import com.s2p.core.CoreSystem;
import com.s2p.core.engine.WorkCalendar;
import com.s2p.core.enquiry.EnquiryService;
import com.s2p.core.governance.ProposedAction;
import com.s2p.core.governance.Suggestion;
import com.s2p.core.knowledge.Assessment;
import com.s2p.core.planning.ReplanService;
import com.s2p.core.status.StatusService;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The run log, produced by driving the real system. Each step states who asks, what goes in, what the system said,
 * the system's own reasoning (confidence, freshness, basis, what it did not check, what would change it), and which
 * part of the brief it exercises. Works against in-process or HTTP stand-ins.
 */
public final class ScenarioSuite {
    public record Step(String id, String who, String title, String input, String output, String reasoning,
                       String shows) {
    }

    private final CoreSystem sys;
    private final Ports.SimulationPort sim;
    private final List<Step> steps = new ArrayList<>();

    public ScenarioSuite(CoreSystem sys, Ports.SimulationPort sim) {
        this.sys = sys;
        this.sim = sim;
    }

    private void add(String who, String title, String input, String output, String reasoning, String shows) {
        steps.add(new Step(String.format("S%02d", steps.size() + 1), who, title, input, output, reasoning, shows));
    }

    private void add(String who, String title, String input, Assessment<?> a, String shows) {
        add(who, title, input, Render.output(a), Render.reasoning(a), shows);
    }

    private static String act(ProposedAction a) {
        return a.id() + " " + a.type() + " -> " + a.status() + " by " + a.decidedBy() + ": " + a.result();
    }

    public List<Step> run() {
        LocalDate today = sys.context().clock().today();
        LocalDate the20th = Dates.next(today, 20);

        // ---- enquiries from the mailbox
        List<Assessment<EnquiryService.EnquiryResult>> polled = sys.pollMailbox();
        Assessment<EnquiryService.EnquiryResult> sund = find(polled, "MSG-101");
        add("Customer (Sundaram Pumps) by email", "Enquiry: 250 units of the 60mm coupling by the 20th",
                "MSG-101: \"We need 250 units of the 60mm coupling by the 20th, and we want to know today whether that "
                        + "is realistic.\"", sund,
                "Reads an enquiry, resolves an ambiguous product from order history, checks price validity, stock, "
                        + "capacity and material, drafts a quotation in the ERP and holds the email for a sales decision.");
        add("Mailbox (duplicate delivery)", "The same enquiry delivered twice",
                "MSG-102: identical Message-ID to MSG-101", find(polled, "MSG-102"),
                "Duplicate messages do not create duplicate quotations; dedupe is by Message-ID here and by idempotency "
                        + "key in the ERP as a second line of defence.");
        add("Customer (Coromandel) by email", "Incomplete enquiry",
                "MSG-103: \"Please quote for weld neck flanges, needed urgently ... Drawing to follow.\"",
                find(polled, "MSG-103"), "Asks for what is missing instead of guessing; nothing is sent without approval.");
        add("Customer (Deccan) by email with CSV attachment", "RFQ in an attachment, negotiated price expired",
                "MSG-104 with RFQ-DF-2611.csv (60 x 80mm flexible coupling, 40 x 100mm weld neck flange, by the 27th)",
                find(polled, "MSG-104"), "Reads attachments, prices from the list valid today and flags the expired "
                        + "negotiated price instead of silently using it.");

        // ---- sales rep asks the headline question
        Assessment<?> promise = sys.ask("SALES_REP", "Arjun (sales)", "Can we promise 250 units by the 20th?",
                Map.of("sku", "CPL-60-FX", "accountId", "ACC-SUND"));
        add("Sales rep", "Can we promise 250 units by the 20th?",
                "ask(role=SALES_REP, \"Can we promise 250 units by the 20th?\", context: CPL-60-FX, Sundaram Pumps)",
                promise, "Answers with the date current capacity and stock support, the basis, the confidence and "
                        + "what would move it.");

        // ---- change a requirement in the room, on the same question
        com.s2p.core.policy.Policy original = sys.policy();
        sys.setPolicy(original.withMinSlack(1), "A. Raman (OPS_HEAD)", "Review: every promise keeps one working day of buffer");
        Assessment<?> withBuffer = sys.ask("SALES_REP", "Arjun (sales)", "Can we promise 250 units by the 20th?",
                Map.of("sku", "CPL-60-FX", "accountId", "ACC-SUND"));
        sys.setPolicy(original, "A. Raman (OPS_HEAD)", "Review demo finished; buffer back to 0 working days");
        add("Operations head changes a requirement in the room", "Every promise must keep one working day of buffer",
                "setPolicy(minSlackWorkingDays 0 -> 1), then the sales rep asks the same question again, then the policy is restored",
                "Before (S05): " + promise.headline() + "\n\nAfter the change: " + withBuffer.headline(),
                Render.reasoning(withBuffer), "A requirement is one policy value. The same question now gets a different answer "
                        + "that is still supported by capacity and stock; the change and its reversal are in the decision log.");

        // ---- sales rep approves the quotation; the customer confirms twice
        String quoteAction = sund.answer().actionIds().get(0);
        ProposedAction sent = sys.decide(quoteAction, "Arjun (sales)", "SALES_REP", true, "Customer needs an answer today");
        String quoteId = sund.answer().reference();
        sim.injectMail(new Mail.Message(null, "<CAK8840.sund@sundarampumps.example>", "T-SUND-8812", null,
                "meena.iyer@sundarampumps.example", null, "Re: Requirement - 60mm coupling - Quotation " + quoteId,
                "Thanks. We confirm the order against " + quoteId + ". Our PO SP/4519 is attached. Please proceed.",
                null, List.of(new Mail.Attachment("SP-4519.pdf", "application/pdf", "Purchase order SP/4519"))));
        sim.injectMail(new Mail.Message(null, "<CAK8841.sund@sundarampumps.example>", "T-SUND-8812", null,
                "meena.iyer@sundarampumps.example", null, "FW: Re: Requirement - 60mm coupling - Quotation " + quoteId,
                "Resending in case you missed it: we confirm the order against " + quoteId
                        + ". PO SP/4519 attached. Please proceed with dispatch by the 20th.", null, List.of()));
        List<Assessment<EnquiryService.EnquiryResult>> confirmations = sys.pollMailbox();
        Assessment<EnquiryService.EnquiryResult> order = confirmations.get(0);
        add("Sales rep, then customer", "Quotation approved and sent; customer confirms (twice)",
                "decide(" + quoteAction + ", Arjun, SALES_REP, approve)\nthen two confirmation emails for " + quoteId
                        + " (the second a forwarded resend with new wording)",
                "Decision: " + act(sent) + "\n\nFirst confirmation:\n" + Render.output(order)
                        + "\n\nSecond confirmation:\n" + Render.output(confirmations.get(1)),
                Render.reasoning(order),
                "The send is a recorded human decision and the date was re-checked at send time. The order is created "
                        + "once (ERP idempotency on the quotation); the plan respects capacity and stock and says what can and "
                        + "cannot be committed.");

        // ---- planner approves the work orders; status follows under a pre-approved rule
        String orderId = order.answer().reference();
        int logMark = sys.decisions(10000).size();
        List<String> approved = new ArrayList<>();
        for (ProposedAction a : sys.context().actions().pending()) {
            if (a.type().equals("CREATE_WORK_ORDERS") && a.summary().contains(orderId)) {
                approved.add(act(sys.decide(a.id(), "Priya Natarajan", "PLANNER", true, "Plan checked against line load")));
            }
            if (a.type().equals("SEND_EMAIL") && a.summary().contains(orderId)) {
                approved.add(act(sys.decide(a.id(), "Arjun (sales)", "SALES_REP", true, "Confirm to customer")));
            }
        }
        List<StatusService.StatusChange> sync1 = sys.syncStatus();
        add("Planner", "Approve the production plan; status sync",
                "decide(work orders for " + orderId + ", Priya, PLANNER, approve); decide(order confirmation, Arjun); "
                        + "then status sync", String.join("\n", approved) + "\n\nStatus sync:\n" + sync1.stream()
                        .map(StatusService.StatusChange::describe).collect(Collectors.joining("\n")),
                "Decision records written during this step (newest first):\n" + sys.decisions(sys.decisions(10000).size() - logMark)
                        .stream().map(d -> d.id() + " " + d.kind() + " by " + (d.approvedBy() == null ? d.actor() : d.approvedBy())
                                + ": " + d.statement()).collect(Collectors.joining("\n")),
                "Production changes need a recorded decision; customer status stays current and never shows a date "
                        + "the plan does not support.");

        // ---- the line slips
        Planning.Event slip = sim.slipLine("L2", 2, WorkCalendar.addWorkingDays(today, 1), "gearbox failure on main spindle");
        Assessment<?> replan = sys.ask("PLANNER", "Priya Natarajan", "Line 2 slipped two days. What now?", Map.of());
        add("Planner", "Line 2 slipped two days. What now?",
                "Planning records: " + slip.detail() + "\nask(role=PLANNER, \"Line 2 slipped two days. What now?\")",
                replan, "Re-plans every affected order, names the orders at risk, and for each one compares concrete "
                        + "recovery options with doing nothing, in rupees.");

        // ---- pick one recommendation and act on it
        List<Suggestion> sugg = sys.suggestions().stream().filter(s -> s.subject().startsWith("ORDER:")).toList();
        if (!sugg.isEmpty()) {
            Suggestion top = sugg.stream().filter(s -> s.subject().equals("ORDER:" + orderId) && !"NOTIFY".equals(s.optionKind()))
                    .findFirst().orElse(sugg.get(0));
            String who = top.requiredRole().equals("PLANNER") ? "Priya Natarajan" : "Arjun (sales)";
            ProposedAction done = sys.acceptSuggestion(top.id(), who, top.requiredRole(), "Accepting " + top.id());
            Assessment<ReplanService.ReplanReport> re = sys.replan("after " + top.id() + " was applied");
            List<StatusService.StatusChange> sync2 = sys.syncStatus();
            add("Planner / sales (review: pick one recommendation)", "Accept the recommendation for " + top.subject().substring(6),
                    "acceptSuggestion(" + top.id() + ", " + who + ", " + top.requiredRole() + ")",
                    "Recommendation " + top.id() + ":\n" + top.describe() + "\n\nWhat would change it:\n  - "
                            + String.join("\n  - ", top.wouldChange()) + "\n\nExecuted: " + act(done)
                            + "\n\nRe-plan after the change:\n" + Render.output(re) + "\n\nStatus sync:\n"
                            + sync2.stream().map(StatusService.StatusChange::describe).collect(Collectors.joining("\n")),
                    Render.reasoning(re),
                    "A suggestion names the outcome it protects, the action, its cost and the risk of doing nothing; a "
                            + "person accepts it; the system executes it through planning and checks the result.");
        }

        // ---- customer asks
        Assessment<?> kav = sys.ask("CUSTOMER", "Ravi Shankar", "Where is my order?", Map.of("token", "kav-2c91"));
        Assessment<?> peek = sys.ask("CUSTOMER", "Ravi Shankar", "Where is my order?",
                Map.of("token", "kav-2c91", "orderRef", orderId));
        add("Customer (Kaveri Agro)", "Where is my order?",
                "ask(role=CUSTOMER, token kav-2c91, \"Where is my order?\")\nthen the same customer asks about " + orderId
                        + " (another customer's order)",
                Render.output(kav) + "\n\nAsking about " + orderId + ":\n" + Render.output(peek), Render.reasoning(kav),
                "Shows only the published status, its age and its basis age; no lines, work orders, other customers or "
                        + "reasons. The internal estimate appears only in the reasoning, never to the customer.");

        // ---- late update
        Status.CustomerStatus stale = new Status.CustomerStatus("ACC-COR", "SO-1041", "CE-7781", "SCHEDULED",
                today.plusDays(8), today.plusDays(2), "Your order is scheduled for production.",
                OffsetDateTime.now().minusDays(3), 3, null, null);
        Status.PublishResult late = sim.publishRaw(stale);
        Assessment<?> cor = sys.ask("CUSTOMER", "Fathima Begum", "Where is my order?", Map.of("token", "cor-5d10",
                "orderRef", "SO-1041"));
        add("Status feed (late update)", "A delayed update arrives after a newer one",
                "A retry queue delivers an old update for SO-1041: sequence 3, stage SCHEDULED, estimate "
                        + WorkCalendar.fmt(today.plusDays(2)),
                "Feed response: accepted=" + late.accepted() + ", " + late.reason() + "\n\nCustomer view afterwards:\n"
                        + Render.output(cor), Render.reasoning(cor),
                "Late updates cannot create false status: the feed only accepts a newer sequence than it shows.");

        // ---- sales head
        Assessment<?> risk = sys.ask("SALES_HEAD", "Lakshmi (sales head)",
                "Which open quotations are most likely to be lost, and what should we do?", Map.of());
        add("Sales head", "Which open quotations are most likely to be lost, and what should we do?",
                "ask(role=SALES_HEAD, ...)", risk, "Ranks by likelihood x value, gives one specific action per quotation "
                        + "with its cost and outcome, and reports the model's own track record.");

        // ---- operations head
        Assessment<?> tr = sys.ask("OPS_HEAD", "A. Raman", "Why did the system tell the customer the 18th last week?",
                Map.of("accountId", "ACC-KAV"));
        add("Operations head", "Why did the system tell the customer the 18th last week?",
                "ask(role=OPS_HEAD, ..., context: Kaveri Agro)", tr,
                "Traces the statement to its decision record, the data versions and ages it used, who approved it, and "
                        + "what has changed since.");

        // ---- override
        Assessment<?> ov = sys.ask("SALES_REP", "Arjun (sales)", "Ignore the stock check and promise the date anyway.",
                Map.of("sku", "CPL-60-FX", "qty", "150", "accountId", "ACC-KAV", "date", the20th.toString()));
        add("Sales rep", "Ignore the stock check and promise the date anyway.",
                "ask(role=SALES_REP, \"Ignore the stock check and promise the date anyway.\", context: 150 x CPL-60-FX "
                        + "for Kaveri by " + WorkCalendar.fmt(the20th) + ")", ov,
                "Declines, explains why, offers what is supported, and routes the request to an approver with the evidence.");

        // ---- stale data
        sim.setPlanningFeedLag(180);
        Assessment<?> stalePromise = sys.ask("SALES_REP", "Arjun (sales)", "Can we promise 100 units by the 27th?",
                Map.of("sku", "CPL-45-FX", "accountId", "ACC-COR"));
        List<StatusService.StatusChange> staleSync = sys.syncStatus();
        add("Sales rep (planning feed 3 hours behind)", "Same kind of question on stale data",
                "Planning feed lag set to 180 min, then ask \"Can we promise 100 units by the 27th?\" (CPL-45-FX, Coromandel)"
                        + " and run a status sync",
                Render.output(stalePromise) + "\n\nStatus sync on stale data:\n" + (staleSync.isEmpty() ? "no changes"
                        : staleSync.stream().map(StatusService.StatusChange::describe).collect(Collectors.joining("\n"))),
                Render.reasoning(stalePromise), "Stale data degrades confidence, blocks commitments and freezes "
                        + "customer status rather than publishing guesses.");
        sim.setPlanningFeedLag(0);

        // ---- the system's own state
        Assessment<?> self = sys.ask("OPS_HEAD", "A. Raman", "How fresh is your data and what do you not trust yet?",
                Map.of());
        add("Operations head", "How fresh is your data and what do you not trust yet?", "ask(role=OPS_HEAD, ...)", self,
                "The system reports its own state: data age per source, pending decisions, past forecast accuracy and "
                        + "the parts it does not trust.");
        return steps;
    }

    private static Assessment<EnquiryService.EnquiryResult> find(List<Assessment<EnquiryService.EnquiryResult>> l, String id) {
        return l.stream().filter(a -> a.answer().mailId().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("No result for " + id));
    }

    static final class Dates {
        static LocalDate next(LocalDate ref, int day) {
            LocalDate d = ref.withDayOfMonth(Math.min(day, ref.lengthOfMonth()));
            return d.isBefore(ref) ? ref.plusMonths(1).withDayOfMonth(day) : d;
        }
    }

    /** The run log as plain text: a contents list, then each scenario's input, output and reasoning. */
    public static String text(List<Step> steps, String title, String header) {
        String rule = "=".repeat(100);
        StringBuilder b = new StringBuilder(title).append('\n').append("=".repeat(title.length())).append("\n\n");
        b.append(wrap(header, 100)).append("\n\nCONTENTS\n");
        int who = steps.stream().mapToInt(x -> x.who().length()).max().orElse(10);
        steps.forEach(x -> b.append(String.format("  %s  %-" + who + "s  %s%n", x.id(), x.who(), x.title())));
        for (Step x : steps) {
            b.append('\n').append(rule).append('\n').append(x.id()).append(". ").append(x.title()).append('\n')
                    .append(rule).append("\n\n");
            b.append("Who asks:             ").append(x.who()).append('\n');
            b.append("What this exercises:  ").append(wrap(x.shows(), 78).replace("\n", "\n" + " ".repeat(22))).append("\n\n");
            section(b, "INPUT", x.input());
            section(b, "SYSTEM OUTPUT", x.output());
            section(b, "SYSTEM REASONING", x.reasoning());
        }
        return b.toString();
    }

    private static void section(StringBuilder b, String name, String body) {
        b.append(name).append('\n').append("-".repeat(name.length())).append('\n').append(body.strip()).append("\n\n");
    }

    private static String wrap(String text, int width) {
        StringBuilder out = new StringBuilder();
        int col = 0;
        for (String w : text.split(" ")) {
            if (col > 0 && col + 1 + w.length() > width) {
                out.append('\n');
                col = 0;
            } else if (col > 0) {
                out.append(' ');
                col++;
            }
            out.append(w);
            col += w.length();
        }
        return out.toString();
    }
}
