package com.s2p.core.engine;

import com.s2p.contracts.Erp;
import com.s2p.contracts.Planning;
import com.s2p.core.policy.Policy;
import com.s2p.core.state.CentralView;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Available- and capable-to-promise. Answers "when can this quantity reach this customer" from current stock,
 * current line capacity and current material, and "how much of it by the date they asked for".
 */
public final class PromiseEngine {

    public record Request(String sku, int qty, LocalDate requestedDate, String accountId, String forOrderId) {
    }

    /** Hypothetical changes used for "what would change the answer". */
    public record Adjustments(String lostLine, LocalDate lostDay, String delayedReceipt, int receiptDelayDays,
                              int extraStock) {
        public static final Adjustments NONE = new Adjustments(null, null, null, 0, 0);
    }

    public record Result(String sku, int qty, LocalDate requestedDate, int fromStock, int toMake,
                         List<Scheduler.Placement> placements, LocalDate productionFinish, LocalDate dispatchDate,
                         LocalDate earliestDelivery, LocalDate preferredLinesOnlyDelivery, boolean usedAlternate,
                         Integer slackWorkingDays, boolean feasible, int qtyByRequested, String bindingConstraint,
                         List<MaterialModel.Receipt> reliedOn, int transitDays, int changeoverMinutes,
                         LocalDate promisableDate, List<String> notes) {
        public boolean complete() {
            return earliestDelivery != null;
        }

        /**
         * The date this result supports: the requested date when feasible, otherwise the earliest date that keeps the
         * policy's minimum buffer (which is the earliest physical delivery when the buffer is zero).
         */
        public LocalDate supportedDate() {
            return feasible && requestedDate != null ? requestedDate : promisableDate;
        }

        public Map<String, List<Scheduler.Placement>> byLine() {
            return placements.stream().collect(Collectors.groupingBy(Scheduler.Placement::lineId, LinkedHashMap::new,
                    Collectors.toList()));
        }

        public String mainLine() {
            return byLine().entrySet().stream()
                    .max((a, b) -> Integer.compare(a.getValue().stream().mapToInt(Scheduler.Placement::qty).sum(),
                            b.getValue().stream().mapToInt(Scheduler.Placement::qty).sum()))
                    .map(Map.Entry::getKey).orElse(null);
        }
    }

