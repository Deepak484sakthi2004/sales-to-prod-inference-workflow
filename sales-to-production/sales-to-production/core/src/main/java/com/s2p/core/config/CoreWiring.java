package com.s2p.core.config;

import com.s2p.contracts.SimClock;
import com.s2p.core.CoreSystem;
import com.s2p.core.ai.AiGateway;
import com.s2p.core.ai.HttpAiGateway;
import com.s2p.core.persistence.CoreStore;
import com.s2p.core.persistence.CoreStoreFactory;
import com.s2p.core.persistence.memory.InMemoryCoreStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

/** Wires the framework-free CoreSystem to the HTTP stand-ins, the configured store and the AI service. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(CoreProperties.class)
public class CoreWiring {

    @Bean
    public SimClock simClock(CoreProperties p) {
        Long anchor = p.sim().realAnchorEpoch();
        return SimClock.live(OffsetDateTime.parse(p.sim().start()),
                anchor == null || anchor <= 0 ? Instant.now() : Instant.ofEpochSecond(anchor));
    }

    @Bean
    public HttpPorts httpPorts(CoreProperties p) {
        return new HttpPorts(p.sources());
    }

    @Bean(destroyMethod = "close")
    public CoreStore coreStore(CoreProperties p) {
        CoreProperties.Store s = p.store();
        return s == null ? new InMemoryCoreStore()
                : CoreStoreFactory.create(s.type(), s.mongo() == null ? null : s.mongo().uri(),
                s.mongo() == null ? null : s.mongo().database());
    }

    @Bean
    public AiGateway aiGateway(CoreProperties p) {
        CoreProperties.Ai a = p.ai();
        return a != null && a.enabled() && a.url() != null && !a.url().isBlank()
                ? new HttpAiGateway(a.url(), Duration.ofMillis(a.timeoutMs() > 0 ? a.timeoutMs() : 20000))
                : AiGateway.disabled();
    }

    @Bean(destroyMethod = "")
    public CoreSystem coreSystem(HttpPorts ports, SimClock clock, CoreProperties p, CoreStore store, AiGateway ai) {
        Path log = p.decisionLog() == null || p.decisionLog().isBlank() ? null : Path.of(p.decisionLog());
        return build(ports, clock, p, log, store, ai);
    }

    /** A core with its own in-memory records, for the live scenario run: it never writes to the configured store. */
    public static CoreSystem scratch(HttpPorts ports, SimClock clock, CoreProperties p, AiGateway ai) {
        return build(ports, clock, p, null, new InMemoryCoreStore(), ai);
    }

    private static CoreSystem build(HttpPorts ports, SimClock clock, CoreProperties p, Path decisionLog, CoreStore store,
                                    AiGateway ai) {
        CoreSystem s = new CoreSystem(ports.mailbox(), ports.crm(), ports.erp(), ports.planning(), ports.statusFeed(),
                clock, PolicyFactory.from(p.policy()), decisionLog, Duration.ZERO, store, ai);
        s.start();
        return s;
    }
}
