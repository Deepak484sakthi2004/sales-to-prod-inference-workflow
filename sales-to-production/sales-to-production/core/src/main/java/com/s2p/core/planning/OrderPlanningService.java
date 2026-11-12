package com.s2p.core.planning;

import com.s2p.contracts.Erp;
import com.s2p.contracts.Planning;
import com.s2p.core.CoreContext;
import com.s2p.core.engine.OrderEta;
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
import com.s2p.core.policy.Policy;
import com.s2p.core.state.CentralView;
import com.s2p.core.state.Source;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * From a confirmed order to a production plan that respects free capacity and usable material, with an explicit
 * statement of what can and cannot be committed. Reservations run under rule R2; work orders wait for a planner
 * unless rule R3 covers them.
 */
public final class OrderPlanningService {
    public record PlanLine(String sku, int qty, int alreadyReserved, int reserveNow, int toMake,
                           Map<String, List<Scheduler.Placement>> byLine, LocalDate earliestDelivery,
                           LocalDate committed, boolean committedSupported, String canCommit, String cannotCommit) {
    }

    public record ProductionPlan(String orderId, String accountId, List<PlanLine> lines, List<String> actionIds,
                                 String summary) implements Describable {
        @Override
        public String describe() {
            StringBuilder b = new StringBuilder(summary);
            for (PlanLine l : lines) {
                b.append("\n  ").append(l.sku()).append(": ").append(l.qty()).append(" ordered; ")
                        .append(l.alreadyReserved() + l.reserveNow()).append(" from stock, ").append(l.toMake()).append(" to make");
                l.byLine().forEach((line, pls) -> b.append("\n    ").append(line).append(": ").append(pls.stream()
                        .map(p -> p.qty() + " on " + WorkCalendar.fmt(p.date())).collect(Collectors.joining(", "))));
                b.append("\n    CAN commit: ").append(l.canCommit());
                b.append("\n    CANNOT commit: ").append(l.cannotCommit());
            }
            if (!actionIds.isEmpty()) {
                b.append("\n  Actions: ").append(String.join(", ", actionIds));
            }
            return b.toString();
        }
    }

    private final CoreContext ctx;

    public OrderPlanningService(CoreContext ctx) {
        this.ctx = ctx;
    }

