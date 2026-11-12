package com.s2p.core.governance;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Append-only record of something the system said or did, with the data it used at that moment. This is what makes
 * "why did we tell the customer the 18th?" answerable weeks later.
 *
 * kind: PROMISE_ANSWER, QUOTE_DRAFTED, CLARIFICATION_DRAFTED, ORDER_RECORDED, PLAN_PROPOSED, REPLAN, SUGGESTION,
 * CUSTOMER_STATUS_PUBLISHED, CUSTOMER_EMAIL_SENT, ACTION_PROPOSED, ACTION_DECIDED, ACTION_EXECUTED,
 * OVERRIDE_DECLINED, DUPLICATE_IGNORED, STALE_UPDATE_REJECTED, POLICY_CHANGED.
 */
public record DecisionRecord(String id, OffsetDateTime at, String kind, String actor, String accountId,
                             String orderId, String quotationId, String statement, LocalDate dateStated,
                             Integer qtyStated, List<String> basis, Map<String, Long> sourceVersions,
                             Map<String, String> dataAge, Double confidence, String ruleId, String approvedBy,
                             String actionId, String policyVersion, LocalDate actualDelivery) {
    public DecisionRecord {
        basis = basis == null ? List.of() : List.copyOf(basis);
        sourceVersions = sourceVersions == null ? Map.of() : Map.copyOf(sourceVersions);
        dataAge = dataAge == null ? Map.of() : Map.copyOf(dataAge);
    }

    public boolean customerFacing() {
        return kind.equals("CUSTOMER_STATUS_PUBLISHED") || kind.equals("CUSTOMER_EMAIL_SENT");
    }
}
