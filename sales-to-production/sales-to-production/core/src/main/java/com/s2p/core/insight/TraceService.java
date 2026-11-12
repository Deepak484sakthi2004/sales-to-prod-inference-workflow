package com.s2p.core.insight;

import com.s2p.contracts.Erp;
import com.s2p.contracts.Planning;
import com.s2p.core.CoreContext;
import com.s2p.core.engine.OrderEta;
import com.s2p.core.engine.WorkCalendar;
import com.s2p.core.governance.DecisionRecord;
import com.s2p.core.knowledge.Assessment;
import com.s2p.core.knowledge.Confidence;
import com.s2p.core.knowledge.Describable;
import com.s2p.core.knowledge.Fact;
import com.s2p.core.state.CentralView;
import com.s2p.core.state.Source;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Answers "why did the system say X?" from the decision log, then shows what has changed since. */
public final class TraceService {
    public record TraceReport(DecisionRecord decision, List<String> then, List<String> since, String now,
                              List<DecisionRecord> laterDecisions) implements Describable {
        @Override
        public String describe() {
            StringBuilder b = new StringBuilder();
            b.append("Decision ").append(decision.id()).append(" at ").append(decision.at().toLocalDateTime().withNano(0))
                    .append(" (").append(decision.kind()).append("): ").append(decision.statement());
            b.append("\n  Approved by: ").append(decision.approvedBy() == null ? "n/a" : decision.approvedBy())
                    .append(decision.ruleId() == null ? "" : " under rule " + decision.ruleId());
            b.append("\n  Data it used at the time:");
            then.forEach(t -> b.append("\n    - ").append(t));
            b.append("\n  What changed since:");
            since.forEach(t -> b.append("\n    - ").append(t));
            b.append("\n  Now: ").append(now);
            laterDecisions.forEach(d -> b.append("\n  Later: ").append(d.id()).append(" ").append(d.at().toLocalDateTime().withNano(0))
                    .append(" ").append(d.kind()).append(": ").append(d.statement()));
            return b.toString();
        }
    }

    private final CoreContext ctx;

    public TraceService(CoreContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Finds the customer-facing statement a question is about and shows the data behind it. The language model reads
     * the question into {@code dateStated} (the date the customer was told) and the period it was said in; without
     * them the last 60 days are searched.
     */
    public Assessment<TraceReport> trace(String question, String accountId, String orderId, LocalDate dateStated,
                                         LocalDate saidFrom, LocalDate saidTo) {
        CentralView v = ctx.state().view();
        LocalDate from = saidFrom != null ? saidFrom : v.today().minusDays(60);
        LocalDate to = saidTo != null ? saidTo : v.today();
        Optional<DecisionRecord> hit = ctx.log().find(r -> r.customerFacing()
                        && (dateStated == null || dateStated.equals(r.dateStated()))
                        && !r.at().toLocalDate().isBefore(from) && !r.at().toLocalDate().isAfter(to)
                        && (accountId == null || accountId.equals(r.accountId()))
                        && (orderId == null || orderId.equals(r.orderId())))
                .stream().max(Comparator.comparing(DecisionRecord::at));
        if (hit.isEmpty()) {
            String h = "No customer-facing statement matches" + (dateStated == null ? "" : " " + WorkCalendar.fmt(dateStated))
                    + " said between " + WorkCalendar.fmt(from) + " and " + WorkCalendar.fmt(to)
                    + ". Nothing was said that I can trace.";
            return new Assessment<>(question, h, null, Confidence.start().apply("search", 0.9, "decision log searched").build(),
                    CoreContext.fresh(v, Source.ERP, Source.PLANNING), List.of(), List.of("Messages sent outside this system."),
                    List.of(), null);
        }
        DecisionRecord d = hit.get();
        List<String> then = new ArrayList<>(d.basis());
        d.sourceVersions().forEach((s, ver) -> then.add(s + " version " + ver + ", data age " + d.dataAge().getOrDefault(s, "?")));
        if (d.confidence() != null) {
            then.add("Confidence at the time: " + d.confidence());
        }
        List<String> since = new ArrayList<>();
        String now = "order no longer open";
        Optional<Erp.SalesOrder> order = d.orderId() == null ? Optional.empty() : v.order(d.orderId());
        if (order.isPresent()) {
            OrderEta eta = OrderEta.of(order.get(), v, ctx.policy().get());
            now = "estimate " + WorkCalendar.fmt(eta.estimate()) + " against " + WorkCalendar.fmt(eta.committed())
                    + " committed" + (eta.lateDays() > 0 ? " (" + eta.lateDays() + " day(s) late)" : "") + "; "
                    + String.join("; ", eta.basis());
            for (Planning.Event e : ctx.planning().events(0)) {
                if (e.occurredAt().isAfter(d.at()) && (eta.workOrders().contains(e.workOrderId())
                        || (e.type().equals("LINE_SLIP") && v.workOrdersFor(d.orderId()).stream()
                        .anyMatch(w -> w.lineId().equals(e.lineId()))))) {
                    since.add(e.occurredAt().toLocalDateTime().withNano(0) + " " + e.type() + ": " + e.detail());
                }
            }
            v.workOrdersFor(d.orderId()).forEach(w -> {
                if (w.originalPlannedFinish() != null && !w.originalPlannedFinish().equals(w.plannedFinish())) {
                    since.add(w.id() + " planned finish moved from " + WorkCalendar.fmt(w.originalPlannedFinish())
                            + " to " + WorkCalendar.fmt(w.plannedFinish()));
                }
            });
        }
        if (since.isEmpty()) {
            since.add("No planning events affecting this order since the decision.");
        }
        List<DecisionRecord> later = ctx.log().find(r -> r.at().isAfter(d.at()) && d.orderId() != null
                && d.orderId().equals(r.orderId()) && r.customerFacing());
        String headline = "On " + WorkCalendar.fmt(d.at().toLocalDate()) + " the system told "
                + v.accountName(d.accountId()) + " " + (d.dateStated() == null ? "" : WorkCalendar.fmt(d.dateStated()))
                + " because, at planning version " + d.sourceVersions().getOrDefault("PLANNING", 0L) + ", "
                + (d.basis().isEmpty() ? "its basis" : d.basis().get(0)) + ". Now: " + now.split(";")[0] + ".";
        return new Assessment<>(question, headline, new TraceReport(d, then, since, now, later),
                Confidence.start().apply("audit trail", 0.98, "the decision record stores the data versions and ages used").build(),
                CoreContext.fresh(v, Source.PLANNING, Source.ERP), d.basis().stream()
                .map(b -> new Fact("DECISION_LOG", d.id(), b, 0, 0)).toList(),
                List.of("Conversations by phone that were never recorded in the system."),
                List.of("None: this is a record of the past. The 'now' line updates with the plan."), d.id());
    }
}
