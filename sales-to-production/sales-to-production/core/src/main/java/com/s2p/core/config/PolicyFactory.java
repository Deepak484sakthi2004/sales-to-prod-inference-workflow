package com.s2p.core.config;

import com.s2p.core.policy.Policy;
import com.s2p.core.state.Source;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/** Builds the policy from defaults plus any values set in application.yml or environment variables. */
public final class PolicyFactory {
    private PolicyFactory() {
    }

    public static Policy from(CoreProperties.PolicyProps c) {
        Policy d = Policy.defaults();
        if (c == null) {
            return d;
        }
        Map<Source, Duration> sla = new EnumMap<>(d.freshnessSla());
        if (c.planningSlaMinutes() != null) {
            sla.put(Source.PLANNING, Duration.ofMinutes(c.planningSlaMinutes()));
        }
        if (c.erpSlaMinutes() != null) {
            sla.put(Source.ERP, Duration.ofMinutes(c.erpSlaMinutes()));
        }
        Map<Source, Duration> limit = new EnumMap<>(d.commitLimit());
        if (c.commitLimitMinutes() != null) {
            limit.put(Source.ERP, Duration.ofMinutes(c.commitLimitMinutes()));
            limit.put(Source.PLANNING, Duration.ofMinutes(c.commitLimitMinutes()));
        }
        return new Policy(d.version() + "+config",
                c.minSlackWorkingDays() == null ? d.minSlackWorkingDays() : c.minSlackWorkingDays(),
                d.qcWorkingDays(), d.dispatchLeadWorkingDays(), d.materialInspectionWorkingDays(),
                d.productionStartOffsetDays(),
                c.allowAlternateLines() == null ? d.allowAlternateLines() : c.allowAlternateLines(),
                c.quoteValidityDays() == null ? d.quoteValidityDays() : c.quoteValidityDays(),
                d.dateHoldHours(), d.horizonDays(), sla, limit,
                c.goodwillPctPerLateDay() == null || c.goodwillPctPerLateDay().isEmpty()
                        ? d.goodwillPctPerLateDay() : c.goodwillPctPerLateDay(),
                d.goodwillCapPct(),
                c.proactiveNoticeGoodwillReduction() == null ? d.proactiveNoticeGoodwillReduction()
                        : c.proactiveNoticeGoodwillReduction(),
                d.extraShipmentCost(), d.staffHourCost(), d.marginFloorPct(), d.autoScheduleMinSlack(),
                d.autoScheduleMaxValue(),
                c.enabledRules() == null ? d.enabledRules() : Set.copyOf(c.enabledRules()));
    }
}