    public Assessment<ProductionPlan> plan(String orderId) {
        CentralView v = ctx.state().view();
        Policy p = ctx.policy().get();
        Erp.SalesOrder o = v.order(orderId).orElseThrow(() -> new IllegalArgumentException("Unknown order " + orderId));
        if (!v.workOrdersFor(orderId).isEmpty()) {
            return existingPlan(o, v, p);
        }
        List<PlanLine> lines = new ArrayList<>();
        List<String> actionIds = new ArrayList<>();
        Confidence.Builder conf = Confidence.start();
        List<Fact> basis = new ArrayList<>();
        Set<String> notChecked = new LinkedHashSet<>();
        List<String> wouldChange = new ArrayList<>();
        int minSlack = Integer.MAX_VALUE;
        for (Erp.OrderLine ol : o.lines()) {
            PromiseAssessor.Assessed as = ctx.promises().assess(new PromiseEngine.Request(ol.sku(), ol.qty(),
                    ol.committedDate(), o.accountId(), orderId), v, p, List.of());
            PromiseEngine.Result r = as.result();
            conf.applyAll(as.confidence().factors());
            basis.addAll(as.basis());
            notChecked.addAll(as.notChecked());
            wouldChange.addAll(as.wouldChange());
            int already = v.reservationsFor(orderId).stream().filter(x -> x.sku().equals(ol.sku()))
                    .mapToInt(Erp.Reservation::qty).sum();
            int reserveNow = Math.max(0, r.fromStock() - already);
            String can = r.complete() ? ol.qty() + " by " + WorkCalendar.fmt(r.supportedDate())
                    + (r.feasible() ? " (slack " + r.slackWorkingDays() + " working days)" : " (earliest possible)")
                    : "nothing within the planning horizon";
            String cannot;
            if (!r.complete()) {
                cannot = "any date: " + r.bindingConstraint();
            } else {
                LocalDate dayBefore = WorkCalendar.minusWorkingDays(r.supportedDate(), 1);
                PromiseEngine.Result earlier = ctx.promises().engine().evaluate(new PromiseEngine.Request(ol.sku(),
                        ol.qty(), dayBefore, o.accountId(), orderId), v, p, PromiseEngine.Adjustments.NONE);
                cannot = "all " + ol.qty() + " any earlier than " + WorkCalendar.fmt(r.supportedDate()) + "; at most "
                        + earlier.qtyByRequested() + " by " + WorkCalendar.fmt(dayBefore)
                        + (r.feasible() ? "" : "; the committed " + WorkCalendar.fmt(ol.committedDate()) + " is NOT supported");
            }
            if (r.slackWorkingDays() != null) {
                minSlack = Math.min(minSlack, r.slackWorkingDays());
            }
            lines.add(new PlanLine(ol.sku(), ol.qty(), already, reserveNow, r.toMake(), r.byLine(), r.supportedDate(),
                    ol.committedDate(), r.feasible(), can, cannot));
            if (reserveNow > 0) {
                ProposedAction res = ctx.actions().propose(ProposedAction.draft("RESERVE_STOCK", "Reserve " + reserveNow
                                + " x " + ol.sku() + " for " + orderId,
                        new ActionPayload.Reserve(new Erp.NewReservation("RES:" + orderId + ":" + ol.sku(), ol.sku(),
                                reserveNow, orderId)), "PLANNER", "reserve:" + orderId + ":" + ol.sku(), Map.of(),
                        List.of(), null));
                actionIds.add(res.id());
            }
            if (r.toMake() > 0 && r.complete()) {
                List<Planning.NewWorkOrder> wos = new ArrayList<>();
                r.byLine().forEach((line, pls) -> wos.add(new Planning.NewWorkOrder("WO:" + orderId + ":" + ol.sku()
                        + ":" + line, orderId, ol.sku(), line, pls.stream().mapToInt(Scheduler.Placement::qty).sum(),
                        pls.stream().map(pl -> new Planning.Allocation(pl.date(), pl.qty())).toList(), List.of())));
                ProposedAction wa = ctx.actions().propose(ProposedAction.draft("CREATE_WORK_ORDERS", "Create "
                                + wos.size() + " work order(s) for " + orderId + " (" + wos.stream().map(w -> w.qty()
                                + " on " + w.lineId()).collect(Collectors.joining(", ")) + "), finish "
                                + WorkCalendar.fmt(r.productionFinish()),
                        new ActionPayload.CreateWorkOrders(orderId, wos), "PLANNER", "wo:" + orderId + ":" + ol.sku(),
                        Map.of("slackWorkingDays", r.slackWorkingDays() == null ? -99 : r.slackWorkingDays(),
                                "orderValue", o.value(), "dataFresh", v.freshness(Source.PLANNING).fresh()),
                        List.of(), null));
                actionIds.add(wa.id());
            }
        }
        Confidence c = conf.build();
        boolean allOk = lines.stream().allMatch(PlanLine::committedSupported);
        String summary = "Plan for " + orderId + " (" + v.accountName(o.accountId()) + "): " + (allOk
                ? "committed date " + WorkCalendar.fmt(lines.get(0).committed()) + " is supported"
                : "committed date is NOT supported; earliest " + WorkCalendar.fmt(lines.get(0).earliestDelivery()))
                + (minSlack == Integer.MAX_VALUE ? "" : " with " + minSlack + " working day(s) slack") + ".";
        DecisionRecord rec = ctx.log().record("PLAN_PROPOSED", "system", o.accountId(), orderId, o.quotationId(), summary,
                lines.get(0).committedSupported() ? lines.get(0).committed() : lines.get(0).earliestDelivery(), lines.get(0).qty(), basis.stream().map(Fact::describe).toList(), v,
                c.score(), null, null, String.join(",", actionIds), p.version());
        return new Assessment<>("Production plan for " + orderId, summary, new ProductionPlan(orderId, o.accountId(),
                lines, actionIds, summary), c, CoreContext.fresh(v, Source.ERP, Source.PLANNING, Source.CRM), basis,
                List.copyOf(notChecked), wouldChange, rec.id());
    }

    private Assessment<ProductionPlan> existingPlan(Erp.SalesOrder o, CentralView v, Policy p) {
        OrderEta eta = OrderEta.of(o, v, p);
        String summary = o.id() + " is already planned: estimate " + WorkCalendar.fmt(eta.estimate()) + " against "
                + WorkCalendar.fmt(eta.committed()) + " committed (" + String.join(", ", eta.workOrders()) + ").";
        return new Assessment<>("Production plan for " + o.id(), summary,
                new ProductionPlan(o.id(), o.accountId(), List.of(), List.of(), summary),
                Confidence.start().apply("existing plan", eta.lateDays() > 0 ? 0.5 : 0.9, eta.lateDays() > 0
                        ? "the existing plan is " + eta.lateDays() + " day(s) late" : "existing work orders").build(),
                CoreContext.fresh(v, Source.ERP, Source.PLANNING),
                eta.basis().stream().map(b -> v.fact(Source.PLANNING, o.id(), b)).toList(), List.of(), List.of(), null);
    }
}
