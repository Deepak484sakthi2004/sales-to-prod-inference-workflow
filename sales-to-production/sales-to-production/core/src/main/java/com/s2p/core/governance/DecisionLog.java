package com.s2p.core.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.s2p.contracts.RelativeJson;
import com.s2p.contracts.SimClock;
import com.s2p.core.persistence.CoreStore;
import com.s2p.core.persistence.StoreCollection;
import com.s2p.core.persistence.memory.InMemoryCoreStore;
import com.s2p.core.state.CentralView;
import com.s2p.core.state.Source;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The system's memory of its own decisions. Seeded with history and appended to as it works. Records live in the
 * configured {@link CoreStore} (in memory by default, MongoDB when configured) and are optionally mirrored to a JSON
 * lines file. Seeding is idempotent, so a core restarted against MongoDB does not duplicate its history.
 */
public final class DecisionLog {
    public record Seed(List<DecisionRecord> decisions) {
    }

    private static final long FIRST_ID = 500;

    private final CoreStore store;
    private final StoreCollection<DecisionRecord> records;
    private final Path file;
    private final SimClock clock;
    private final ObjectMapper mapper = RelativeJson.mapper();

    public DecisionLog(Path file, SimClock clock) {
        this(new InMemoryCoreStore(), file, clock);
    }

    public DecisionLog(CoreStore store, Path file, SimClock clock) {
        this.store = store;
        this.records = store.collection("decisions", DecisionRecord.class);
        this.file = file;
        this.clock = clock;
    }

    /** Records a decision together with the version and age of every source in the view it was based on. */
    public DecisionRecord record(String kind, String actor, String accountId, String orderId, String quotationId,
                                 String statement, LocalDate dateStated, Integer qty, List<String> basis,
                                 CentralView view, Double confidence, String ruleId, String approvedBy,
                                 String actionId, String policyVersion) {
        Map<String, Long> versions = new LinkedHashMap<>();
        Map<String, String> ages = new LinkedHashMap<>();
        if (view != null) {
            for (Source s : List.of(Source.ERP, Source.PLANNING, Source.CRM, Source.MAILBOX)) {
                versions.put(s.name(), view.version(s));
                ages.put(s.name(), view.freshness(s).ageMinutes() + " min");
            }
        }
        return append(new DecisionRecord(nextId(), clock.now(), kind, actor, accountId, orderId, quotationId, statement,
                dateStated, qty, basis, versions, ages, confidence, ruleId, approvedBy, actionId, policyVersion, null));
    }

    public void seed(LocalDate anchor) {
        Seed s = new RelativeJson(anchor).load("core-seed/decision-history.json", Seed.class);
        s.decisions().forEach(r -> records.insertIfAbsent(r.id(), r, Map.of()));
    }

    public String nextId() {
        return String.format("DEC-%04d", FIRST_ID - 1 + store.nextSequence("decision"));
    }

    public synchronized DecisionRecord append(DecisionRecord r) {
        records.insertIfAbsent(r.id(), r, Map.of());
        if (file != null) {
            try {
                Files.createDirectories(file.toAbsolutePath().getParent());
                Files.writeString(file, mapper.writeValueAsString(r) + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return r;
    }

    public List<DecisionRecord> find(Predicate<DecisionRecord> p) {
        return records.all().stream().filter(p).toList();
    }

    public List<DecisionRecord> all() {
        return records.all();
    }

    public List<DecisionRecord> recent(int n) {
        return records.latest(n);
    }
}
