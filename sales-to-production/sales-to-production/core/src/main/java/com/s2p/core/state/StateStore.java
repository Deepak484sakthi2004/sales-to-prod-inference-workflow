package com.s2p.core.state;

import com.s2p.contracts.Crm;
import com.s2p.contracts.Erp;
import com.s2p.contracts.Mail;
import com.s2p.contracts.Planning;
import com.s2p.contracts.Ports;
import com.s2p.contracts.SimClock;
import com.s2p.contracts.SourceResponse;
import com.s2p.core.knowledge.Freshness;
import com.s2p.core.policy.Policy;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The core's copy of each system of record. Sources stay the truth; this store keeps the last good copy of each,
 * the source's own version and as-of time, and the last error. If a source cannot be reached the old copy is kept and
 * simply gets older, which is exactly what freshness then reports.
 */
public final class StateStore {
    private record Entry(Object data, long version, OffsetDateTime sourceAsOf, OffsetDateTime fetchedAt,
                         String lastError, OffsetDateTime lastErrorAt) {
    }

    private final Ports.MailboxPort mailbox;
    private final Ports.CrmPort crm;
    private final Ports.ErpPort erp;
    private final Ports.PlanningPort planning;
    private final SimClock clock;
    private final Supplier<Policy> policy;
    private final Duration minRefreshInterval;
    private final Map<Source, Entry> entries = new EnumMap<>(Source.class);
    private OffsetDateTime statusFeedContact;

    public StateStore(Ports.MailboxPort mailbox, Ports.CrmPort crm, Ports.ErpPort erp, Ports.PlanningPort planning,
                      SimClock clock, Supplier<Policy> policy, Duration minRefreshInterval) {
        this.mailbox = mailbox;
        this.crm = crm;
        this.erp = erp;
        this.planning = planning;
        this.clock = clock;
        this.policy = policy;
        this.minRefreshInterval = minRefreshInterval;
    }

    public synchronized void refresh(Source s) {
        try {
            SourceResponse<?> r = switch (s) {
                case MAILBOX -> mailbox.inbox();
                case CRM -> crm.snapshot();
                case ERP -> erp.snapshot();
                case PLANNING -> planning.snapshot();
                case STATUS_FEED -> null;
            };
            if (r != null) {
                entries.put(s, new Entry(r.data(), r.version(), r.asOf(), clock.now(), null, null));
            }
        } catch (RuntimeException e) {
            Entry old = entries.get(s);
            entries.put(s, old == null
                    ? new Entry(null, 0, null, null, e.getMessage(), clock.now())
                    : new Entry(old.data(), old.version(), old.sourceAsOf(), old.fetchedAt(), e.getMessage(), clock.now()));
        }
    }

    public synchronized void refreshAll() {
        for (Source s : List.of(Source.MAILBOX, Source.CRM, Source.ERP, Source.PLANNING)) {
            refresh(s);
        }
    }

    public synchronized void recordStatusFeedContact(OffsetDateTime at) {
        statusFeedContact = at;
    }

    /** Builds a consistent view, refreshing any copy older than the refresh interval first. */
    @SuppressWarnings("unchecked")
    public synchronized CentralView view() {
        for (Source s : List.of(Source.MAILBOX, Source.CRM, Source.ERP, Source.PLANNING)) {
            Entry e = entries.get(s);
            if (e == null || e.fetchedAt() == null
                    || Duration.between(e.fetchedAt(), clock.now()).compareTo(minRefreshInterval) >= 0) {
                refresh(s);
            }
        }
        Map<Source, Freshness> fr = new EnumMap<>(Source.class);
        Map<Source, Long> versions = new EnumMap<>(Source.class);
        for (Source s : Source.values()) {
            fr.put(s, freshness(s));
            Entry e = entries.get(s);
            versions.put(s, e == null ? 0L : e.version());
        }
        Erp.Snapshot erpData = data(Source.ERP, new Erp.Snapshot(List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        Planning.Snapshot planData = data(Source.PLANNING, new Planning.Snapshot(List.of(), List.of(), List.of(), List.of(), List.of(), 0));
        Crm.Snapshot crmData = data(Source.CRM, new Crm.Snapshot(List.of(), List.of(), List.of(), List.of()));
        List<Mail.Message> inbox = data(Source.MAILBOX, List.of());
        return new CentralView(clock.now(), clock.today(), erpData, planData, crmData, inbox, fr, versions);
    }

    @SuppressWarnings("unchecked")
    private <T> T data(Source s, T fallback) {
        Entry e = entries.get(s);
        return e == null || e.data() == null ? fallback : (T) e.data();
    }

    public synchronized Freshness freshness(Source s) {
        Policy p = policy.get();
        long sla = p.sla(s).toMinutes();
        if (s == Source.STATUS_FEED) {
            if (statusFeedContact == null) {
                return new Freshness(s, null, 0, sla, Freshness.FRESH, "read on demand per customer");
            }
            long age = Duration.between(statusFeedContact, clock.now()).toMinutes();
            return new Freshness(s, statusFeedContact, age, sla, Freshness.FRESH, "read on demand per customer");
        }
        Entry e = entries.get(s);
        if (e == null || e.sourceAsOf() == null) {
            return new Freshness(s, null, -1, sla, Freshness.UNAVAILABLE,
                    e == null ? "never fetched" : "fetch failed: " + e.lastError());
        }
        long age = Math.max(0, Duration.between(e.sourceAsOf(), clock.now()).toMinutes());
        Duration limit = p.commitLimit(s);
        String status;
        if (limit != null && age > limit.toMinutes()) {
            status = Freshness.TOO_OLD_TO_COMMIT;
        } else if (age > sla) {
            status = Freshness.STALE;
        } else {
            status = Freshness.FRESH;
        }
        String note = null;
        if (e.lastError() != null && (e.fetchedAt() == null || e.lastErrorAt().isAfter(e.fetchedAt()))) {
            note = "last refresh failed at " + e.lastErrorAt().toLocalTime().withNano(0) + " (" + e.lastError()
                    + "); showing the copy fetched at " + e.fetchedAt().toLocalTime().withNano(0);
        } else if (Duration.between(e.fetchedAt(), e.sourceAsOf()).abs().toMinutes() > 1) {
            note = "source reports its own data as of " + e.sourceAsOf().toLocalTime().withNano(0);
        }
        return new Freshness(s, e.sourceAsOf(), age, sla, status, note);
    }
}
