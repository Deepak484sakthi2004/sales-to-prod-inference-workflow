package com.s2p.core.engine;

import com.s2p.contracts.Erp;
import com.s2p.core.policy.Policy;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What lateness costs: the contractual penalty on the order, plus an explicit, tunable estimate of goodwill lost
 * per late day by customer tier. Both components are always shown separately.
 */
public record LatenessCost(BigDecimal penalty, BigDecimal goodwill) {
    public BigDecimal total() {
        return penalty.add(goodwill);
    }

    public static LatenessCost of(Erp.SalesOrder o, String tier, int lateDays, BigDecimal lateValue, Policy p) {
        if (lateDays <= 0 || lateValue.signum() <= 0) {
            return new LatenessCost(BigDecimal.ZERO, BigDecimal.ZERO);
        }
        double ldPct = Math.min(o.latePenaltyPctPerDay() * lateDays, o.latePenaltyCapPct());
        double gwPct = Math.min(p.goodwillPctPerLateDay().getOrDefault(tier, 0.5) * lateDays, p.goodwillCapPct());
        return new LatenessCost(pct(lateValue, ldPct), pct(lateValue, gwPct));
    }

    private static BigDecimal pct(BigDecimal v, double pct) {
        return v.multiply(BigDecimal.valueOf(pct)).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
    }

    public String describe() {
        return Money.inr(total()) + " (penalty " + Money.inr(penalty) + " + goodwill " + Money.inr(goodwill) + ")";
    }
}
