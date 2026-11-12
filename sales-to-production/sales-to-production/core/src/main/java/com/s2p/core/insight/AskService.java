package com.s2p.core.insight;

import com.s2p.core.CoreContext;
import com.s2p.core.engine.PromiseAssessor;
import com.s2p.core.engine.PromiseEngine;
import com.s2p.core.engine.Scheduler;
import com.s2p.core.engine.WorkCalendar;
import com.s2p.core.governance.ActionPayload;
import com.s2p.core.governance.DecisionRecord;
import com.s2p.core.governance.ProposedAction;
import com.s2p.core.knowledge.Assessment;
import com.s2p.core.knowledge.Confidence;
import com.s2p.core.knowledge.Describable;
import com.s2p.core.knowledge.Fact;
import com.s2p.core.planning.ReplanService;
import com.s2p.core.policy.Policy;
import com.s2p.core.sales.QuotationRiskService;
import com.s2p.core.state.CentralView;
import com.s2p.core.state.Source;
import com.s2p.core.status.StatusService;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * One entry point for questions in plain words. The language model reads the question through {@link AskPlanner}:
 * which service it is for and with which customer, product, quantity and dates. The answer always comes from that
 * deterministic service, never from the model. Requests that would break a principle are refused, not bent.
 *
 * If the model cannot be reached, nothing in the question is interpreted: the answer says so, and a promise check
 * still runs if the customer, product and quantity were picked in the form.
 */
public final class AskService {
    public enum Intent { OVERRIDE, PROMISE, LINE_SLIP, CUSTOMER_STATUS, QUOTE_RISK, TRACE, SELF, UNKNOWN }

    public record PromiseAnswer(String verdict, String sku, int qty, LocalDate requested, LocalDate supportedDate,
                                int qtyByRequested, LocalDate saferDate, String statement, PromiseEngine.Result detail)
            implements Describable {
        @Override
        public String describe() {
            return verdict + ": " + statement;
        }
    }

    public record OverrideDecision(boolean declined, String reason, String supportedAlternative, String routedTo,
                                   String actionId) implements Describable {
        @Override
        public String describe() {
            return (declined ? "DECLINED. " : "") + reason + "\n  What I can support: " + supportedAlternative
                    + "\n  Routed to: " + routedTo + " as " + actionId;
        }
    }

    /**
     * An answer plus how the question was read. {@code readAs} says what the model understood; {@code resolved} is
     * the customer, product, quantity and dates actually used; {@code reader} is "model" or "not read (model
     * unavailable)".
     */
    public record Answer(Assessment<?> assessment, String readAs, Map<String, String> resolved, String reader) {
    }

    public record Unknown(String help) implements Describable {
        @Override
        public String describe() {
            return help;
        }
    }

    private final CoreContext ctx;
    private final ReplanService replan;
    private final StatusService status;
    private final QuotationRiskService quotes;
    private final TraceService trace;
    private final SelfStateService self;
    private final AskPlanner planner;

    public AskService(CoreContext ctx, ReplanService replan, StatusService status, QuotationRiskService quotes,
                      TraceService trace, SelfStateService self) {
        this(ctx, replan, status, quotes, trace, self, new AskPlanner(ctx, ctx.ai()));
    }

    public AskService(CoreContext ctx, ReplanService replan, StatusService status, QuotationRiskService quotes,
                      TraceService trace, SelfStateService self, AskPlanner planner) {
        this.planner = planner;
        this.ctx = ctx;
        this.replan = replan;
        this.status = status;
        this.quotes = quotes;
        this.trace = trace;
        this.self = self;
    }

    public Assessment<?> ask(String role, String user, String text, Map<String, String> context) {
        return answer(role, user, text, context).assessment();
    }

    public Answer answer(String role, String user, String text, Map<String, String> context) {
        Map<String, String> given = context == null ? Map.of() : context;
        Optional<AskPlanner.Routed> routed = planner.route(role, text, given);
        Assessment<?> a;
        String readAs;
        Map<String, String> used = routed.map(AskPlanner.Routed::context).orElse(given);
        if (routed.isPresent()) {
            readAs = routed.get().readAs();
            a = dispatch(role, user, text, routed.get().intent(), used);
        } else {
            String why = ctx.ai().enabled() ? "the language model could not be reached"
                    + (ctx.ai().telemetry().lastError() == null ? "" : " (" + ctx.ai().telemetry().lastError() + ")")
                    : "no language model is configured";
            boolean picked = !"CUSTOMER".equals(role) && used.get("sku") != null && used.get("qty") != null
                    && used.get("accountId") != null;
            readAs = "Not read: " + why + "."
                    + (picked ? " Checked the promise from the customer, product, quantity and date picked in the form." : "");
            a = picked ? promise(text, used) : info(text, "I cannot read questions right now: " + why + ". "
                    + ("CUSTOMER".equals(role) ? "Please try again shortly." : "A promise check still works if you pick "
                    + "the customer, product and quantity (and the date) in the form."));
        }
        List<String> wc = new ArrayList<>(a.wouldChange());
        wc.add(readAs);
        a = new Assessment<>(a.question(), a.headline(), a.answer(), a.confidence(), a.freshness(), a.basis(),
                a.notChecked(), wc, a.decisionId());
        Map<String, String> resolved = new LinkedHashMap<>(used);
        resolved.remove("token");
        return new Answer(a, readAs, resolved, routed.isPresent() ? "model" : "not read (model unavailable)");
    }

