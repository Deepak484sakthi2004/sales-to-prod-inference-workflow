package com.s2p.sources;

import com.s2p.contracts.Command;
import com.s2p.contracts.Planning;
import com.s2p.contracts.RelativeJson;
import com.s2p.contracts.SimClock;
import com.s2p.contracts.SourceResponse;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Production planning stand-in: line calendars, routings and work orders. It refuses any work order that would
 * overbook a line, and it models the shop-floor feed lag that makes planning data go stale.
 */
public final class PlanningStore {
    public record Seed(List<Planning.Line> lines, List<Planning.CapacityDay> capacityOverrides,
                       List<Planning.Routing> routings, List<Planning.WorkOrder> workOrders,
                       List<Planning.History> history, List<Planning.Event> events) {
    }

    private static final int HORIZON_DAYS = 45;

    private final SimClock clock;
    private final LocalDate anchor;
    private final AtomicLong version = new AtomicLong();
    private final Map<String, Planning.Line> lines = new LinkedHashMap<>();
    private final Map<String, Planning.CapacityDay> overrides = new HashMap<>();
    private final List<Planning.Routing> routings = new ArrayList<>();
    private final Map<String, Planning.WorkOrder> workOrders = new LinkedHashMap<>();
    private final List<Planning.History> history = new ArrayList<>();
    private final List<Planning.Event> events = new ArrayList<>();
    private final Map<String, String> idempotency = new HashMap<>();
    private int feedLagMinutes;
    private int nextWo;

    public PlanningStore(SimClock clock, LocalDate anchor) {
        this.clock = clock;
        this.anchor = anchor;
        reset();
    }

    public synchronized void reset() {
        Seed seed = new RelativeJson(anchor).load("seed/planning.json", Seed.class);
        lines.clear();
        overrides.clear();
        routings.clear();
        workOrders.clear();
        history.clear();
        events.clear();
        idempotency.clear();
        feedLagMinutes = 0;
        version.set(450);
        seed.lines().forEach(l -> lines.put(l.id(), l));
        seed.capacityOverrides().forEach(c -> overrides.put(key(c.lineId(), c.date()), c));
        routings.addAll(seed.routings());
        for (Planning.WorkOrder w : seed.workOrders()) {
            workOrders.put(w.id(), new Planning.WorkOrder(w.id(), w.orderId(), w.sku(), w.lineId(), w.qty(),
                    w.completedQty(), w.allocations(), w.status(),
                    w.originalPlannedFinish() == null ? w.plannedFinish() : w.originalPlannedFinish(),
                    w.overtimeMinutes(), w.idempotencyKey(), version.get(),
                    w.updatedAt() == null ? clock.now() : w.updatedAt()));
        }
        history.addAll(seed.history());
        events.addAll(seed.events());
        nextWo = Ids.next(workOrders.keySet(), "WO-", 5500);
    }

    public synchronized SourceResponse<Planning.Snapshot> snapshot() {
        LocalDate today = clock.today();
        List<Planning.CapacityDay> cap = new ArrayList<>();
        for (Planning.Line l : lines.values()) {
            for (LocalDate d = today.minusDays(7); !d.isAfter(today.plusDays(HORIZON_DAYS)); d = d.plusDays(1)) {
                cap.add(capacity(l.id(), d));
            }
        }
        return new SourceResponse<>("planning", version.get(), clock.now().minusMinutes(feedLagMinutes),
                new Planning.Snapshot(List.copyOf(lines.values()), cap, List.copyOf(routings),
                        List.copyOf(workOrders.values()), List.copyOf(history), lastSeq()));
    }

    public synchronized List<Planning.Event> events(long afterSeq) {
        return events.stream().filter(e -> e.seq() > afterSeq).toList();
    }

