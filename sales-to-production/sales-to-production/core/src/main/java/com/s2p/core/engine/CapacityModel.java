package com.s2p.core.engine;

import com.s2p.contracts.Planning;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Line minutes by day: what the calendar offers, what work orders already hold, and any overtime a planner might
 * approve. Built fresh from a planning snapshot for every question, optionally ignoring one or more orders so their
 * own work can be re-placed.
 */
public final class CapacityModel {
    private final Map<String, Map<LocalDate, Integer>> available = new HashMap<>();
    private final Map<String, Map<LocalDate, Integer>> booked = new HashMap<>();
    private final Map<String, Map<LocalDate, Integer>> overtimeAllowed = new HashMap<>();
    private final Map<String, Map<LocalDate, Integer>> overtimeUsed = new HashMap<>();
    private final Map<String, Map<LocalDate, List<String>>> bookedBy = new HashMap<>();

    public static CapacityModel from(Planning.Snapshot p, Set<String> excludeOrderIds) {
        return from(p, excludeOrderIds, Set.of());
    }

    /** As above, additionally ignoring individual work orders (the ones being re-placed). */
    public static CapacityModel from(Planning.Snapshot p, Set<String> excludeOrderIds, Set<String> excludeWoIds) {
        CapacityModel m = new CapacityModel();
        for (Planning.CapacityDay c : p.capacity()) {
            m.available.computeIfAbsent(c.lineId(), k -> new HashMap<>()).put(c.date(), c.availableMinutes());
        }
        for (Planning.WorkOrder wo : p.workOrders()) {
            if (closed(wo) || excludeOrderIds.contains(wo.orderId()) || excludeWoIds.contains(wo.id())) {
                continue;
            }
            double mpu = minutesPerUnit(p, wo.sku(), wo.lineId());
            for (Planning.Allocation a : wo.allocations()) {
                int minutes = (int) Math.ceil(a.qty() * mpu);
                m.book(wo.lineId(), a.date(), minutes);
                m.bookedBy.computeIfAbsent(wo.lineId(), k -> new HashMap<>())
                        .computeIfAbsent(a.date(), k -> new ArrayList<>())
                        .add(wo.id() + " (" + wo.orderId() + ") " + a.qty() + " x " + wo.sku() + " @ " + mpu
                                + " min = " + minutes + " min");
            }
        }
        return m;
    }

    public static boolean closed(Planning.WorkOrder wo) {
        return wo.status().equals("DONE") || wo.status().equals("CANCELLED");
    }

    public static double minutesPerUnit(Planning.Snapshot p, String sku, String lineId) {
        return p.routings().stream().filter(r -> r.sku().equals(sku) && r.lineId().equals(lineId)).findFirst()
                .map(Planning.Routing::minutesPerUnit).orElse(10.0);
    }

    public int available(String line, LocalDate d) {
        return available.getOrDefault(line, Map.of()).getOrDefault(d, 0);
    }

    public int booked(String line, LocalDate d) {
        return booked.getOrDefault(line, Map.of()).getOrDefault(d, 0);
    }

    public int free(String line, LocalDate d) {
        return Math.max(0, available(line, d) - booked(line, d));
    }

    public int overtimeFree(String line, LocalDate d) {
        return overtimeAllowed.getOrDefault(line, Map.of()).getOrDefault(d, 0)
                - overtimeUsed.getOrDefault(line, Map.of()).getOrDefault(d, 0);
    }

    public void book(String line, LocalDate d, int minutes) {
        booked.computeIfAbsent(line, k -> new HashMap<>()).merge(d, minutes, Integer::sum);
    }

    public void useOvertime(String line, LocalDate d, int minutes) {
        overtimeUsed.computeIfAbsent(line, k -> new HashMap<>()).merge(d, minutes, Integer::sum);
    }

    public void allowOvertime(String line, LocalDate d, int minutes) {
        overtimeAllowed.computeIfAbsent(line, k -> new HashMap<>()).merge(d, minutes, Integer::sum);
    }

    /** Used by sensitivity checks: what if this line loses this day? */
    public void zero(String line, LocalDate d) {
        available.computeIfAbsent(line, k -> new HashMap<>()).put(d, 0);
    }

    public List<String> explain(String line, LocalDate d) {
        return bookedBy.getOrDefault(line, Map.of()).getOrDefault(d, List.of());
    }

    public CapacityModel copy() {
        CapacityModel c = new CapacityModel();
        deepCopy(available, c.available);
        deepCopy(booked, c.booked);
        deepCopy(overtimeAllowed, c.overtimeAllowed);
        deepCopy(overtimeUsed, c.overtimeUsed);
        bookedBy.forEach((k, v) -> {
            Map<LocalDate, List<String>> m = new HashMap<>();
            v.forEach((d, l) -> m.put(d, new ArrayList<>(l)));
            c.bookedBy.put(k, m);
        });
        return c;
    }

    private static void deepCopy(Map<String, Map<LocalDate, Integer>> from, Map<String, Map<LocalDate, Integer>> to) {
        from.forEach((k, v) -> to.put(k, new HashMap<>(v)));
    }
}
