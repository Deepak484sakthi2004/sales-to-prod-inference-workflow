package com.s2p.core.engine;

import com.s2p.contracts.Erp;
import com.s2p.core.knowledge.Confidence;
import com.s2p.core.knowledge.Fact;
import com.s2p.core.knowledge.Freshness;
import com.s2p.core.policy.Policy;
import com.s2p.core.state.CentralView;
import com.s2p.core.state.Source;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Turns a promise calculation into a self-aware answer: how confident, on what basis, what was not checked,
 * and which concrete changes would move the date (found by re-running the engine with one thing changed).
 */
public final class PromiseAssessor {
    public record Assessed(PromiseEngine.Result result, Confidence confidence, List<Fact> basis,
                           List<String> notChecked, List<String> wouldChange) {
    }

    private final PromiseEngine engine = new PromiseEngine();

    public PromiseEngine engine() {
        return engine;
    }

    public Assessed assess(PromiseEngine.Request req, CentralView v, Policy p, List<Confidence.Factor> inputFactors) {
        PromiseEngine.Result r = engine.evaluate(req, v, p, PromiseEngine.Adjustments.NONE);
        Map<String, LineReliability.LineStats> stats = LineReliability.compute(v.planning().history());
        Confidence.Builder c = Confidence.start().applyAll(inputFactors);

        for (Source s : List.of(Source.ERP, Source.PLANNING)) {
            Freshness f = v.freshness(s);
            if (f.blocksCommitment()) {
                c.apply(s + " data", 0.5, f.describe() + "; figures are indicative only");
            } else if (!f.fresh()) {
                c.apply(s + " data", 0.85, f.describe());
            }
        }
        if (!r.complete()) {
            c.apply("no supportable date", 0.3, r.bindingConstraint());
        } else {
            int slack = r.feasible() && r.slackWorkingDays() != null ? r.slackWorkingDays() : p.minSlackWorkingDays();
            double sf = slack >= 3 ? 1.0 : slack == 2 ? 0.95 : slack == 1 ? 0.88 : 0.75;
            if (sf < 1.0) {
                c.apply("slack", sf, slack + " working day(s) between the plan and the date presented");
            }
            String main = r.mainLine();
            if (main != null && stats.containsKey(main)) {
                LineReliability.LineStats st = stats.get(main);
                double lf = slack >= Math.max(1, st.p80SlipDays()) ? 0.97 : 0.7 + 0.3 * st.onTimeRate();
                c.apply("line history", lf, st.describe());
            }
            for (MaterialModel.Receipt rc : r.reliedOn()) {
                c.apply("inbound material", 0.85 + 0.15 * rc.onTimeRate(), "relies on " + rc.ref() + " (" + rc.qty()
                        + " x " + rc.rmSku() + ", due " + WorkCalendar.fmt(rc.expected()) + ", " + rc.supplier()
                        + " on-time " + Math.round(rc.onTimeRate() * 100) + "%)");
            }
            if (r.usedAlternate()) {
                c.apply("alternate line", 0.92, "part of the quantity runs on a non-preferred line (first-article risk)");
            }
        }
        return new Assessed(r, c.build(), basis(req, r, v, p, stats), notChecked(v), wouldChange(req, r, v, p));
    }

    private List<Fact> basis(PromiseEngine.Request req, PromiseEngine.Result r, CentralView v, Policy p,
                             Map<String, LineReliability.LineStats> stats) {
        List<Fact> out = new ArrayList<>();
        v.stock(req.sku()).ifPresent(s -> {
            String holders = v.erp().reservations().stream().filter(x -> x.sku().equals(req.sku()))
                    .map(x -> x.qty() + " for " + x.orderId()).collect(Collectors.joining(", "));
            out.add(v.fact(Source.ERP, "stock/" + req.sku(), req.sku() + ": on hand " + s.onHand() + ", reserved "
                    + s.reserved() + (holders.isEmpty() ? "" : " (" + holders + ")") + ", available " + s.available()
                    + "; " + r.fromStock() + " taken from stock"));
        });
        r.byLine().forEach((line, pls) -> {
            String days = pls.stream().map(pl -> pl.qty() + " on " + WorkCalendar.fmt(pl.date()))
                    .collect(Collectors.joining(", "));
            double mpu = CapacityModelHelper.mpu(v, req.sku(), line);
            out.add(v.fact(Source.PLANNING, "capacity/" + line, line + " free capacity used: " + days + " (" + mpu
                    + " min/unit" + (pls.get(0).alternate() ? ", alternate line" : "") + ")"));
        });
        if (r.toMake() > 0) {
            MaterialModel mat = MaterialModel.from(v.erp(), v.planning(), p,
                    req.forOrderId() == null ? java.util.Set.of() : java.util.Set.of(req.forOrderId()));
            mat.bom(req.sku()).keySet().forEach(rm -> {
                String receipts = mat.receipts(rm).stream().map(x -> x.ref() + " " + x.qty() + " due "
                        + WorkCalendar.fmt(x.expected()) + " (usable " + WorkCalendar.fmt(x.usableFrom()) + ")")
                        .collect(Collectors.joining("; "));
                out.add(v.fact(Source.ERP, "stock/" + rm, rm + ": " + mat.baseAvailable(rm) + " available to new work"
                        + (receipts.isEmpty() ? "" : "; inbound " + receipts)));
            });
        }
        v.account(req.accountId()).ifPresent(a -> out.add(v.fact(Source.CRM, "account/" + a.id(), a.name() + " ("
                + a.region() + "): transit " + a.transitDays() + " day(s)")));
        out.add(new Fact("POLICY", p.version(), "QC " + p.qcWorkingDays() + " working day; minimum slack "
                + p.minSlackWorkingDays() + " working day(s); today's line schedule is frozen; Sunday closed", 0, 0));
        String main = r.mainLine();
        if (main != null && stats.containsKey(main)) {
            out.add(v.fact(Source.PLANNING, "history/" + main, stats.get(main).describe()));
        }
        return out;
    }

