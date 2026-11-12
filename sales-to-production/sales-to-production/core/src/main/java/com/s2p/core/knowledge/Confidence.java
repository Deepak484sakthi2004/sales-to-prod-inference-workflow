package com.s2p.core.knowledge;

import java.util.ArrayList;
import java.util.List;

/**
 * Confidence is a product of named, explained factors. Every factor that pulled the score down is visible, so
 * "how sure are you?" always has the answer "this sure, because of these things".
 */
public record Confidence(double score, String level, List<Factor> factors) {
    public record Factor(String name, double multiplier, String reason) {
        public String describe() {
            return String.format("x%.2f %s: %s", multiplier, name, reason);
        }
    }

    public static Builder start() {
        return new Builder();
    }

    public static String levelFor(double s) {
        if (s >= 0.8) {
            return "HIGH";
        }
        if (s >= 0.6) {
            return "MEDIUM";
        }
        if (s >= 0.4) {
            return "LOW";
        }
        return "VERY_LOW";
    }

    public String describe() {
        return String.format("%.2f (%s)", score, level);
    }

    public static final class Builder {
        private double score = 1.0;
        private final List<Factor> factors = new ArrayList<>();

        public Builder apply(String name, double multiplier, String reason) {
            double m = Math.max(0.0, Math.min(1.0, multiplier));
            factors.add(new Factor(name, Math.round(m * 100.0) / 100.0, reason));
            score *= m;
            return this;
        }

        public Builder applyAll(List<Factor> fs) {
            fs.forEach(f -> apply(f.name(), f.multiplier(), f.reason()));
            return this;
        }

        public Confidence build() {
            double s = Math.round(score * 100.0) / 100.0;
            return new Confidence(s, levelFor(s), List.copyOf(factors));
        }
    }
}
