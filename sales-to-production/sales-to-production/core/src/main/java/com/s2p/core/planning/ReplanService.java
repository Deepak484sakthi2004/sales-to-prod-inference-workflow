package com.s2p.core.planning;

import com.s2p.contracts.Crm;
import com.s2p.contracts.Erp;
import com.s2p.contracts.Mail;
import com.s2p.contracts.Planning;
import com.s2p.contracts.Status;
import com.s2p.core.CoreContext;
import com.s2p.core.engine.CapacityModel;
import com.s2p.core.engine.LatenessCost;
import com.s2p.core.engine.MaterialModel;
import com.s2p.core.engine.Money;
import com.s2p.core.engine.OrderEta;
import com.s2p.core.engine.Scheduler;
import com.s2p.core.engine.WorkCalendar;
import com.s2p.core.governance.ActionPayload;
import com.s2p.core.governance.DecisionRecord;
import com.s2p.core.governance.Suggestion;
import com.s2p.core.knowledge.Assessment;
import com.s2p.core.knowledge.Confidence;
import com.s2p.core.knowledge.Describable;
import com.s2p.core.knowledge.Fact;
import com.s2p.core.policy.Policy;
import com.s2p.core.state.CentralView;
import com.s2p.core.state.Source;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Re-plans after production moves. For each order now at risk it builds concrete recovery options by re-running the
 * scheduler with one lever changed (overtime, alternate line, resequencing, split delivery, early notice), prices
 * each one, and compares it with doing nothing. Only the comparison decides the ranking.
 */
public final class ReplanService {
    public record OrderImpact(String orderId, String account, String tier, BigDecimal value, LocalDate committed,
                              LocalDate previousEstimate, LocalDate currentEstimate, int lateDays, Integer slack,
                              boolean atRisk, List<String> workOrders) {
    }

    public record Option(String orderId, String kind, String description, LocalDate newEstimate, int newLateDays,
                         BigDecimal cost, String costNote, double pSuccess, BigDecimal lossIfItWorks,
                         BigDecimal expectedLoss, BigDecimal netBenefit, String requiredRole, List<String> sideEffects,
                         List<String> wouldChange, ActionPayload payload) {
    }

    public record ReplanReport(String trigger, List<String> events, List<OrderImpact> impacts,
                               Map<String, List<Option>> options, List<Suggestion> suggestions, String summary)
            implements Describable {
        @Override
        public String describe() {
            StringBuilder b = new StringBuilder(summary);
            if (!events.isEmpty()) {
                b.append("\n  Planning events read: ").append(String.join(" | ", events));
            }
            b.append("\n  Orders whose estimate moved or that are at risk:");
            impacts.stream().filter(i -> i.atRisk() || !java.util.Objects.equals(i.previousEstimate(), i.currentEstimate()))
                    .forEach(i -> b.append("\n    ").append(i.orderId()).append(" ").append(i.account()).append(" (")
                            .append(Money.inr(i.value())).append("): committed ").append(WorkCalendar.fmt(i.committed()))
                            .append(", estimate ").append(WorkCalendar.fmt(i.previousEstimate())).append(" -> ")
                            .append(WorkCalendar.fmt(i.currentEstimate()))
                            .append(i.atRisk() ? "  AT RISK, " + i.lateDays() + " day(s) late" : "  ok, slack " + i.slack()));
            options.forEach((order, opts) -> {
                b.append("\n  Options for ").append(order).append(" (expected loss vs doing nothing):");
                opts.forEach(o -> b.append("\n    ").append(String.format("%-11s", o.kind())).append(" delivers ")
                        .append(WorkCalendar.fmt(o.newEstimate())).append(", cost ").append(Money.inr(o.cost()))
                        .append(", expected loss ").append(Money.inr(o.expectedLoss())).append(", net ")
                        .append(Money.inr(o.netBenefit())).append(o.kind().equals("DO_NOTHING") ? "" : "  - " + o.description()));
            });
            if (!suggestions.isEmpty()) {
                b.append("\n  Suggestions (a planner decides):");
                suggestions.forEach(s -> b.append("\n    [").append(s.id()).append("] ").append(s.describe().replace("\n", "\n      ")));
            }
            return b.toString();
        }
    }

    private final CoreContext ctx;
    private final Map<String, LocalDate> lastKnown = new HashMap<>();
    private long lastSeq = -1;

    public ReplanService(CoreContext ctx) {
        this.ctx = ctx;
    }

    public synchronized void baseline() {
        CentralView v = ctx.state().view();
        for (Erp.SalesOrder o : v.openOrders()) {
            lastKnown.put(o.id(), OrderEta.of(o, v, ctx.policy().get()).estimate());
        }
        lastSeq = v.planning().lastEventSeq();
    }