    public synchronized Command<Planning.WorkOrder> createWorkOrder(Planning.NewWorkOrder w) {
        if (w.idempotencyKey() == null || w.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("Work orders need an idempotency key");
        }
        String existing = idempotency.get(w.idempotencyKey());
        if (existing != null) {
            return new Command<>(workOrders.get(existing), true);
        }
        double mpu = minutesPerUnit(w.sku(), w.lineId());
        int total = w.allocations().stream().mapToInt(Planning.Allocation::qty).sum();
        if (total != w.qty()) {
            throw new IllegalArgumentException("Allocations sum to " + total + " but the work order is for " + w.qty());
        }
        applyAddedCapacity(w.addedCapacity());
        checkCapacity(w.lineId(), mpu, w.allocations(), null);
        String id = "WO-" + nextWo++;
        Planning.WorkOrder wo = new Planning.WorkOrder(id, w.orderId(), w.sku(), w.lineId(), w.qty(), 0,
                merge(w.allocations()), "PLANNED", null, addedMinutes(w.addedCapacity()), w.idempotencyKey(),
                version.incrementAndGet(), clock.now());
        wo = withOriginalFinish(wo, wo.plannedFinish());
        workOrders.put(id, wo);
        idempotency.put(w.idempotencyKey(), id);
        event("WO_CREATED", w.lineId(), id, "Created for " + w.orderId() + ": " + w.qty() + " x " + w.sku()
                + ", finish " + wo.plannedFinish());
        return new Command<>(wo, false);
    }

