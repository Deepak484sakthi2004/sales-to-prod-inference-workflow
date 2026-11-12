package com.s2p.core;

import com.s2p.contracts.SimClock;
import com.s2p.core.ai.AiGateway;
import com.s2p.core.persistence.CoreStore;
import com.s2p.core.persistence.memory.InMemoryCoreStore;
import com.s2p.core.policy.Policy;
import com.s2p.sources.LocalSources;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * The whole stack in one process: real stores behind in-process adapters, a frozen business clock, and the scripted
 * stand-in for the language model unless a test passes another.
 */
public final class LocalSystem {
    public static final OffsetDateTime START = OffsetDateTime.parse("2026-11-11T09:30:00+05:30");

    public final SimClock clock = SimClock.frozen(START);
    public final LocalSources sources = new LocalSources(clock);
    public final CoreSystem core;

    public LocalSystem() {
        this(Policy.defaults());
    }

    public LocalSystem(Policy policy) {
        this(policy, new InMemoryCoreStore(), new ScriptedModel());
    }

    public LocalSystem(CoreStore store, AiGateway ai) {
        this(Policy.defaults(), store, ai);
    }

    public LocalSystem(Policy policy, CoreStore store, AiGateway ai) {
        core = new CoreSystem(sources.mailboxPort(), sources.crmPort(), sources.erpPort(), sources.planningPort(),
                sources.statusFeedPort(), clock, policy, null, Duration.ZERO, store, ai);
        core.start();
    }

    /** A second core against the same stand-ins and the same store, as after a restart or on another instance. */
    public CoreSystem restart(CoreStore store, AiGateway ai) {
        CoreSystem c = new CoreSystem(sources.mailboxPort(), sources.crmPort(), sources.erpPort(), sources.planningPort(),
                sources.statusFeedPort(), clock, core.policy(), null, Duration.ZERO, store, ai);
        c.start();
        return c;
    }
}
