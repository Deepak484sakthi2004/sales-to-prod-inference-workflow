package com.s2p.core.governance;

import com.s2p.core.engine.Money;
import com.s2p.core.knowledge.Describable;

import java.math.BigDecimal;
import java.util.List;

/**
 * A recommendation. It cannot be constructed without the outcome it protects, the action, its cost and the risk of
 * doing nothing, and it always carries the do-nothing comparison: anything less is noise, so it does not compile.
 */
public record Suggestion(String id, String subject, String outcome, BigDecimal valueAtStake, String action,
                         BigDecimal cost, String costNote, String riskIfNobodyActs,
                         BigDecimal expectedLossIfNobodyActs, BigDecimal expectedLossIfActed, BigDecimal netBenefit,
                         double confidence, String requiredRole, List<String> wouldChange, String optionKind,
                         ActionPayload payload) implements Describable {
    public Suggestion {
        if (blank(outcome) || blank(action) || blank(costNote) || blank(riskIfNobodyActs) || cost == null
                || expectedLossIfNobodyActs == null || expectedLossIfActed == null || netBenefit == null) {
            throw new IllegalArgumentException(
                    "A suggestion needs a named outcome, an action, a cost and the risk if nobody acts");
        }
        wouldChange = wouldChange == null ? List.of() : List.copyOf(wouldChange);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    @Override
    public String describe() {
        return "Protects: " + outcome + "\n"
                + "Action: " + action + "\n"
                + "Cost: " + Money.inr(cost) + " (" + costNote + ")\n"
                + "If nobody acts: " + riskIfNobodyActs + " - expected loss " + Money.inr(expectedLossIfNobodyActs) + "\n"
                + "If acted on: expected loss " + Money.inr(expectedLossIfActed) + " including the cost; net benefit "
                + Money.inr(netBenefit) + " vs doing nothing (confidence " + String.format("%.2f", confidence) + ")\n"
                + "Decided by: " + requiredRole;
    }
}