    private List<String> notChecked(CentralView v) {
        List<String> out = new ArrayList<>();
        out.add("Customer credit limit and payment terms (no finance system is connected).");
        out.add("Freight booking: transit is taken from the account's region, not from a carrier quote.");
        out.add("Operator availability beyond the planning calendar, and first-pass yield (assumed 100%).");
        if (!v.freshness(Source.PLANNING).fresh()) {
            out.add("Shop-floor events after " + v.freshness(Source.PLANNING).asOf().toLocalTime().withNano(0)
                    + " (planning data is not fresh).");
        }
        return out;
    }

    private List<String> wouldChange(PromiseEngine.Request req, PromiseEngine.Result r, CentralView v, Policy p) {
        List<String> out = new ArrayList<>();
        if (!r.complete()) {
            return out;
        }
        String main = r.mainLine();
        if (main != null && r.productionFinish() != null) {
            LocalDate probe = r.byLine().get(main).stream().map(Scheduler.Placement::date)
                    .filter(d -> !d.isAfter(r.productionFinish())).reduce((a, b) -> b).orElse(r.productionFinish());
            PromiseEngine.Result lost = engine.evaluate(req, v, p,
                    new PromiseEngine.Adjustments(main, probe, null, 0, 0));
            out.add(lost.earliestDelivery() != null && lost.earliestDelivery().isAfter(r.earliestDelivery())
                    ? "If " + main + " loses one more day (e.g. " + WorkCalendar.fmt(probe) + "), earliest delivery moves to "
                    + WorkCalendar.fmt(lost.earliestDelivery()) + "."
                    : "Losing one day on " + main + " (" + WorkCalendar.fmt(probe) + ") would not move the date.");
        }
        for (MaterialModel.Receipt rc : r.reliedOn()) {
            PromiseEngine.Result late = engine.evaluate(req, v, p,
                    new PromiseEngine.Adjustments(null, null, rc.ref(), 3, 0));
            out.add(late.earliestDelivery() == null || late.earliestDelivery().isAfter(r.earliestDelivery())
                    ? "If " + rc.ref() + " arrives 3 days late, earliest delivery moves to "
                    + (late.earliestDelivery() == null ? "beyond the horizon" : WorkCalendar.fmt(late.earliestDelivery()))
                    + (req.requestedDate() == null ? "" : " and only " + late.qtyByRequested() + " units make "
                    + WorkCalendar.fmt(req.requestedDate())) + "."
                    : "A 3-day slip on " + rc.ref() + " would not move the date.");
        }
        if (r.toMake() > 0) {
            int reservedByOthers = v.erp().reservations().stream()
                    .filter(x -> x.sku().equals(req.sku()) && !x.orderId().equals(req.forOrderId()))
                    .mapToInt(Erp.Reservation::qty).sum();
            if (reservedByOthers > 0) {
                PromiseEngine.Result freed = engine.evaluate(req, v, p,
                        new PromiseEngine.Adjustments(null, null, null, 0, reservedByOthers));
                if (freed.earliestDelivery() != null && freed.earliestDelivery().isBefore(r.earliestDelivery())) {
                    out.add("Releasing the " + reservedByOthers + " units of " + req.sku()
                            + " reserved for other orders would bring delivery to "
                            + WorkCalendar.fmt(freed.earliestDelivery()) + " (and put those orders at risk).");
                }
            }
        }
        if (req.requestedDate() != null && !r.feasible()) {
            out.add(r.qtyByRequested() + " of " + req.qty() + " units can be delivered by "
                    + WorkCalendar.fmt(req.requestedDate()) + "; the rest by " + WorkCalendar.fmt(r.supportedDate()) + ".");
        }
        if (r.usedAlternate() && r.preferredLinesOnlyDelivery() != null) {
            out.add("If the alternate line is unavailable, earliest delivery is "
                    + WorkCalendar.fmt(r.preferredLinesOnlyDelivery()) + ".");
        }
        if (main != null) {
            out.add("Any new order booked on " + main + " before this one is confirmed takes the same capacity: the "
                    + "date holds only while the capacity is free.");
        } else {
            out.add("Covered from stock: another order reserving this stock first would change the answer.");
        }
        return out;
    }

    /** Small helper so the basis can quote minutes per unit without exposing the snapshot structure. */
    static final class CapacityModelHelper {
        static double mpu(CentralView v, String sku, String line) {
            return CapacityModel.minutesPerUnit(v.planning(), sku, line);
        }
    }
}