    /**
     * Moves several work orders at once. Each one may shrink (quantity moved to a new work order elsewhere) but never
     * grow. The combined result is checked against the line calendars, including any overtime the batch adds, before
     * anything is written.
     */
    public synchronized List<Command<Planning.WorkOrder>> reschedule(List<Planning.Reschedule> batch) {
        List<Command<Planning.WorkOrder>> out = new ArrayList<>();
        List<Planning.Reschedule> todo = new ArrayList<>();
        for (Planning.Reschedule r : batch) {
            String existing = idempotency.get(r.idempotencyKey());
            if (existing != null) {
                out.add(new Command<>(workOrders.get(existing), true));
            } else {
                todo.add(r);
            }
        }
        if (todo.isEmpty()) {
            return out;
        }
        Map<String, Integer> extra = new HashMap<>();
        Map<String, Integer> demand = new HashMap<>();
        java.util.Set<String> moving = new java.util.HashSet<>();
        for (Planning.Reschedule r : todo) {
            Planning.WorkOrder wo = workOrders.get(r.workOrderId());
            if (wo == null) {
                throw new IllegalArgumentException("Unknown work order " + r.workOrderId());
            }
            if (closed(wo)) {
                throw new IllegalStateException("Work order " + wo.id() + " is " + wo.status());
            }
            int total = r.allocations().stream().mapToInt(Planning.Allocation::qty).sum();
            if (total > wo.remainingQty()) {
                throw new IllegalArgumentException("Allocations for " + wo.id() + " sum to " + total
                        + ", remaining is only " + wo.remainingQty());
            }
            moving.add(wo.id());
            if (r.addedCapacity() != null) {
                r.addedCapacity().forEach(c -> extra.merge(key(c.lineId(), c.date()), c.availableMinutes(), Integer::sum));
            }
            double mpu = minutesPerUnit(wo.sku(), wo.lineId());
            for (Planning.Allocation a : r.allocations()) {
                demand.merge(key(wo.lineId(), a.date()), (int) Math.ceil(a.qty() * mpu), Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> e : demand.entrySet()) {
            String[] k = e.getKey().split("\\|");
            LocalDate d = LocalDate.parse(k[1]);
            int free = capacity(k[0], d).availableMinutes() + extra.getOrDefault(e.getKey(), 0) - booked(k[0], d, moving);
            if (e.getValue() > free) {
                throw new IllegalStateException("Capacity exceeded on " + k[0] + " " + d + ": free " + free
                        + " min, the change needs " + e.getValue() + " min. Nothing was changed.");
            }
        }
        for (Planning.Reschedule r : todo) {
            applyAddedCapacity(r.addedCapacity());
            Planning.WorkOrder wo = workOrders.get(r.workOrderId());
            int total = r.allocations().stream().mapToInt(Planning.Allocation::qty).sum();
            String status = total > 0 ? wo.status() : (wo.completedQty() > 0 ? "DONE" : "CANCELLED");
            Planning.WorkOrder updated = new Planning.WorkOrder(wo.id(), wo.orderId(), wo.sku(), wo.lineId(),
                    wo.completedQty() + total, wo.completedQty(), merge(r.allocations()), status,
                    wo.originalPlannedFinish(), wo.overtimeMinutes() + addedMinutes(r.addedCapacity()),
                    wo.idempotencyKey(), version.incrementAndGet(), clock.now());
            workOrders.put(wo.id(), updated);
            idempotency.put(r.idempotencyKey(), wo.id());
            event("WO_RESCHEDULED", wo.lineId(), wo.id(), r.reason() + "; " + (total == 0
                    ? "remaining quantity moved elsewhere" : "finish now " + updated.plannedFinish()));
            out.add(new Command<>(updated, false));
        }
        return out;
    }

    /**
     * A line loses capacity: the next N working days from 'from' go to zero and every open work order on the line is
     * pushed back in its existing sequence, the way a planning system replays its queue after a breakdown.
     */
    public synchronized Planning.Event slipLine(String lineId, int workingDays, LocalDate from, String reason) {
        if (!lines.containsKey(lineId)) {
            throw new IllegalArgumentException("Unknown line " + lineId);
        }
        int lost = 0;
        for (LocalDate d = from; lost < workingDays; d = d.plusDays(1)) {
            if (capacity(lineId, d).availableMinutes() > 0) {
                overrides.put(key(lineId, d), new Planning.CapacityDay(lineId, d, 0, reason));
                lost++;
            }
        }
        record Chunk(String woId, LocalDate date, int qty, int order) {
        }
        List<Chunk> queue = new ArrayList<>();
        List<String> woOrder = new ArrayList<>(workOrders.keySet());
        Map<String, List<Planning.Allocation>> kept = new HashMap<>();
        for (Planning.WorkOrder wo : workOrders.values()) {
            if (!wo.lineId().equals(lineId) || closed(wo)) {
                continue;
            }
            List<Planning.Allocation> keep = new ArrayList<>();
            for (Planning.Allocation a : wo.allocations()) {
                if (a.date().isBefore(from)) {
                    keep.add(a);
                } else {
                    queue.add(new Chunk(wo.id(), a.date(), a.qty(), woOrder.indexOf(wo.id())));
                }
            }
            kept.put(wo.id(), keep);
        }
        queue.sort(Comparator.comparing(Chunk::date).thenComparingInt(Chunk::order));
        Map<LocalDate, Integer> used = new HashMap<>();
        LocalDate cursor = from;
        for (Chunk c : queue) {
            Planning.WorkOrder wo = workOrders.get(c.woId());
            double mpu = minutesPerUnit(wo.sku(), lineId);
            int left = c.qty();
            int guard = 0;
            while (left > 0 && guard++ < 400) {
                int free = capacity(lineId, cursor).availableMinutes() - used.getOrDefault(cursor, 0);
                int units = (int) Math.floor(free / mpu);
                if (units <= 0) {
                    cursor = cursor.plusDays(1);
                    continue;
                }
                int place = Math.min(units, left);
                kept.get(wo.id()).add(new Planning.Allocation(cursor, place));
                used.merge(cursor, (int) Math.ceil(place * mpu), Integer::sum);
                left -= place;
            }
        }
        int moved = 0;
        for (Map.Entry<String, List<Planning.Allocation>> e : kept.entrySet()) {
            Planning.WorkOrder wo = workOrders.get(e.getKey());
            List<Planning.Allocation> merged = merge(e.getValue());
            if (!merged.equals(wo.allocations())) {
                moved++;
            }
            workOrders.put(wo.id(), new Planning.WorkOrder(wo.id(), wo.orderId(), wo.sku(), wo.lineId(), wo.qty(),
                    wo.completedQty(), merged, wo.status(), wo.originalPlannedFinish(), wo.overtimeMinutes(),
                    wo.idempotencyKey(), version.incrementAndGet(), clock.now()));
        }
        return event("LINE_SLIP", lineId, null, lineId + " lost " + workingDays + " working day(s) from " + from
                + " (" + reason + "); " + moved + " work order(s) pushed back");
    }

    public synchronized Planning.Event reportProgress(String woId, int completedQty) {
        Planning.WorkOrder wo = workOrders.get(woId);
        if (wo == null) {
            throw new IllegalArgumentException("Unknown work order " + woId);
        }
        int delta = Math.max(0, completedQty - wo.completedQty());
        List<Planning.Allocation> remaining = new ArrayList<>();
        int toRemove = delta;
        for (Planning.Allocation a : wo.allocations()) {
            int take = Math.min(toRemove, a.qty());
            toRemove -= take;
            if (a.qty() - take > 0) {
                remaining.add(new Planning.Allocation(a.date(), a.qty() - take));
            }
        }
        String status = completedQty >= wo.qty() ? "DONE" : "IN_PROGRESS";
        workOrders.put(woId, new Planning.WorkOrder(wo.id(), wo.orderId(), wo.sku(), wo.lineId(), wo.qty(),
                completedQty, remaining, status, wo.originalPlannedFinish(), wo.overtimeMinutes(),
                wo.idempotencyKey(), version.incrementAndGet(), clock.now()));
        return event("WO_PROGRESS", wo.lineId(), woId, completedQty + "/" + wo.qty() + " complete");
    }

    public synchronized void setFeedLag(int minutes) {
        this.feedLagMinutes = Math.max(0, minutes);
    }

    private Planning.Event event(String type, String lineId, String woId, String detail) {
        Planning.Event e = new Planning.Event(lastSeq() + 1, type, lineId, woId, detail, clock.now());
        events.add(e);
        return e;
    }

    private long lastSeq() {
        return events.isEmpty() ? 0 : events.get(events.size() - 1).seq();
    }

    private Planning.CapacityDay capacity(String lineId, LocalDate d) {
        Planning.CapacityDay o = overrides.get(key(lineId, d));
        if (o != null) {
            return o;
        }
        Planning.Line l = lines.get(lineId);
        int minutes = d.getDayOfWeek() == DayOfWeek.SUNDAY ? 0
                : d.getDayOfWeek() == DayOfWeek.SATURDAY ? l.saturdayMinutes() : l.weekdayMinutes();
        return new Planning.CapacityDay(lineId, d, minutes, null);
    }

    private double minutesPerUnit(String sku, String lineId) {
        return routings.stream().filter(r -> r.sku().equals(sku) && r.lineId().equals(lineId)).findFirst()
                .map(Planning.Routing::minutesPerUnit)
                .orElseThrow(() -> new IllegalArgumentException("No routing for " + sku + " on " + lineId));
    }

    private int booked(String lineId, LocalDate d, String excludeWo) {
        return booked(lineId, d, excludeWo == null ? java.util.Set.of() : java.util.Set.of(excludeWo));
    }

    private int booked(String lineId, LocalDate d, java.util.Set<String> excludeWos) {
        int minutes = 0;
        for (Planning.WorkOrder wo : workOrders.values()) {
            if (!wo.lineId().equals(lineId) || closed(wo) || excludeWos.contains(wo.id())) {
                continue;
            }
            double mpu = minutesPerUnit(wo.sku(), lineId);
            for (Planning.Allocation a : wo.allocations()) {
                if (a.date().equals(d)) {
                    minutes += (int) Math.ceil(a.qty() * mpu);
                }
            }
        }
        return minutes;
    }

    private void checkCapacity(String lineId, double mpu, List<Planning.Allocation> allocations, String excludeWo) {
        for (Planning.Allocation a : merge(allocations)) {
            int free = capacity(lineId, a.date()).availableMinutes() - booked(lineId, a.date(), excludeWo);
            int need = (int) Math.ceil(a.qty() * mpu);
            if (need > free) {
                throw new IllegalStateException("Capacity exceeded on " + lineId + " " + a.date() + ": free " + free
                        + " min, need " + need + " min");
            }
        }
    }

    private void applyAddedCapacity(List<Planning.CapacityDay> added) {
        if (added == null) {
            return;
        }
        for (Planning.CapacityDay c : added) {
            Planning.CapacityDay current = capacity(c.lineId(), c.date());
            overrides.put(key(c.lineId(), c.date()), new Planning.CapacityDay(c.lineId(), c.date(),
                    current.availableMinutes() + c.availableMinutes(), "overtime: " + c.note()));
        }
    }

    private static int addedMinutes(List<Planning.CapacityDay> added) {
        return added == null ? 0 : added.stream().mapToInt(Planning.CapacityDay::availableMinutes).sum();
    }

    private static Planning.WorkOrder withOriginalFinish(Planning.WorkOrder w, LocalDate finish) {
        return new Planning.WorkOrder(w.id(), w.orderId(), w.sku(), w.lineId(), w.qty(), w.completedQty(),
                w.allocations(), w.status(), finish, w.overtimeMinutes(), w.idempotencyKey(), w.version(),
                w.updatedAt());
    }

    private static List<Planning.Allocation> merge(List<Planning.Allocation> in) {
        TreeMap<LocalDate, Integer> m = new TreeMap<>();
        in.forEach(a -> m.merge(a.date(), a.qty(), Integer::sum));
        List<Planning.Allocation> out = new ArrayList<>();
        m.forEach((d, q) -> {
            if (q > 0) {
                out.add(new Planning.Allocation(d, q));
            }
        });
        return out;
    }

    private static boolean closed(Planning.WorkOrder wo) {
        return wo.status().equals("DONE") || wo.status().equals("CANCELLED");
    }

    private static String key(String line, LocalDate d) {
        return line + "|" + d;
    }
}
