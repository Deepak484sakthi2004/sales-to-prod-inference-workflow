package com.s2p.core.governance;

import com.s2p.contracts.SimClock;
import com.s2p.core.persistence.CoreStore;
import com.s2p.core.persistence.StoreCollection;
import com.s2p.core.policy.Policy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The only path from a recommendation to the outside world. Proposals are idempotent by key; each one either waits
 * for a person with the right role, or runs under a pre-approved rule, and both paths leave a decision record.
 * Actions live in the configured {@link CoreStore}; a decision first claims the action (PENDING -> DECIDING) so two
 * core instances sharing one MongoDB cannot both execute it.
 */
public final class ActionQueue {
    public interface Executor {
        Execution execute(ProposedAction a);
    }

    public record Execution(String status, String detail) {
    }

    private static final Set<String> RETRYABLE = Set.of("REJECTED", "FAILED", "INVALIDATED");

    private final CoreStore store;
    private final StoreCollection<ProposedAction> byId;
    private final StoreCollection<String> byKey;
    private final AutoApprovalRules rules;
    private final DecisionLog log;
    private final SimClock clock;
    private final Supplier<Policy> policy;
    private Executor executor;

    public ActionQueue(CoreStore store, AutoApprovalRules rules, DecisionLog log, SimClock clock, Supplier<Policy> policy) {
        this.store = store;
        this.byId = store.collection("actions", ProposedAction.class, "status");
        this.byKey = store.collection("action_keys", String.class);
        this.rules = rules;
        this.log = log;
        this.clock = clock;
        this.policy = policy;
    }

    public void setExecutor(Executor executor) {
        this.executor = executor;
    }

    public synchronized ProposedAction propose(ProposedAction draft) {
        String existingId = byKey.get(draft.idempotencyKey()).orElse(null);
        if (existingId != null) {
            Optional<ProposedAction> existing = byId.get(existingId);
            if (existing.isPresent() && !RETRYABLE.contains(existing.get().status())) {
                return existing.get();
            }
        }
        String id = String.format("A-%04d", store.nextSequence("action"));
        if (existingId == null && !byKey.insertIfAbsent(draft.idempotencyKey(), id, Map.of())) {
            String winner = byKey.get(draft.idempotencyKey()).orElseThrow();
            return byId.get(winner).orElseThrow(() -> new IllegalStateException("Action for " + draft.idempotencyKey()
                    + " is being proposed by another core instance; retry"));
        }
        if (existingId != null) {
            byKey.upsert(draft.idempotencyKey(), id, Map.of());
        }
        ProposedAction a = draft.withId(id, clock.now());
        save(a);
        Optional<AutoApprovalRules.Rule> rule = rules.match(a, policy.get(), clock.today());
        if (rule.isPresent()) {
            AutoApprovalRules.Rule r = rule.get();
            Execution ex = executor.execute(a);
            String by = "rule " + r.id() + " (approved in advance by " + r.approvedBy() + " on " + r.approvedOn() + ")";
            a = a.decided(ex.status(), by, clock.now(), r.description(), r.id(), ex.detail());
            save(a);
            log.record("ACTION_EXECUTED", "system", null, null, null, a.type() + " " + id + ": " + a.summary() + " -> "
                    + ex.status() + " (" + ex.detail() + ")", null, null, List.of(), null, null, r.id(), by, id,
                    policy.get().version());
        } else {
            log.record("ACTION_PROPOSED", "system", null, null, null, a.type() + " " + id + " awaiting "
                    + a.requiredRole() + ": " + a.summary(), null, null, List.of(), null, null, null, null, id,
                    policy.get().version());
        }
        return a;
    }

    public synchronized ProposedAction decide(String id, String who, String role, boolean approve, String note) {
        ProposedAction a = byId.get(id).orElseThrow(() -> new IllegalArgumentException("Unknown action " + id));
        if (!a.pending()) {
            throw new IllegalStateException("Action " + id + " was already decided: " + a.status() + " by " + a.decidedBy());
        }
        if (who == null || who.isBlank()) {
            throw new IllegalArgumentException("A decision must name the person making it");
        }
        if (!(role.equals(a.requiredRole()) || role.equals("OPS_HEAD"))) {
            throw new SecurityException("Action " + id + " needs a decision from " + a.requiredRole() + "; " + role
                    + " cannot decide it");
        }
        OffsetDateTime now = clock.now();
        ProposedAction pending = a;
        ProposedAction claim = a.decided("DECIDING", who + " (" + role + ")", now, note, null, null);
        if (!byId.replaceIf(id, claim, Map.of("status", claim.status()), "status", "PENDING")) {
            throw new IllegalStateException("Action " + id + " was decided by someone else a moment ago");
        }
        try {
            if (!approve) {
                a = a.decided("REJECTED", who + " (" + role + ")", now, note, null, "not executed");
            } else {
                Execution ex = executor.execute(a);
                a = a.decided(ex.status(), who + " (" + role + ")", now, note, null, ex.detail());
            }
        } catch (RuntimeException e) {
            save(pending);
            throw e;
        }
        save(a);
        log.record("ACTION_DECIDED", who, null, null, null, a.type() + " " + id + " " + (approve ? "approved" : "rejected")
                + " by " + who + " (" + role + ")" + (note == null ? "" : ": " + note) + " -> " + a.status() + " ("
                + a.result() + ")", null, null, List.of(), null, null, null, who, id, policy.get().version());
        return a;
    }

    public Optional<ProposedAction> get(String id) {
        return byId.get(id);
    }

    public List<ProposedAction> all() {
        return byId.all();
    }

    public List<ProposedAction> pending() {
        return byId.findBy("status", "PENDING");
    }

    private void save(ProposedAction a) {
        byId.upsert(a.id(), a, Map.of("status", a.status()));
    }
}
