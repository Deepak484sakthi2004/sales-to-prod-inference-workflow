package com.s2p.core.engine;

import com.s2p.contracts.Erp;
import com.s2p.contracts.Planning;
import com.s2p.core.policy.Policy;
import com.s2p.core.state.CentralView;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** Where an existing order stands: its current estimate from live work orders and reservations. */
public record OrderEta(String orderId, String accountId, LocalDate committed, LocalDate estimate,
                       LocalDate productionFinish, Integer slackWorkingDays, int lateDays, boolean planned,
                       String stage, List<String> basis, List<String> workOrders) {

    public boolean atRisk(Policy p) {
        return estimate == null || lateDays > 0 || (slackWorkingDays != null && slackWorkingDays < p.minSlackWorkingDays());
    }

    public static OrderEta of(Erp.SalesOrder o, CentralView v, Policy p) {
        int transit = v.transitDays(o.accountId());
        LocalDate committed = o.lines().stream().map(Erp.OrderLine::committedDate).max(LocalDate::compareTo).orElse(null);
        List<String> basis = new ArrayList<>();
        List<String> wos = new ArrayList<>();
        if (o.status().equals("SHIPPED")) {
            return new OrderEta(o.id(), o.accountId(), committed, committed, null, 0, 0, true, "SHIPPED", basis, wos);
        }
        LocalDate latestDispatch = null;
        LocalDate latestFinish = null;
        boolean planned = true;
        boolean inProgress = false;
        for (Erp.OrderLine line : o.lines()) {
            int reserved = v.reservationsFor(o.id()).stream().filter(r -> r.sku().equals(line.sku()))
                    .mapToInt(Erp.Reservation::qty).sum();
            List<Planning.WorkOrder> lineWos = v.workOrdersFor(o.id()).stream()
                    .filter(w -> w.sku().equals(line.sku()) && !w.status().equals("CANCELLED")).toList();
            int covered = reserved + lineWos.stream().mapToInt(Planning.WorkOrder::qty).sum();
            if (reserved > 0) {
                basis.add(reserved + " x " + line.sku() + " reserved from stock");
            }
            if (covered < line.qty()) {
                planned = false;
                basis.add((line.qty() - covered) + " x " + line.sku() + " not yet planned");
                continue;
            }
            LocalDate dispatch = WorkCalendar.addWorkingDays(v.today(), p.dispatchLeadWorkingDays());
            for (Planning.WorkOrder w : lineWos) {
                wos.add(w.id());
                if (w.status().equals("IN_PROGRESS") || w.allocations().stream().anyMatch(a -> !a.date().isAfter(v.today()))) {
                    inProgress = true;
                }
                if (CapacityModel.closed(w) || w.plannedFinish() == null) {
                    continue;
                }
                basis.add(w.id() + " on " + w.lineId() + ": " + w.remainingQty() + " to make, planned finish "
                        + WorkCalendar.fmt(w.plannedFinish()) + (w.originalPlannedFinish() != null
                        && !w.originalPlannedFinish().equals(w.plannedFinish())
                        ? " (originally " + WorkCalendar.fmt(w.originalPlannedFinish()) + ")" : ""));
                LocalDate d = WorkCalendar.addWorkingDays(w.plannedFinish(), p.qcWorkingDays());
                if (d.isAfter(dispatch)) {
                    dispatch = d;
                }
                if (latestFinish == null || w.plannedFinish().isAfter(latestFinish)) {
                    latestFinish = w.plannedFinish();
                }
            }
            if (latestDispatch == null || dispatch.isAfter(latestDispatch)) {
                latestDispatch = dispatch;
            }
        }
        if (!planned) {
            return new OrderEta(o.id(), o.accountId(), committed, null, null, null, 0, false, "ORDER_RECEIVED", basis, wos);
        }
        LocalDate estimate = WorkCalendar.deliveryDate(latestDispatch, transit);
        basis.add("QC " + p.qcWorkingDays() + " working day, transit " + transit + " day(s) to "
                + v.account(o.accountId()).map(a -> a.region()).orElse("customer"));
        int late = committed == null ? 0 : (int) Math.max(0, ChronoUnit.DAYS.between(committed, estimate));
        Integer slack = committed == null ? null : WorkCalendar.workingDaysBetween(estimate, committed);
        String stage = latestFinish == null ? "READY_TO_DISPATCH" : inProgress ? "IN_PRODUCTION" : "SCHEDULED";
        return new OrderEta(o.id(), o.accountId(), committed, estimate, latestFinish, slack, late, true, stage, basis, wos);
    }
}
