package com.s2p.core.governance;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Anything that would reach a customer or change production. status: PENDING, EXECUTED, REJECTED, FAILED,
 * INVALIDATED (the data moved and the action is no longer supported). Every transition records who decided.
 */
public record ProposedAction(String id, String type, String summary, ActionPayload payload, String requiredRole,
                             String idempotencyKey, OffsetDateTime createdAt, Map<String, Object> ruleFacts,
                             List<DateCommitment> commitments, String sourceDecisionId, String status, String decidedBy,
                             OffsetDateTime decidedAt, String decisionNote, String ruleId, String result) {

    public ProposedAction {
        ruleFacts = ruleFacts == null ? Map.of() : Map.copyOf(ruleFacts);
        commitments = commitments == null ? List.of() : List.copyOf(commitments);
    }

    public static ProposedAction draft(String type, String summary, ActionPayload payload, String requiredRole,
                                       String idempotencyKey, Map<String, Object> ruleFacts, List<DateCommitment> commitments,
                                       String sourceDecisionId) {
        return new ProposedAction(null, type, summary, payload, requiredRole, idempotencyKey, null, ruleFacts,
                commitments, sourceDecisionId, "DRAFT", null, null, null, null, null);
    }

    ProposedAction withId(String id, OffsetDateTime at) {
        return new ProposedAction(id, type, summary, payload, requiredRole, idempotencyKey, at, ruleFacts, commitments,
                sourceDecisionId, "PENDING", null, null, null, null, null);
    }

    ProposedAction decided(String status, String by, OffsetDateTime at, String note, String ruleId, String result) {
        return new ProposedAction(id, type, summary, payload, requiredRole, idempotencyKey, createdAt, ruleFacts,
                commitments, sourceDecisionId, status, by, at, note, ruleId, result);
    }

    public boolean pending() {
        return "PENDING".equals(status);
    }
}
