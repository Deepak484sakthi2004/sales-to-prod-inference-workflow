package com.s2p.core.persistence.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.s2p.core.persistence.CoreStore;
import com.s2p.core.persistence.StoreCodec;
import com.s2p.core.persistence.StoreCollection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The default store: everything lives in this process and is gone when it stops. Nothing to install, fastest for
 * development, tests and the review. With {@code verifyRoundTrip} every record is serialised and read back exactly as
 * a database would do it, so a type that cannot survive MongoDB fails here first.
 */
public final class InMemoryCoreStore implements CoreStore {
    private final boolean roundTrip;
    private final Map<String, Coll<?>> collections = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();

    public InMemoryCoreStore() {
        this(false);
    }

    public InMemoryCoreStore(boolean verifyRoundTrip) {
        this.roundTrip = verifyRoundTrip;
    }

    @Override
    public <T> StoreCollection<T> collection(String name, Class<T> type, String... indexedFields) {
        return open(name, StoreCodec.mapper().getTypeFactory().constructType(type), indexedFields);
    }

    @Override
    public <T> StoreCollection<T> collection(String name, TypeReference<T> type, String... indexedFields) {
        return open(name, StoreCodec.mapper().getTypeFactory().constructType(type), indexedFields);
    }

    @SuppressWarnings("unchecked")
    private <T> StoreCollection<T> open(String name, JavaType type, String[] fields) {
        return (StoreCollection<T>) collections.computeIfAbsent(name, n -> new Coll<T>(n, type, Set.of(fields), roundTrip));
    }

    @Override
    public long nextSequence(String name) {
        return sequences.computeIfAbsent(name, n -> new AtomicLong()).incrementAndGet();
    }

    @Override
    public String kind() {
        return "memory";
    }

    @Override
    public String describe() {
        return "in-memory store (default): decision log, actions, suggestions and enquiry ledger are lost when the core "
                + "stops" + (roundTrip ? "; every record round-tripped through the database codec" : "");
    }

    @Override
    public void close() {
    }

    private static final class Coll<T> implements StoreCollection<T> {
        private record Row(Object stored, Map<String, String> index) {
        }

        private final String name;
        private final JavaType type;
        private final Set<String> fields;
        private final boolean roundTrip;
        private final LinkedHashMap<String, Row> rows = new LinkedHashMap<>();

        Coll(String name, JavaType type, Set<String> fields, boolean roundTrip) {
            this.name = name;
            this.type = type;
            this.fields = fields;
            this.roundTrip = roundTrip;
        }

        @Override
        public synchronized boolean insertIfAbsent(String id, T value, Map<String, String> index) {
            if (rows.containsKey(id)) {
                return false;
            }
            rows.put(id, row(value, index));
            return true;
        }

        @Override
        public synchronized void upsert(String id, T value, Map<String, String> index) {
            rows.put(id, row(value, index));
        }

        @Override
        public synchronized boolean replaceIf(String id, T value, Map<String, String> index, String field, String expected) {
            checkField(field);
            Row r = rows.get(id);
            if (r == null || !expected.equals(r.index().get(field))) {
                return false;
            }
            rows.put(id, row(value, index));
            return true;
        }

        @Override
        public synchronized Optional<T> get(String id) {
            Row r = rows.get(id);
            return r == null ? Optional.empty() : Optional.of(load(r));
        }

        @Override
        public synchronized boolean exists(String id) {
            return rows.containsKey(id);
        }

        @Override
        public synchronized List<T> all() {
            return rows.values().stream().map(this::load).toList();
        }

        @Override
        public synchronized List<T> findBy(String field, String value) {
            checkField(field);
            return rows.values().stream().filter(r -> value.equals(r.index().get(field))).map(this::load).toList();
        }

        @Override
        public synchronized List<T> latest(int n) {
            List<T> out = new ArrayList<>();
            ListIterator<Row> it = new ArrayList<>(rows.values()).listIterator(rows.size());
            while (it.hasPrevious() && out.size() < n) {
                out.add(load(it.previous()));
            }
            return out;
        }

        @Override
        public synchronized boolean delete(String id) {
            return rows.remove(id) != null;
        }

        @Override
        public synchronized long deleteByPrefix(String field, String prefix) {
            checkField(field);
            int before = rows.size();
            rows.values().removeIf(r -> r.index().getOrDefault(field, "").startsWith(prefix));
            return before - rows.size();
        }

        private Row row(T value, Map<String, String> index) {
            Map<String, String> idx = index == null ? Map.of() : Map.copyOf(index);
            idx.keySet().forEach(this::checkField);
            return new Row(roundTrip ? StoreCodec.write(value) : value, idx);
        }

        @SuppressWarnings("unchecked")
        private T load(Row r) {
            return roundTrip ? StoreCodec.read((String) r.stored(), type) : (T) r.stored();
        }

        private void checkField(String f) {
            if (!fields.contains(f)) {
                throw new IllegalArgumentException("Field '" + f + "' is not indexed on collection " + name
                        + " (declared: " + fields + ")");
            }
        }
    }
}
