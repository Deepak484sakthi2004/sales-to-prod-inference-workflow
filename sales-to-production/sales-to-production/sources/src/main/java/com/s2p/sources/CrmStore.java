package com.s2p.sources;

import com.s2p.contracts.Crm;
import com.s2p.contracts.RelativeJson;
import com.s2p.contracts.SimClock;
import com.s2p.contracts.SourceResponse;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

/** CRM stand-in, read-only: accounts, contacts, opportunities (with recorded deal signals) and activity notes. */
public final class CrmStore {
    private final SimClock clock;
    private final LocalDate anchor;
    private final AtomicLong version = new AtomicLong();
    private Crm.Snapshot data;

    public CrmStore(SimClock clock, LocalDate anchor) {
        this.clock = clock;
        this.anchor = anchor;
        reset();
    }

    public synchronized void reset() {
        data = new RelativeJson(anchor).load("seed/crm.json", Crm.Snapshot.class);
        version.set(500);
    }

    public synchronized SourceResponse<Crm.Snapshot> snapshot() {
        return new SourceResponse<>("crm", version.get(), clock.now(), data);
    }
}
