package com.s2p.core;

import com.s2p.contracts.Ports;
import com.s2p.contracts.SimClock;
import com.s2p.core.ai.AiGateway;
import com.s2p.core.engine.PromiseAssessor;
import com.s2p.core.governance.ActionQueue;
import com.s2p.core.governance.DecisionLog;
import com.s2p.core.knowledge.Freshness;
import com.s2p.core.persistence.CoreStore;
import com.s2p.core.policy.Policy;
import com.s2p.core.state.CentralView;
import com.s2p.core.state.Source;
import com.s2p.core.state.StateStore;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * Shared collaborators handed to every service, so each service receives one thing instead of ten. {@code store} is
 * where the core keeps its own facts (in memory by default, MongoDB when configured); {@code ai} is the language model,
 * disabled unless configured.
 */
public record CoreContext(StateStore state, Supplier<Policy> policy, SimClock clock, ActionQueue actions,
                          DecisionLog log, PromiseAssessor promises, SuggestionBoard suggestions,
                          Ports.MailboxPort mailbox, Ports.CrmPort crm, Ports.ErpPort erp,
                          Ports.PlanningPort planning, Ports.StatusFeedPort statusFeed, CoreStore store, AiGateway ai) {

    public static List<Freshness> fresh(CentralView v, Source... sources) {
        return Arrays.stream(sources).map(v::freshness).toList();
    }
}