    public Result evaluate(Request req, CentralView v, Policy p, Adjustments adj) {
        Erp.Product product = v.product(req.sku())
                .orElseThrow(() -> new IllegalArgumentException("Unknown product " + req.sku()));
        int transit = v.transitDays(req.accountId());
        LocalDate today = v.today();
        int ownReserved = req.forOrderId() == null ? 0 : v.reservationsFor(req.forOrderId()).stream()
                .filter(r -> r.sku().equals(req.sku())).mapToInt(Erp.Reservation::qty).sum();
        int fgAvailable = v.stock(req.sku()).map(Erp.StockItem::available).orElse(0) + ownReserved + adj.extraStock();
        int fromStock = Math.min(req.qty(), Math.max(0, fgAvailable));
        int toMake = req.qty() - fromStock;
        Set<String> exclude = req.forOrderId() == null ? Set.of() : Set.of(req.forOrderId());

        CapacityModel cap = CapacityModel.from(v.planning(), exclude);
        if (adj.lostLine() != null) {
            cap.zero(adj.lostLine(), adj.lostDay());
        }
        MaterialModel mat = MaterialModel.from(v.erp(), v.planning(), p, exclude);
        if (adj.delayedReceipt() != null) {
            mat = mat.withReceiptDelay(adj.delayedReceipt(), adj.receiptDelayDays());
        }
        List<Planning.Routing> routings = v.routings(req.sku());
        LocalDate start = today.plusDays(p.productionStartOffsetDays());
        while (!WorkCalendar.isWorkingDay(start)) {
            start = start.plusDays(1);
        }
        LocalDate end = today.plusDays(p.horizonDays());
        List<Planning.Routing> preferred = routings.stream().filter(Planning.Routing::preferred).toList();

        Scheduler.Schedule pref = toMake == 0 ? Scheduler.Schedule.empty()
                : Scheduler.run(req.sku(), toMake, start, end, preferred, cap.copy(), mat.copy(), false);
        Scheduler.Schedule alt = null;
        if (toMake > 0 && p.allowAlternateLines() && routings.size() > preferred.size()) {
            alt = Scheduler.run(req.sku(), toMake, start, end, routings, cap.copy(), mat.copy(), false);
        }
        Scheduler.Schedule chosen = pref;
        if (alt != null && alt.complete() && (!pref.complete() || alt.finish().isBefore(pref.finish()))) {
            chosen = alt;
        }

        LocalDate dispatch;
        LocalDate finish = null;
        if (toMake == 0) {
            dispatch = WorkCalendar.addWorkingDays(today, p.dispatchLeadWorkingDays());
        } else if (chosen.complete()) {
            finish = chosen.finish();
            dispatch = WorkCalendar.addWorkingDays(finish, p.qcWorkingDays());
        } else {
            dispatch = null;
        }
        LocalDate delivery = dispatch == null ? null : WorkCalendar.deliveryDate(dispatch, transit);
        LocalDate prefDelivery = toMake == 0 ? delivery : pref.complete()
                ? WorkCalendar.deliveryDate(WorkCalendar.addWorkingDays(pref.finish(), p.qcWorkingDays()), transit)
                : null;
        Integer slack = req.requestedDate() == null || delivery == null ? null
                : WorkCalendar.workingDaysBetween(delivery, req.requestedDate());
        boolean feasible = delivery != null && (slack == null || slack >= p.minSlackWorkingDays());

        int qtyBy = req.qty();
        if (req.requestedDate() != null) {
            LocalDate target = WorkCalendar.minusWorkingDays(req.requestedDate(), p.minSlackWorkingDays());
            LocalDate stockDelivery = WorkCalendar.deliveryDate(
                    WorkCalendar.addWorkingDays(today, p.dispatchLeadWorkingDays()), transit);
            qtyBy = stockDelivery.isAfter(target) ? 0 : fromStock;
            for (Scheduler.Placement pl : chosen.placements()) {
                LocalDate dl = WorkCalendar.deliveryDate(WorkCalendar.addWorkingDays(pl.date(), p.qcWorkingDays()), transit);
                if (!dl.isAfter(target)) {
                    qtyBy += pl.qty();
                }
            }
        }

        List<String> notes = new ArrayList<>();
        String binding;
        if (toMake == 0) {
            binding = "none: covered from unreserved stock";
        } else if (!chosen.complete()) {
            binding = "cannot be completed within the " + p.horizonDays() + "-day planning horizon";
        } else if (chosen.materialLimited()) {
            binding = "material: " + String.join(", ", mat.bom(req.sku()).keySet()) + " availability";
        } else {
            binding = "capacity on " + String.join(" + ", new LinkedHashMap<>(chosen.placements().stream()
                    .collect(Collectors.groupingBy(Scheduler.Placement::lineId, LinkedHashMap::new, Collectors.counting())))
                    .keySet());
        }
        if (chosen == alt && prefDelivery != null && !prefDelivery.equals(delivery)) {
            notes.add("Using the alternate line brings delivery from " + WorkCalendar.fmt(prefDelivery) + " to "
                    + WorkCalendar.fmt(delivery) + " at a " + chosen.changeoverMinutes() + "-minute changeover.");
        } else if (chosen == alt) {
            notes.add("Alternate line used.");
        }
        LocalDate promisable = delivery == null ? null : WorkCalendar.addWorkingDays(delivery, p.minSlackWorkingDays());
        if (p.minSlackWorkingDays() > 0 && delivery != null) {
            notes.add("Policy keeps " + p.minSlackWorkingDays() + " working day(s) of buffer: earliest physical delivery "
                    + WorkCalendar.fmt(delivery) + ", earliest promisable date " + WorkCalendar.fmt(promisable) + ".");
        }
        return new Result(req.sku(), req.qty(), req.requestedDate(), fromStock, toMake, chosen.placements(), finish,
                dispatch, delivery, prefDelivery, chosen.usedAlternate(), slack, feasible, qtyBy, binding,
                chosen.reliedOn(), transit, chosen.changeoverMinutes(), promisable, notes);
    }
}
