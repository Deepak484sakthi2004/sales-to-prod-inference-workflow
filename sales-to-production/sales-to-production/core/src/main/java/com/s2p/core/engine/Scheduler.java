package com.s2p.core.engine;

import com.s2p.contracts.Planning;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finite-capacity forward scheduler. Places units day by day on the given routings in order, never beyond free
 * minutes (plus any overtime allowed) and never beyond the material that is usable on that day.
 */
public final class Scheduler {
    public record Placement(String lineId, LocalDate date, int qty, int minutes, int overtimeMinutes,
                            boolean alternate) {
    }

    public record Schedule(List<Placement> placements, LocalDate finish, int unplaced, boolean usedAlternate,
                           boolean materialLimited, int changeoverMinutes, int overtimeMinutes,
                           List<MaterialModel.Receipt> reliedOn) {
        public static Schedule empty() {
            return new Schedule(List.of(), null, 0, false, false, 0, 0, List.of());
        }

        public boolean complete() {
            return unplaced == 0;
        }
    }

    private Scheduler() {
    }

    public static Schedule run(String sku, int qty, LocalDate start, LocalDate end, List<Planning.Routing> routings,
                               CapacityModel cap, MaterialModel mat, boolean useOvertime) {
        if (qty <= 0) {
            return Schedule.empty();
        }
        List<Placement> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        int remaining = qty;
        boolean materialLimited = false;
        boolean usedAlt = false;
        int changeover = 0;
        int overtime = 0;
        for (LocalDate d = start; !d.isAfter(end) && remaining > 0; d = d.plusDays(1)) {
            for (Planning.Routing r : routings) {
                if (remaining <= 0) {
                    break;
                }
                int normal = cap.free(r.lineId(), d);
                int ot = useOvertime ? Math.max(0, cap.overtimeFree(r.lineId(), d)) : 0;
                int setup = (!r.preferred() && !used.contains(r.lineId())) ? r.changeoverMinutes() : 0;
                int usable = normal + ot - setup;
                int byCapacity = (int) Math.floor(usable / r.minutesPerUnit());
                if (byCapacity <= 0) {
                    continue;
                }
                int byMaterial = mat.buildable(r.sku(), d);
                if (byMaterial < Math.min(byCapacity, remaining)) {
                    materialLimited = true;
                }
                int units = Math.min(remaining, Math.min(byCapacity, byMaterial));
                if (units <= 0) {
                    continue;
                }
                int minutes = (int) Math.ceil(units * r.minutesPerUnit()) + setup;
                int fromNormal = Math.min(normal, minutes);
                int fromOt = minutes - fromNormal;
                cap.book(r.lineId(), d, fromNormal);
                if (fromOt > 0) {
                    cap.useOvertime(r.lineId(), d, fromOt);
                }
                mat.consume(r.sku(), units);
                used.add(r.lineId());
                usedAlt |= !r.preferred();
                changeover += setup;
                overtime += fromOt;
                out.add(new Placement(r.lineId(), d, units, minutes, fromOt, !r.preferred()));
                remaining -= units;
            }
        }
        LocalDate finish = remaining == 0 && !out.isEmpty() ? out.get(out.size() - 1).date() : null;
        return new Schedule(List.copyOf(out), finish, remaining, usedAlt, materialLimited, changeover, overtime,
                mat.reliedOn(sku));
    }
}