    /** Remember the estimate of orders created since the last re-plan, so the next re-plan can show what moved. */
    public synchronized void observeNewOrders() {
        CentralView v = ctx.state().view();
        for (Erp.SalesOrder o : v.openOrders()) {
            lastKnown.computeIfAbsent(o.id(), k -> OrderEta.of(o, v, ctx.policy().get()).estimate());
            if (lastKnown.get(o.id()) == null) {
                lastKnown.put(o.id(), OrderEta.of(o, v, ctx.policy().get()).estimate());
            }
        }
    }

    public synchronized int newEvents() {
        try {
            return ctx.planning().events(lastSeq).size();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public synchronized Assessment<ReplanReport> replan(String trigger) {
        ctx.state().refresh(Source.PLANNING);
        ctx.state().refresh(Source.ERP);
        CentralView v = ctx.state().view();
        Policy p = ctx.policy().get();
        List<String> events = new ArrayList<>();
        if (!v.freshness(Source.PLANNING).blocksCommitment()) {
            for (Planning.Event e : ctx.planning().events(lastSeq)) {
                events.add("#" + e.seq() + " " + e.type() + ": " + e.detail());
                lastSeq = Math.max(lastSeq, e.seq());
            }
        }
        List<OrderImpact> impacts = new ArrayList<>();
        Map<String, List<Option>> options = new LinkedHashMap<>();
        List<Suggestion> suggestions = new ArrayList<>();
        Map<String, OrderEta> etas = new HashMap<>();
        for (Erp.SalesOrder o : v.openOrders()) {
            etas.put(o.id(), OrderEta.of(o, v, p));
        }
        for (Erp.SalesOrder o : v.openOrders()) {
            OrderEta eta = etas.get(o.id());
            Crm.Account acct = v.account(o.accountId()).orElseThrow();
            boolean atRisk = eta.atRisk(p);
            impacts.add(new OrderImpact(o.id(), acct.name(), acct.tier(), o.value(), eta.committed(),
                    lastKnown.get(o.id()), eta.estimate(), eta.lateDays(), eta.slackWorkingDays(), atRisk, eta.workOrders()));
            if (atRisk && eta.estimate() != null) {
                List<Option> opts = optionsFor(o, eta, acct, v, p, etas);
                options.put(o.id(), opts);
                Optional<Option> recovery = opts.stream().filter(x -> !List.of("DO_NOTHING", "NOTIFY").contains(x.kind()))
                        .filter(x -> x.netBenefit().signum() > 0).max(Comparator.comparing(Option::netBenefit));
                recovery.ifPresent(best -> suggestions.add(toSuggestion(best, o, eta, acct, v, opts)));
                opts.stream().filter(x -> x.kind().equals("NOTIFY") && x.netBenefit().signum() > 0)
                        .findFirst().ifPresent(n -> suggestions.add(toSuggestion(n, o, eta, acct, v, opts)));

            }
        }
        impacts.sort(Comparator.comparing(OrderImpact::atRisk).reversed().thenComparing(OrderImpact::orderId));
        suggestions.sort(Comparator.comparing(Suggestion::netBenefit).reversed());
        ctx.suggestions().replace("ORDER:", suggestions);
        long atRiskCount = impacts.stream().filter(OrderImpact::atRisk).count();
        long moved = impacts.stream().filter(i -> !java.util.Objects.equals(i.previousEstimate(), i.currentEstimate())).count();
        String summary = "Re-plan (" + trigger + "): " + moved + " order estimate(s) moved, " + atRiskCount
                + " order(s) at risk, " + suggestions.size() + " suggestion(s).";
        impacts.forEach(i -> lastKnown.put(i.orderId(), i.currentEstimate()));

        Confidence.Builder c = Confidence.start();
        if (!v.freshness(Source.PLANNING).fresh()) {
            c.apply("planning data", v.freshness(Source.PLANNING).blocksCommitment() ? 0.4 : 0.8,
                    v.freshness(Source.PLANNING).describe());
        }
        c.apply("cost model", 0.85, "penalty terms come from the orders; goodwill per late day is a policy estimate ("
                + p.goodwillPctPerLateDay() + "% of order value by tier)");
        List<Fact> basis = new ArrayList<>();
        basis.add(v.fact(Source.PLANNING, "v" + v.version(Source.PLANNING), "work orders and line calendars as reported by planning"));
        impacts.stream().filter(OrderImpact::atRisk).forEach(i -> basis.add(v.fact(Source.ERP, i.orderId(),
                i.orderId() + " committed " + WorkCalendar.fmt(i.committed()) + ", value " + Money.inr(i.value())
                        + ", tier " + i.tier())));
        DecisionRecord rec = ctx.log().record("REPLAN", "system", null, null, null, summary, null, null,
                suggestions.stream().map(s -> s.id() + ": " + s.action()).toList(), v, c.build().score(), null, null,
                null, p.version());
        ReplanReport report = new ReplanReport(trigger, events, impacts, options, suggestions, summary);
        return new Assessment<>(trigger, summary, report, c.build(),
                CoreContext.fresh(v, Source.PLANNING, Source.ERP, Source.CRM), basis,
                List.of("Whether overtime crews are actually available (no HR roster is connected).",
                        "Customer willingness to accept a split delivery.",
                        "Knock-on effects beyond " + p.horizonDays() + " days."),
                List.of("Any further slip on the affected lines invalidates these options; they are re-built on the next event.",
                        "If goodwill cost per late day is set to zero, only the contractual penalty drives the ranking."),
                rec.id());
    }

    private List<Option> optionsFor(Erp.SalesOrder o, OrderEta eta, Crm.Account acct, CentralView v, Policy p,
                                    Map<String, OrderEta> etas) {
        List<Option> out = new ArrayList<>();
        LatenessCost dn = LatenessCost.of(o, acct.tier(), eta.lateDays(), o.value(), p);
        out.add(new Option(o.id(), "DO_NOTHING", "Keep the current plan", eta.estimate(), eta.lateDays(), BigDecimal.ZERO,
                "none", 1.0, dn.total(), dn.total(), BigDecimal.ZERO, "PLANNER", List.of(), List.of(), null));
        List<Planning.WorkOrder> wos = v.workOrdersFor(o.id()).stream().filter(w -> !CapacityModel.closed(w)).toList();
        if (!wos.isEmpty()) {
            Ctx c = new Ctx(o, eta, acct, v, p, dn, wos);
            overtime(c, false).ifPresent(out::add);
            alternate(c).ifPresent(out::add);
            overtime(c, true).ifPresent(out::add);
            resequence(c, etas).ifPresent(out::add);
            split(c).ifPresent(out::add);
        }
        notify(o, eta, acct, v, p, dn).ifPresent(out::add);
        out.sort(Comparator.comparing(Option::netBenefit).reversed());
        return out;
    }

    private record Ctx(Erp.SalesOrder o, OrderEta eta, Crm.Account acct, CentralView v, Policy p, LatenessCost dn,
                       List<Planning.WorkOrder> wos) {
        /** Only the work orders that finish too late are re-placed; work that is on time stays where it is. */
        List<Planning.WorkOrder> late() {
            LocalDate cut = cutoff();
            List<Planning.WorkOrder> l = wos.stream().filter(w -> w.plannedFinish() != null && w.plannedFinish().isAfter(cut)).toList();
            return l.isEmpty() ? wos : l;
        }

        Set<String> lateIds() {
            return late().stream().map(Planning.WorkOrder::id).collect(Collectors.toSet());
        }

        String sku() {
            return late().get(0).sku();
        }

        int remaining() {
            return late().stream().filter(w -> w.sku().equals(sku())).mapToInt(Planning.WorkOrder::remainingQty).sum();
        }

        CapacityModel capacity() {
            return CapacityModel.from(v.planning(), Set.of(), lateIds());
        }

        LocalDate start() {
            LocalDate s = v.today().plusDays(p.productionStartOffsetDays());
            while (!WorkCalendar.isWorkingDay(s)) {
                s = s.plusDays(1);
            }
            return s;
        }

        LocalDate end() {
            return v.today().plusDays(p.horizonDays());
        }

        LocalDate cutoff() {
            return WorkCalendar.latestFinishFor(eta.committed(), acct.transitDays(), p.qcWorkingDays());
        }

        List<Planning.Routing> preferred() {
            return v.routings(sku()).stream().filter(Planning.Routing::preferred).toList();
        }

        String primary() {
            return preferred().isEmpty() ? wos.get(0).lineId() : preferred().get(0).lineId();
        }

        LocalDate deliveryOf(Scheduler.Schedule s) {
            return s.complete() ? WorkCalendar.deliveryDate(WorkCalendar.addWorkingDays(s.finish(), p.qcWorkingDays()),
                    acct.transitDays()) : null;
        }

        MaterialModel material(Set<String> exclude) {
            return materialFor(late(), v, p, exclude);
        }
    }

    /** Released or in-progress work already has its material on the floor; only planned work draws on stock. */
    static MaterialModel materialFor(List<Planning.WorkOrder> wos, CentralView v, Policy p, Set<String> exclude) {
        boolean anyPlanned = wos.stream().anyMatch(w -> "PLANNED".equals(w.status()));
        return anyPlanned ? MaterialModel.from(v.erp(), v.planning(), p, exclude) : MaterialModel.unconstrained();
    }

    private Optional<Option> overtime(Ctx c, boolean withAlternate) {
        Planning.Line line = c.v().line(c.primary()).orElseThrow();
        List<LocalDate> otDays = new ArrayList<>();
        CapacityModel probe = c.capacity();
        for (LocalDate d = c.cutoff(); !d.isBefore(c.start()); d = d.minusDays(1)) {
            int allow = !WorkCalendar.isWorkingDay(d) ? line.maxSundayOvertimeMinutes()
                    : probe.available(c.primary(), d) > 0 ? line.maxWeekdayOvertimeMinutes() : 0;
            if (allow > 0) {
                otDays.add(d);
            }
        }
        if (otDays.isEmpty()) {
            return Optional.empty();
        }
        List<Planning.Routing> routings = withAlternate ? c.v().routings(c.sku()) : c.preferred();
        if (withAlternate && routings.size() == c.preferred().size()) {
            return Optional.empty();
        }
        // Size overtime to the shortfall: first see what normal capacity delivers by the cutoff, then grant only the
        // missing minutes, latest days first, so overtime is not spent on work that normal shifts could still do.
        Scheduler.Schedule normal = Scheduler.run(c.sku(), c.remaining(), c.start(), c.end(), routings, c.capacity(),
                c.material(Set.of(c.o().id())), false);
        int byCutoff = normal.placements().stream().filter(pl -> !pl.date().isAfter(c.cutoff()))
                .mapToInt(Scheduler.Placement::qty).sum();
        double mpu = c.preferred().isEmpty() ? 5.0 : c.preferred().get(0).minutesPerUnit();
        int needMinutes = (int) Math.ceil(Math.max(0, c.remaining() - byCutoff) * mpu);
        if (needMinutes == 0) {
            return Optional.empty();
        }
        Scheduler.Schedule best = null;
        for (int extra : new int[]{0, 60, 120, 100000}) {
            CapacityModel cap = c.capacity();
            int left = needMinutes + extra;
            for (LocalDate d : otDays) {
                int max = !WorkCalendar.isWorkingDay(d) ? line.maxSundayOvertimeMinutes() : line.maxWeekdayOvertimeMinutes();
                int grant = Math.min(max, left);
                if (grant > 0) {
                    cap.allowOvertime(c.primary(), d, grant);
                    left -= grant;
                }
            }
            best = Scheduler.run(c.sku(), c.remaining(), c.start(), c.end(), routings, cap,
                    c.material(Set.of(c.o().id())), true);
            if (best.complete() && !best.finish().isAfter(c.cutoff())) {
                break;
            }
        }
        if (best == null || best.overtimeMinutes() == 0) {
            return Optional.empty();
        }
        BigDecimal otCost = line.overtimeCostPerHour().multiply(BigDecimal.valueOf(best.overtimeMinutes()))
                .divide(BigDecimal.valueOf(60), 0, RoundingMode.HALF_UP);
        BigDecimal altCost = withAlternate ? alternateCost(c, best) : BigDecimal.ZERO;
        String otWhen = best.placements().stream().filter(pl -> pl.overtimeMinutes() > 0)
                .map(pl -> WorkCalendar.fmt(pl.date()) + " " + pl.overtimeMinutes() + " min")
                .collect(Collectors.joining(", "));
        String kind = withAlternate ? "OT+ALT" : "OVERTIME";
        String desc = "Approve overtime on " + c.primary() + " (" + otWhen + ")" + (withAlternate
                ? " and move part of the remaining " + c.remaining() + " units to the alternate line" : "")
                + " for " + c.o().id();
        return option(c, kind, desc, best, otCost.add(altCost), best.overtimeMinutes() + " overtime minutes at "
                + Money.inr(line.overtimeCostPerHour()) + "/h" + (withAlternate ? " plus " + Money.inr(altCost)
                + " for alternate-line changeover and slower cycle" : ""), withAlternate ? 0.72 : 0.85,
                List.of("Needs an overtime crew on " + otWhen + "."),
                List.of("If no crew is available on those days, this option does not recover the date."));
    }

    private Optional<Option> alternate(Ctx c) {
        List<Planning.Routing> all = c.v().routings(c.sku());
        if (all.size() == c.preferred().size()) {
            return Optional.empty();
        }
        CapacityModel cap = c.capacity();
        Scheduler.Schedule s = Scheduler.run(c.sku(), c.remaining(), c.start(), c.end(), all, cap,
                c.material(Set.of(c.o().id())), false);
        if (!s.usedAlternate()) {
            return Optional.empty();
        }
        BigDecimal cost = alternateCost(c, s);
        String alt = s.placements().stream().filter(Scheduler.Placement::alternate).map(pl -> pl.qty() + " on "
                + pl.lineId() + " " + WorkCalendar.fmt(pl.date())).collect(Collectors.joining(", "));
        return option(c, "ALTERNATE", "Move part of " + c.o().id() + " to the alternate line (" + alt + ")", s, cost,
                "changeover and the slower cycle on the alternate line", 0.8,
                List.of("Uses alternate-line capacity that other urgent work could need."),
                List.of("Assumes first-article approval on the alternate line on the first day."));
    }

    private BigDecimal alternateCost(Ctx c, Scheduler.Schedule s) {
        double prefMpu = c.preferred().isEmpty() ? 0 : c.preferred().get(0).minutesPerUnit();
        BigDecimal total = BigDecimal.ZERO;
        for (Planning.Routing r : c.v().routings(c.sku())) {
            if (r.preferred()) {
                continue;
            }
            int units = s.placements().stream().filter(pl -> pl.lineId().equals(r.lineId())).mapToInt(Scheduler.Placement::qty).sum();
            if (units == 0) {
                continue;
            }
            BigDecimal perMin = c.v().line(r.lineId()).map(Planning.Line::costPerMinute).orElse(BigDecimal.valueOf(30));
            double minutes = r.changeoverMinutes() + units * Math.max(0, r.minutesPerUnit() - prefMpu);
            total = total.add(perMin.multiply(BigDecimal.valueOf(minutes)));
        }
        return total.setScale(0, RoundingMode.HALF_UP);
    }

    private Optional<Option> resequence(Ctx c, Map<String, OrderEta> etas) {
        Option best = null;
        for (Erp.SalesOrder donor : c.v().openOrders()) {
            OrderEta de = etas.get(donor.id());
            if (donor.id().equals(c.o().id()) || de == null || de.estimate() == null || de.atRisk(c.p())
                    || de.slackWorkingDays() == null || de.slackWorkingDays() < 1) {
                continue;
            }
            List<Planning.WorkOrder> dw = c.v().workOrdersFor(donor.id()).stream()
                    .filter(w -> !CapacityModel.closed(w) && w.lineId().equals(c.primary())).toList();
            if (dw.isEmpty()) {
                continue;
            }
            Set<String> moving = new java.util.HashSet<>(c.lateIds());
            dw.forEach(w -> moving.add(w.id()));
            CapacityModel cap = CapacityModel.from(c.v().planning(), Set.of(), moving);
            Scheduler.Schedule mine = Scheduler.run(c.sku(), c.remaining(), c.start(), c.end(), c.preferred(), cap,
                    c.material(Set.of(c.o().id(), donor.id())), false);
            MaterialModel mat = materialFor(dw, c.v(), c.p(), Set.of(c.o().id(), donor.id()));
            String dSku = dw.get(0).sku();
            int dRemaining = dw.stream().mapToInt(Planning.WorkOrder::remainingQty).sum();
            List<Planning.Routing> dRoutes = c.v().routings(dSku).stream().filter(r -> r.lineId().equals(c.primary())).toList();
            Scheduler.Schedule theirs = Scheduler.run(dSku, dRemaining, c.start(), c.end(), dRoutes, cap, mat, false);
            if (!mine.complete() || !theirs.complete()) {
                continue;
            }
            int dTransit = c.v().transitDays(donor.accountId());
            LocalDate dNew = WorkCalendar.deliveryDate(WorkCalendar.addWorkingDays(theirs.finish(), c.p().qcWorkingDays()), dTransit);
            int dSlack = WorkCalendar.workingDaysBetween(dNew, de.committed());
            if (dSlack < Math.max(0, c.p().minSlackWorkingDays())) {
                continue;
            }
            List<Planning.Reschedule> changes = new ArrayList<>(reschedules(c.late(), mine, "resequence ahead of " + donor.id(),
                    "RESEQ", c.v()));
            changes.addAll(reschedules(dw, theirs, "resequenced behind " + c.o().id(), "RESEQ", c.v()));
            ActionPayload.Replan payload = new ActionPayload.Replan(c.o().id(), "RESEQUENCE", changes, List.of());
            String side = donor.id() + " (" + c.v().accountName(donor.accountId()) + ") moves from "
                    + WorkCalendar.fmt(de.estimate()) + " to " + WorkCalendar.fmt(dNew) + "; its slack falls from "
                    + de.slackWorkingDays() + " to " + dSlack + " working day(s)";
            Option opt = build(c, "RESEQUENCE", "Run " + c.o().id() + " ahead of " + donor.id() + " on " + c.primary(),
                    mine, BigDecimal.ZERO, "no direct cost; the risk moves to " + donor.id() + " (slack " + dSlack + ")", 0.9,
                    List.of(side), List.of("If " + donor.id() + " slips by more than " + dSlack
                            + " working day(s) afterwards, it becomes late instead."), payload);
            if (opt != null && (best == null || opt.netBenefit().compareTo(best.netBenefit()) > 0)) {
                best = opt;
            }
        }
        return Optional.ofNullable(best);
    }

    private Optional<Option> split(Ctx c) {
        LocalDate cutoff = c.cutoff();
        int reserved = c.v().reservationsFor(c.o().id()).stream().mapToInt(Erp.Reservation::qty).sum();
        int made = c.wos().stream().mapToInt(Planning.WorkOrder::completedQty).sum();
        int byCutoff = c.wos().stream().flatMap(w -> w.allocations().stream()).filter(a -> !a.date().isAfter(cutoff))
                .mapToInt(Planning.Allocation::qty).sum();
        int total = c.o().lines().stream().mapToInt(Erp.OrderLine::qty).sum();
        int partial = reserved + made + byCutoff;
        if (partial <= 0 || partial >= total) {
            return Optional.empty();
        }
        BigDecimal lateValue = c.o().value().multiply(BigDecimal.valueOf(total - partial))
                .divide(BigDecimal.valueOf(total), 0, RoundingMode.HALF_UP);
        LatenessCost lossNew = LatenessCost.of(c.o(), c.acct().tier(), c.eta().lateDays(), lateValue, c.p());
        BigDecimal cost = c.p().extraShipmentCost();
        double pOk = 0.7;
        BigDecimal expected = lossNew.total().multiply(BigDecimal.valueOf(pOk))
                .add(c.dn().total().multiply(BigDecimal.valueOf(1 - pOk))).add(cost).setScale(0, RoundingMode.HALF_UP);
        String firstName = c.v().primaryContact(c.acct().id()).map(x -> x.name().split(" ")[0]).orElse("Sir/Madam");
        String to = c.v().primaryContact(c.acct().id()).map(Crm.Contact::email).orElse("");
        Mail.Outbound mail = new Mail.Outbound("split:" + c.o().id() + ":" + c.eta().estimate(), to,
                "Your order " + c.o().id() + ": delivery in two parts?", "Dear " + firstName + ",\n\nWe can deliver "
                + partial + " of the " + total + " units on your order " + c.o().id() + " by "
                + WorkCalendar.fmt(c.eta().committed()) + " as promised, and the remaining " + (total - partial) + " by "
                + WorkCalendar.fmt(c.eta().estimate()) + ". Would a delivery in two parts work for you?\n\n"
                + "Regards,\nSales team, Precision Couplings", null, null);
        return Optional.of(new Option(c.o().id(), "SPLIT", "Offer " + c.acct().name() + " a split delivery: " + partial
                + " by " + WorkCalendar.fmt(c.eta().committed()) + ", " + (total - partial) + " by "
                + WorkCalendar.fmt(c.eta().estimate()), c.eta().estimate(), c.eta().lateDays(), cost,
                "freight for a second shipment", pOk, lossNew.total(), expected,
                c.dn().total().subtract(expected), "SALES_REP",
                List.of("Only the late " + (total - partial) + " units attract penalty and goodwill cost."),
                List.of("Needs the customer to accept two deliveries (assumed 70% likely)."),
                new ActionPayload.SendEmail(c.acct().id(), mail)));
    }

    private Optional<Option> notify(Erp.SalesOrder o, OrderEta eta, Crm.Account acct, CentralView v, Policy p,
                                    LatenessCost dn) {
        if (eta.lateDays() <= 0) {
            return Optional.empty();
        }
        Optional<Status.CustomerStatus> published = ctx.statusFeed().statuses(acct.id()).data().stream()
                .filter(s -> s.orderRef().equals(o.id())).findFirst();
        if (published.isPresent() && eta.estimate().equals(published.get().currentEstimate())) {
            return Optional.empty();
        }
        long seq = published.map(Status.CustomerStatus::sequence).orElse(0L) + 1;
        BigDecimal lossNew = dn.penalty().add(dn.goodwill().multiply(BigDecimal.valueOf(1 - p.proactiveNoticeGoodwillReduction())))
                .setScale(0, RoundingMode.HALF_UP);
        BigDecimal cost = p.staffHourCost().divide(BigDecimal.valueOf(4), 0, RoundingMode.HALF_UP);
        double pOk = 0.95;
        BigDecimal expected = lossNew.multiply(BigDecimal.valueOf(pOk)).add(dn.total().multiply(BigDecimal.valueOf(1 - pOk)))
                .add(cost).setScale(0, RoundingMode.HALF_UP);
        String to = v.primaryContact(acct.id()).map(Crm.Contact::email).orElse("");
        String firstName = v.primaryContact(acct.id()).map(x -> x.name().split(" ")[0]).orElse("Sir/Madam");
        Status.CustomerStatus st = new Status.CustomerStatus(acct.id(), o.id(), o.customerPo(), eta.stage(),
                eta.committed(), eta.estimate(), "Your order has been rescheduled. New delivery date: "
                + WorkCalendar.fmt(eta.estimate()) + ".", v.freshness(Source.PLANNING).asOf(), seq, null, null);
        Mail.Outbound mail = new Mail.Outbound("notify:" + o.id() + ":" + eta.estimate(), to, "Your order " + o.id()
                + ": new delivery date", "Dear " + firstName + ",\n\nYour order " + o.id()
                + (o.customerPo() == null ? "" : " (your PO " + o.customerPo() + ")") + " will now be delivered on "
                + WorkCalendar.fmt(eta.estimate()) + " instead of " + WorkCalendar.fmt(eta.committed())
                + ". We are sorry for the change and will tell you at once if we can bring it forward.\n\n"
                + "Regards,\nSales team, Precision Couplings", null, null);
        return Optional.of(new Option(o.id(), "NOTIFY", "Tell " + acct.name() + " now that " + o.id() + " will arrive "
                + WorkCalendar.fmt(eta.estimate()) + " instead of " + WorkCalendar.fmt(eta.committed()), eta.estimate(),
                eta.lateDays(), cost, "15 minutes of planner time", pOk, lossNew, expected,
                dn.total().subtract(expected), "PLANNER",
                List.of("Does not recover the date; cuts the goodwill cost by " + Math.round(p.proactiveNoticeGoodwillReduction() * 100)
                        + "% (policy assumption) by telling them before the date passes."),
                List.of("If a recovery option is approved and works, the notice is not needed."),
                new ActionPayload.PublishStatus(st, false, mail)));
    }

    private Optional<Option> option(Ctx c, String kind, String desc, Scheduler.Schedule s, BigDecimal cost,
                                    String costNote, double pOk, List<String> side, List<String> would) {
        ActionPayload.Replan payload = payloadFor(c, s, kind);
        return Optional.ofNullable(build(c, kind, desc, s, cost, costNote, pOk, side, would, payload));
    }

    private Option build(Ctx c, String kind, String desc, Scheduler.Schedule s, BigDecimal cost, String costNote,
                         double pOk, List<String> side, List<String> would, ActionPayload payload) {
        LocalDate est = c.deliveryOf(s);
        if (est == null || !est.isBefore(c.eta().estimate())) {
            return null;
        }
        int late = (int) Math.max(0, ChronoUnit.DAYS.between(c.eta().committed(), est));
        BigDecimal lossNew = LatenessCost.of(c.o(), c.acct().tier(), late, c.o().value(), c.p()).total();
        BigDecimal expected = lossNew.multiply(BigDecimal.valueOf(pOk)).add(c.dn().total().multiply(BigDecimal.valueOf(1 - pOk)))
                .add(cost).setScale(0, RoundingMode.HALF_UP);
        String outcome = late == 0 ? "recovers " + WorkCalendar.fmt(c.eta().committed())
                : "improves to " + WorkCalendar.fmt(est) + " (" + late + " day(s) late)";
        List<String> w = new ArrayList<>(would);
        BigDecimal gain = c.dn().total().subtract(lossNew).multiply(BigDecimal.valueOf(pOk)).setScale(0, RoundingMode.HALF_UP);
        w.add("Worth doing while it costs less than " + Money.inr(gain) + " (it costs " + Money.inr(cost) + ").");
        int days = c.eta().lateDays() - late;
        if (days > 0 && cost.signum() > 0) {
            LatenessCost newCost = LatenessCost.of(c.o(), c.acct().tier(), late, c.o().value(), c.p());
            double dPenalty = c.dn().penalty().subtract(newCost.penalty()).doubleValue();
            double g = (cost.doubleValue() / pOk - dPenalty) / (c.o().value().doubleValue() * days) * 100;
            double policyG = c.p().goodwillPctPerLateDay().getOrDefault(c.acct().tier(), 0.5);
            w.add(g <= 0 ? "Worth it even with zero goodwill cost: the contractual penalty alone covers it."
                    : String.format("Stops beating doing nothing if goodwill lost per late day for a tier %s customer is below "
                    + "%.2f%% of order value (policy assumes %.2f%%).", c.acct().tier(), g, policyG));
        }
        return new Option(c.o().id(), kind, desc + "; " + outcome, est, late, cost, costNote, pOk, lossNew, expected,
                c.dn().total().subtract(expected), "PLANNER", side, w, payload);
    }

    private ActionPayload.Replan payloadFor(Ctx c, Scheduler.Schedule s, String kind) {
        List<Planning.Reschedule> changes = reschedules(c.late(), s, kind.toLowerCase() + " recovery for " + c.o().id(),
                kind, c.v());
        List<Planning.NewWorkOrder> created = new ArrayList<>();
        Set<String> withWo = c.late().stream().map(Planning.WorkOrder::lineId).collect(Collectors.toSet());
        groupByLine(s).forEach((line, pls) -> {
            if (!withWo.contains(line)) {
                created.add(new Planning.NewWorkOrder("WO:" + c.o().id() + ":" + c.sku() + ":" + line + ":" + kind + ":v"
                        + c.v().version(Source.PLANNING), c.o().id(), c.sku(), line,
                        pls.stream().mapToInt(Scheduler.Placement::qty).sum(),
                        pls.stream().map(pl -> new Planning.Allocation(pl.date(), pl.qty())).toList(), overtime(line, pls, c.o().id())));
            }
        });
        return new ActionPayload.Replan(c.o().id(), kind, changes, created);
    }

    private List<Planning.Reschedule> reschedules(List<Planning.WorkOrder> wos, Scheduler.Schedule s, String reason,
                                                  String kind, CentralView v) {
        Map<String, List<Scheduler.Placement>> byLine = groupByLine(s);
        List<Planning.Reschedule> out = new ArrayList<>();
        Set<String> assigned = new java.util.HashSet<>();
        for (Planning.WorkOrder w : wos) {
            List<Scheduler.Placement> pls = assigned.contains(w.lineId()) ? List.of() : byLine.getOrDefault(w.lineId(), List.of());
            assigned.add(w.lineId());
            out.add(new Planning.Reschedule("RS:" + w.id() + ":" + kind + ":v" + v.version(Source.PLANNING), w.id(),
                    pls.stream().map(pl -> new Planning.Allocation(pl.date(), pl.qty())).toList(),
                    overtime(w.lineId(), pls, w.orderId()), reason));
        }
        return out;
    }

    private static List<Planning.CapacityDay> overtime(String line, List<Scheduler.Placement> pls, String orderId) {
        Map<LocalDate, Integer> ot = new TreeMap<>();
        pls.stream().filter(pl -> pl.overtimeMinutes() > 0).forEach(pl -> ot.merge(pl.date(), pl.overtimeMinutes(), Integer::sum));
        return ot.entrySet().stream().map(e -> new Planning.CapacityDay(line, e.getKey(), e.getValue(), "for " + orderId)).toList();
    }

    private static Map<String, List<Scheduler.Placement>> groupByLine(Scheduler.Schedule s) {
        return s.placements().stream().collect(Collectors.groupingBy(Scheduler.Placement::lineId, LinkedHashMap::new,
                Collectors.toList()));
    }

    private Suggestion toSuggestion(Option best, Erp.SalesOrder o, OrderEta eta, Crm.Account acct, CentralView v,
                                    List<Option> all) {
        Option dn = all.stream().filter(x -> x.kind().equals("DO_NOTHING")).findFirst().orElseThrow();
        String alternatives = all.stream().filter(x -> !x.kind().equals("DO_NOTHING") && x != best)
                .map(x -> x.kind() + " net " + Money.inr(x.netBenefit())).collect(Collectors.joining(", "));
        List<String> would = new ArrayList<>(best.wouldChange());
        would.addAll(best.sideEffects());
        if (!alternatives.isEmpty()) {
            would.add("Next best: " + alternatives + ".");
        }
        double conf = best.pSuccess() * (v.freshness(Source.PLANNING).fresh() ? 1.0 : 0.8);
        if (best.kind().equals("NOTIFY")) {
            would.add(0, "Not exclusive with a recovery option: if a recovery is approved and works, skip the notice.");
        }
        int totalQty = o.lines().stream().mapToInt(Erp.OrderLine::qty).sum();
        String outcome = best.kind().equals("SPLIT") ? "Most of " + o.id() + " for " + acct.name() + " on the promised "
                + WorkCalendar.fmt(eta.committed()) + " (" + Money.inr(o.value()) + ", " + totalQty + " units ordered)"
                : best.kind().equals("NOTIFY")
                ? acct.name() + "'s trust on " + o.id() + " (" + Money.inr(o.value()) + ", tier " + acct.tier()
                + "): they hear about the " + eta.lateDays() + "-day slip from us now, not on the delivery day"
                : best.newLateDays() == 0 ? "On-time delivery of " + o.id() + " to " + acct.name() + " ("
                + Money.inr(o.value()) + ", tier " + acct.tier() + "), committed " + WorkCalendar.fmt(eta.committed())
                : "Fewer late days on " + o.id() + " for " + acct.name() + " (" + eta.lateDays() + " -> "
                + best.newLateDays() + ")";
        return new Suggestion(ctx.suggestions().nextId(), "ORDER:" + o.id(), outcome,
                o.value(), best.description(), best.cost(), best.costNote(),
                "delivers " + WorkCalendar.fmt(eta.estimate()) + ", " + eta.lateDays() + " day(s) late",
                dn.expectedLoss(), best.expectedLoss(), best.netBenefit(), Math.round(conf * 100) / 100.0,
                best.requiredRole(), would, best.kind(), best.payload());
    }
}
