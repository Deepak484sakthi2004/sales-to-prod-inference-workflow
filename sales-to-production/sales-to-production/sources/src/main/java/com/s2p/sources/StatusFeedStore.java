package com.s2p.sources;

import com.s2p.contracts.RelativeJson;
import com.s2p.contracts.SimClock;
import com.s2p.contracts.SourceResponse;
import com.s2p.contracts.Status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Customer status feed. It holds only customer-safe fields and accepts an update only if its sequence is newer than
 * the one it already shows, so a late or replayed update can never overwrite a newer status.
 */
public final class StatusFeedStore {
    public record Seed(List<Status.CustomerStatus> statuses, List<Status.Subscriber> subscribers) {
    }

    private final SimClock clock;
    private final LocalDate anchor;
    private final AtomicLong version = new AtomicLong();
    private final Map<String, Status.CustomerStatus> current = new LinkedHashMap<>();
    private final List<Status.CustomerStatus> history = new ArrayList<>();
    private final Map<String, String> tokens = new HashMap<>();

    public StatusFeedStore(SimClock clock, LocalDate anchor) {
        this.clock = clock;
        this.anchor = anchor;
        reset();
    }

    public synchronized void reset() {
        Seed seed = new RelativeJson(anchor).load("seed/statusfeed.json", Seed.class);
        current.clear();
        history.clear();
        tokens.clear();
        seed.statuses().forEach(s -> current.put(key(s.accountId(), s.orderRef()), s));
        history.addAll(seed.statuses());
        seed.subscribers().forEach(s -> tokens.put(s.token(), s.accountId()));
        version.set(80);
    }

    public synchronized Status.PublishResult publish(Status.CustomerStatus s) {
        if (s.accountId() == null || s.orderRef() == null) {
            throw new IllegalArgumentException("Status needs an account and an order reference");
        }
        Status.CustomerStatus cur = current.get(key(s.accountId(), s.orderRef()));
        if (cur != null && s.sequence() <= cur.sequence()) {
            return new Status.PublishResult(false, "Rejected: sequence " + s.sequence()
                    + " is not newer than the published sequence " + cur.sequence(), cur);
        }
        Status.CustomerStatus stored = new Status.CustomerStatus(s.accountId(), s.orderRef(), s.customerPo(),
                s.stage(), s.promisedDate(), s.currentEstimate(), s.message(), s.basisAsOf(), s.sequence(),
                clock.now(), s.decisionId());
        current.put(key(s.accountId(), s.orderRef()), stored);
        history.add(stored);
        version.incrementAndGet();
        return new Status.PublishResult(true, "Published", stored);
    }

    public synchronized SourceResponse<List<Status.CustomerStatus>> statuses(String accountId) {
        return new SourceResponse<>("statusfeed", version.get(), clock.now(),
                current.values().stream().filter(s -> s.accountId().equals(accountId)).toList());
    }

    public synchronized Optional<String> accountForToken(String token) {
        return Optional.ofNullable(tokens.get(token));
    }

    private static String key(String account, String order) {
        return account + "|" + order;
    }
}