    private Assessment<?> dispatch(String role, String user, String text, Intent intent, Map<String, String> context) {
        if ("CUSTOMER".equals(role) && intent != Intent.CUSTOMER_STATUS) {
            return info(text, "Customers can ask about their own orders here; other questions go to your sales contact.");
        }
        return switch (intent) {
            case OVERRIDE -> override(role, user, text, context);
            case PROMISE -> promise(text, context);
            case LINE_SLIP -> lineSlip(text, context.get("lineId"));
            case CUSTOMER_STATUS -> status.customerView(context.getOrDefault("token", ""), context.get("orderRef"));
            case QUOTE_RISK -> quotes.rank();
            case TRACE -> trace.trace(text, context.get("accountId"), context.get("orderId"), date(context, "dateStated"),
                    date(context, "saidFrom"), date(context, "saidTo"));
            case SELF -> self.report();
            case UNKNOWN -> info(text, "I can answer: whether a quantity can be promised by a date; what a line slip "
                    + "does to orders; where a customer's order is; which quotations are at risk; why the system told a "
                    + "customer something; and how fresh my data is.");
        };
    }

    /** A promise check from structured inputs: sku, qty, accountId and optionally date (the model or the form fills them). */
    public Assessment<PromiseAnswer> promise(String text, Map<String, String> context) {
        CentralView v = ctx.state().view();
        Policy p = ctx.policy().get();
        LocalDate date = date(context, "date");
        Integer qty = context.containsKey("qty") ? Integer.valueOf(context.get("qty")) : null;
        String sku = context.get("sku");
        String account = context.get("accountId");
        if (qty == null || sku == null || account == null) {
            return info(text, "To check a promise I need the product, the quantity and the customer"
                    + (date == null ? ", and ideally the date" : "") + ". Missing: "
                    + (sku == null ? "product " : "") + (qty == null ? "quantity " : "") + (account == null ? "customer" : ""));
        }
        PromiseAssessor.Assessed as = ctx.promises().assess(new PromiseEngine.Request(sku, qty, date, account, null), v, p, List.of());
        PromiseEngine.Result r = as.result();
        String verdict;
        String statement;
        String split = r.byLine().entrySet().stream().map(e -> e.getValue().stream().mapToInt(Scheduler.Placement::qty).sum()
                + " on " + e.getKey() + " (" + WorkCalendar.fmt(e.getValue().get(0).date()) + " to "
                + WorkCalendar.fmt(e.getValue().get(e.getValue().size() - 1).date()) + ")").collect(Collectors.joining(" and "));
        String made = r.fromStock() + " from stock" + (r.toMake() > 0 ? ", " + r.toMake() + " to make: " + split : "");
        LocalDate safer = r.complete() ? WorkCalendar.addWorkingDays(r.supportedDate(), 1) : null;
        if (!v.canCommit()) {
            verdict = "INDICATIVE ONLY";
            statement = "Data is too old to commit (" + v.freshness(Source.PLANNING).describe() + "). On that data the "
                    + "earliest would be " + WorkCalendar.fmt(r.earliestDelivery()) + ". Do not promise a date until it refreshes.";
        } else if (!r.complete()) {
            verdict = "NO";
            statement = "No date within the planning horizon: " + r.bindingConstraint() + ".";
        } else if (date == null) {
            verdict = "EARLIEST";
            statement = qty + " x " + sku + " can reach " + v.accountName(account) + " by " + WorkCalendar.fmt(r.earliestDelivery())
                    + ". " + made + ".";
        } else if (r.feasible()) {
            verdict = "YES";
            statement = qty + " x " + sku + " by " + WorkCalendar.fmt(date) + " is supported for " + v.accountName(account)
                    + ": " + made + ". Slack " + r.slackWorkingDays() + " working day(s). " + String.join(" ", r.notes())
                    + (r.slackWorkingDays() == 0 ? " A date with one working day of buffer would be " + WorkCalendar.fmt(safer) + "." : "");
        } else {
            verdict = "NO";
            statement = "Not all " + qty + " by " + WorkCalendar.fmt(date) + ". Supported: " + r.qtyByRequested() + " by "
                    + WorkCalendar.fmt(date) + ", all " + qty + " by " + WorkCalendar.fmt(r.supportedDate()) + ". " + made
                    + ". Binding constraint: " + r.bindingConstraint() + ". " + String.join(" ", r.notes());
        }
        String headline = verdict + ". " + statement + " Confidence " + as.confidence().describe() + ".";
        DecisionRecord rec = ctx.log().record("PROMISE_ANSWER", "system", account, null, null, headline,
                r.supportedDate(), qty, as.basis().stream().map(Fact::describe).toList(), v, as.confidence().score(), null,
                null, null, p.version());
        return new Assessment<>(text, headline, new PromiseAnswer(verdict, sku, qty, date, r.supportedDate(),
                r.qtyByRequested(), safer, statement, r), as.confidence(),
                CoreContext.fresh(v, Source.ERP, Source.PLANNING, Source.CRM), as.basis(), as.notChecked(),
                as.wouldChange(), rec.id());
    }

