package com.s2p.core.sales;

import com.s2p.contracts.Crm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, readable logistic model of quotation loss. The weights are hand-set and written down; the model's honesty
 * comes from scoring it against closed opportunities (Brier score) and reporting that next to every ranking.
 */
public final class QuoteRiskModel {
    public static final double INTERCEPT = -1.6;
    public static final Map<String, Double> WEIGHTS = new LinkedHashMap<>();

    static {
        WEIGHTS.put("daysSilent", 0.09);
        WEIGHTS.put("priceGapPct", 0.18);
        WEIGHTS.put("deliveryGapDays", 0.15);
        WEIGHTS.put("competitorMentioned", 1.2);
        WEIGHTS.put("validityExpiring", 0.9);
        WEIGHTS.put("accountWinRate", -1.5);
    }

    public record Calibration(int samples, double brier, double baselineBrier) {
        public String describe() {
            return String.format("Brier %.3f on %d closed quotations (always guessing the base rate scores %.3f; lower is better)",
                    brier, samples, baselineBrier);
        }
    }

    public static Map<String, Double> contributions(Map<String, Double> signals) {
        Map<String, Double> out = new LinkedHashMap<>();
        WEIGHTS.forEach((k, w) -> {
            double x = signals.getOrDefault(k, 0.0);
            out.put(k, w * (k.equals("accountWinRate") ? x - 0.5 : x));
        });
        return out;
    }

    public static double pLoss(Map<String, Double> signals) {
        double z = INTERCEPT + contributions(signals).values().stream().mapToDouble(Double::doubleValue).sum();
        return 1.0 / (1.0 + Math.exp(-z));
    }

    public static Calibration calibrate(List<Crm.Opportunity> opportunities) {
        List<Crm.Opportunity> closed = opportunities.stream()
                .filter(o -> ("WON".equals(o.stage()) || "LOST".equals(o.stage())) && o.signals() != null).toList();
        if (closed.isEmpty()) {
            return new Calibration(0, Double.NaN, Double.NaN);
        }
        double base = closed.stream().filter(o -> o.stage().equals("LOST")).count() / (double) closed.size();
        double brier = 0;
        double baseline = 0;
        for (Crm.Opportunity o : closed) {
            double y = o.stage().equals("LOST") ? 1 : 0;
            brier += Math.pow(pLoss(o.signals()) - y, 2);
            baseline += Math.pow(base - y, 2);
        }
        return new Calibration(closed.size(), brier / closed.size(), baseline / closed.size());
    }
}
