package com.s2p.contracts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

/** Production planning contract: capacity by line and day, routings, work orders and their status. */
public final class Planning {
    private Planning() {
    }

    public record Line(String id, String name, int weekdayMinutes, int saturdayMinutes, BigDecimal costPerMinute,
                       BigDecimal overtimeCostPerHour, int maxWeekdayOvertimeMinutes, int maxSundayOvertimeMinutes) {
    }

    public record CapacityDay(String lineId, LocalDate date, int availableMinutes, String note) {
    }

    public record Routing(String sku, String lineId, double minutesPerUnit, boolean preferred,
                          int changeoverMinutes) {
    }

    public record Allocation(LocalDate date, int qty) {
    }

    /** status PLANNED, RELEASED, IN_PROGRESS, DONE. */
    public record WorkOrder(String id, String orderId, String sku, String lineId, int qty, int completedQty,
                            List<Allocation> allocations, String status, LocalDate originalPlannedFinish,
                            int overtimeMinutes, String idempotencyKey, long version, OffsetDateTime updatedAt) {
        public WorkOrder {
            allocations = allocations == null ? List.of() : List.copyOf(allocations);
        }

        public LocalDate plannedFinish() {
            return allocations.stream().filter(a -> a.qty() > 0).map(Allocation::date)
                    .max(Comparator.naturalOrder()).orElse(null);
        }

        public int remainingQty() {
            return qty - completedQty;
        }
    }

    public record History(String workOrderId, String lineId, LocalDate plannedFinish, LocalDate actualFinish) {
    }

    public record Event(long seq, String type, String lineId, String workOrderId, String detail,
                        OffsetDateTime occurredAt) {
    }

    public record Snapshot(List<Line> lines, List<CapacityDay> capacity, List<Routing> routings,
                           List<WorkOrder> workOrders, List<History> history, long lastEventSeq) {
    }

    public record NewWorkOrder(String idempotencyKey, String orderId, String sku, String lineId, int qty,
                               List<Allocation> allocations, List<CapacityDay> addedCapacity) {
    }

    /** Moves the remaining quantity of a work order. addedCapacity carries approved overtime. */
    public record Reschedule(String idempotencyKey, String workOrderId, List<Allocation> allocations,
                             List<CapacityDay> addedCapacity, String reason) {
    }
}
