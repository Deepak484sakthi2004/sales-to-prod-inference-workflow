package com.s2p.core.web;

import com.s2p.core.CoreSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps the system current: new mail, new production events, customer status. Paused while scenarios run. */
@Component
public class Poller {
    private static final Logger LOG = LoggerFactory.getLogger(Poller.class);
    private final CoreSystem core;
    private final boolean enabled;
    private final AtomicBoolean paused = new AtomicBoolean(false);

    public Poller(CoreSystem core, @Value("${s2p.poll.enabled:true}") boolean enabled) {
        this.core = core;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${s2p.poll.interval-ms:30000}", initialDelay = 8000)
    public void scheduled() {
        if (enabled && !paused.get()) {
            run();
        }
    }

    public List<String> run() {
        try {
            List<String> notes = core.tick();
            notes.forEach(n -> LOG.info("tick: {}", n));
            return notes;
        } catch (RuntimeException e) {
            LOG.warn("tick failed: {}", e.getMessage());
            return List.of("tick failed: " + e.getMessage());
        }
    }

    public void pause(boolean p) {
        paused.set(p);
    }
}