    private Assessment<?> lineSlip(String text, String line) {
        if (line == null) {
            return info(text, "Which line slipped? Name the line (for example Line 2) and I will check planning.");
        }
        CentralView v = ctx.state().view();
        boolean recorded = ctx.planning().events(0).stream().anyMatch(e -> "LINE_SLIP".equals(e.type())
                && line != null && line.equals(e.lineId()) && e.occurredAt().toLocalDate().equals(v.today()));
        if (!recorded) {
            return info(text, "The planning system does not show a slip on " + line + " today. Planning is the source of "
                    + "truth for capacity, so I will not re-plan on a report alone: record the slip in planning and I re-plan "
                    + "on the event automatically.");
        }
        return replan.replan(line + " slip reported: " + text);
    }

    private Assessment<OverrideDecision> override(String role, String user, String text, Map<String, String> context) {
        CentralView v = ctx.state().view();
        Policy p = ctx.policy().get();
        String sku = context.get("sku");
        String account = context.get("accountId");
        int qty = context.containsKey("qty") ? Integer.parseInt(context.get("qty")) : 0;
        LocalDate date = context.containsKey("date") ? LocalDate.parse(context.get("date")) : null;
        String alternative = "no promise context given";
        LocalDate supported = null;
        if (sku != null && account != null && qty > 0) {
            PromiseEngine.Result r = ctx.promises().engine().evaluate(new PromiseEngine.Request(sku, qty, date, account, null),
                    v, p, PromiseEngine.Adjustments.NONE);
            supported = r.supportedDate();
            int producible = r.fromStock() + r.placements().stream().mapToInt(Scheduler.Placement::qty).sum();
            alternative = r.feasible() && date != null
                    ? qty + " by " + WorkCalendar.fmt(date) + " IS supported today; no override is needed"
                    : r.complete() ? r.qtyByRequested() + " by " + WorkCalendar.fmt(date) + ", all " + qty + " by "
                    + WorkCalendar.fmt(r.supportedDate())
                    : r.qtyByRequested() + " by " + WorkCalendar.fmt(date) + "; the full " + qty + " is not possible within "
                    + p.horizonDays() + " days (" + r.bindingConstraint() + "), at most " + producible + " units";
        }
        String reason = "I will not promise a date or quantity that current stock and capacity do not support, and I "
                + "do not skip the stock check. A promise outside the plan is a management decision, so I have routed "
                + "your request to the Head of Operations with the evidence.";
        ProposedAction a = ctx.actions().propose(ProposedAction.draft("EXCEPTION_REQUEST", "Exception requested by " + user
                        + " (" + role + "): \"" + text + "\"" + (sku == null ? "" : " for " + qty + " x " + sku + " by "
                        + WorkCalendar.fmt(date)) + "; supported: " + alternative,
                new ActionPayload.ExceptionRequest(text, user, account, sku, qty, date, supported), "OPS_HEAD",
                "exception:" + user + ":" + sku + ":" + qty + ":" + date, Map.of(), List.of(), null));
        DecisionRecord rec = ctx.log().record("OVERRIDE_DECLINED", "system", account, null, null, "Declined '" + text
                        + "' from " + user + " (" + role + "); routed to OPS_HEAD as " + a.id(), date, qty,
                List.of("supported alternative: " + alternative), v, 1.0, null, null, a.id(), p.version());
        OverrideDecision d = new OverrideDecision(true, reason, alternative, "Head of Operations (OPS_HEAD)", a.id());
        return new Assessment<>(text, "DECLINED.", d,
                Confidence.start().apply("policy", 1.0, "Section 4: never present a date or quantity that capacity and stock do not support").build(),
                CoreContext.fresh(v, Source.ERP, Source.PLANNING), List.of(new Fact("POLICY", p.version(),
                "Customer-facing dates must be supported by current capacity and stock; exceptions need OPS_HEAD", 0, 0)),
                List.of(), List.of("An OPS_HEAD approval records a management commitment, labelled as unsupported by the "
                + "plan; it does not make the system present the date as supported."), rec.id());
    }

    private static LocalDate date(Map<String, String> context, String key) {
        String s = context.get(key);
        return s == null || s.isBlank() ? null : LocalDate.parse(s);
    }

    private <T> Assessment<T> info(String text, String message) {
        CentralView v = ctx.state().view();
        @SuppressWarnings("unchecked")
        T answer = (T) new Unknown(message);
        return new Assessment<>(text, message, answer, Confidence.start().build(),
                CoreContext.fresh(v, Source.ERP, Source.PLANNING), List.of(), List.of(), List.of(), null);
    }
}
