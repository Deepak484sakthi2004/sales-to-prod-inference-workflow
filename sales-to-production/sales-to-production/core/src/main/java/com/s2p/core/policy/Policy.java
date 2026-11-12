package com.s2p.core.policy;

import com.s2p.core.state.Source;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Every business rule the system applies, in one place. Changing a requirement in the room means changing a value
 * here (or in application.yml) and nothing else.
 */
public record Policy(
        String version,
        int minSlackWorkingDays,
        int qcWorkingDays,
        int dispatchLeadWorkingDays,
        int materialInspectionWorkingDays,
        int productionStartOffsetDays,
        boolean allowAlternateLines,
        int quoteValidityDays,
        int dateHoldHours,
        int horizonDays,
        Map<Source, Duration> freshnessSla,
        Map<Source, Duration> commitLimit,
        Map<String, Double> goodwillPctPerLateDay,
        double goodwillCapPct,
        double proactiveNoticeGoodwillReduction,
        BigDecimal extraShipmentCost,
        BigDecimal staffHourCost,
        double marginFloorPct,
        int autoScheduleMinSlack,
        BigDecimal autoScheduleMaxValue,
        Set<String> enabledRules) {

    public static Policy defaults() {
        Map<Source, Duration> sla = new EnumMap<>(Source.class);
        sla.put(Source.MAILBOX, Duration.ofMinutes(5));
        sla.put(Source.ERP, Duration.ofMinutes(15));
        sla.put(Source.PLANNING, Duration.ofMinutes(30));
        sla.put(Source.STATUS_FEED, Duration.ofMinutes(30));
        sla.put(Source.CRM, Duration.ofHours(24));
        Map<Source, Duration> limit = new EnumMap<>(Source.class);
        limit.put(Source.ERP, Duration.ofHours(2));
        limit.put(Source.PLANNING, Duration.ofHours(2));
        Map<String, Double> goodwill = new LinkedHashMap<>();
        goodwill.put("A", 1.5);
        goodwill.put("B", 0.75);
        goodwill.put("C", 0.4);
        return new Policy("2026-11-01", 0, 1, 1, 1, 1, true, 15, 24, 45, sla, limit, goodwill, 10.0, 0.4,
                new BigDecimal("6500"), new BigDecimal("600"), 12.0, 3, new BigDecimal("500000"),
                Set.of("R1", "R2", "R3", "R4"));
    }

    public Policy withMinSlack(int days) {
        return new Policy(version + "+minSlack" + days, days, qcWorkingDays, dispatchLeadWorkingDays,
                materialInspectionWorkingDays, productionStartOffsetDays, allowAlternateLines, quoteValidityDays,
                dateHoldHours, horizonDays, freshnessSla, commitLimit, goodwillPctPerLateDay, goodwillCapPct,
                proactiveNoticeGoodwillReduction, extraShipmentCost, staffHourCost, marginFloorPct,
                autoScheduleMinSlack, autoScheduleMaxValue, enabledRules);
    }

    public Policy withRule(String ruleId, boolean enabled) {
        Set<String> rules = new HashSet<>(enabledRules);
        if (enabled) {
            rules.add(ruleId);
        } else {
            rules.remove(ruleId);
        }
        return new Policy(version + (enabled ? "+" : "-") + ruleId, minSlackWorkingDays, qcWorkingDays,
                dispatchLeadWorkingDays, materialInspectionWorkingDays, productionStartOffsetDays,
                allowAlternateLines, quoteValidityDays, dateHoldHours, horizonDays, freshnessSla, commitLimit,
                goodwillPctPerLateDay, goodwillCapPct, proactiveNoticeGoodwillReduction, extraShipmentCost,
                staffHourCost, marginFloorPct, autoScheduleMinSlack, autoScheduleMaxValue, Set.copyOf(rules));
    }

    public Duration sla(Source s) {
        return freshnessSla.getOrDefault(s, Duration.ofMinutes(30));
    }

    public Duration commitLimit(Source s) {
        return commitLimit.get(s);
    }
}
