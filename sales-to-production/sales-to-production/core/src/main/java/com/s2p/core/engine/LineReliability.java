package com.s2p.core.engine;

import com.s2p.contracts.Planning;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Past forecast accuracy per line: how often a line actually finished work orders on the planned day. */
public final class LineReliability {
    public record LineStats(String lineId, int samples, int onTime, double onTimeRate, double meanSlipDays,
                            int p80SlipDays) {
        public String describe() {
            return String.format("%s finished %d/%d work orders on time (%.0f%%), mean slip %.1f days, 80th pct %d days",
                    lineId, onTime, samples, onTimeRate * 100, meanSlipDays, p80SlipDays);
        }
    }

    private LineReliability() {
    }

    public static Map<String, LineStats> compute(List<Planning.History> history) {
        Map<String, List<Long>> slips = new LinkedHashMap<>();
        for (Planning.History h : history) {
            slips.computeIfAbsent(h.lineId(), k -> new ArrayList<>())
                    .add(Math.max(0, ChronoUnit.DAYS.between(h.plannedFinish(), h.actualFinish())));
        }
        Map<String, LineStats> out = new LinkedHashMap<>();
        slips.forEach((line, s) -> {
            List<Long> sorted = new ArrayList<>(s);
            Collections.sort(sorted);
            int onTime = (int) s.stream().filter(x -> x == 0).count();
            double mean = s.stream().mapToLong(Long::longValue).average().orElse(0);
            int p80 = sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(0.8 * sorted.size()) - 1)).intValue();
            out.put(line, new LineStats(line, s.size(), onTime, (double) onTime / s.size(), mean, p80));
        });
        return out;
    }
}
