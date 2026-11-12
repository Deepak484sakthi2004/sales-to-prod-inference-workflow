package com.s2p.core;

import com.s2p.core.governance.ActionPayload;
import com.s2p.core.governance.ActionQueue;
import com.s2p.core.governance.ProposedAction;
import com.s2p.core.governance.Suggestion;
import com.s2p.core.persistence.CoreStore;
import com.s2p.core.persistence.StoreCollection;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Current suggestions. Accepting one is a recorded human decision: it creates the action and approves it in the
 * same step, under the name of the person who accepted it.
 */
public final class SuggestionBoard {
    private final CoreStore store;
    private final StoreCollection<Suggestion> current;
    private final ActionQueue actions;

    public SuggestionBoard(CoreStore store, ActionQueue actions) {
        this.store = store;
        this.current = store.collection("suggestions", Suggestion.class, "subject");
        this.actions = actions;
    }

    public String nextId() {
        return String.format("S-%03d", store.nextSequence("suggestion"));
    }

    public synchronized void replace(String subjectPrefix, List<Suggestion> fresh) {
        current.deleteByPrefix("subject", subjectPrefix);
        fresh.forEach(s -> current.upsert(s.id(), s, Map.of("subject", s.subject())));
    }

    public List<Suggestion> all() {
        return current.all().stream().sorted(Comparator.comparing(Suggestion::netBenefit).reversed()).toList();
    }

    public Suggestion get(String id) {
        return current.get(id).orElseThrow(() -> new IllegalArgumentException("Unknown or superseded suggestion " + id));
    }

    public ProposedAction accept(String id, String who, String role, String note) {
        Suggestion s = get(id);
        if (s.payload() == null) {
            throw new IllegalStateException(id + " is advice only; there is nothing for the system to execute");
        }
        ProposedAction draft = ProposedAction.draft(type(s.payload()), s.action(), s.payload(), s.requiredRole(),
                "suggestion:" + s.id() + ":" + s.subject(), Map.of(), List.of(), null);
        ProposedAction a = actions.propose(draft);
        return a.pending() ? actions.decide(a.id(), who, role, true, note == null ? "accepted " + id : note) : a;
    }

    private static String type(ActionPayload p) {
        return switch (p) {
            case ActionPayload.Replan r -> "REPLAN";
            case ActionPayload.PublishStatus ps -> "PUBLISH_STATUS";
            case ActionPayload.SendEmail e -> "SEND_EMAIL";
            default -> p.getClass().getSimpleName().toUpperCase();
        };
    }
}
