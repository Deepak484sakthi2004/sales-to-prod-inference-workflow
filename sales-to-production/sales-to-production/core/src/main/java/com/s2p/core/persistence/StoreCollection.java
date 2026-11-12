package com.s2p.core.persistence;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The operations the core needs, and no more, so that every backend can implement them with the same semantics.
 * {@code index} holds the searchable attributes of a record (for example status or idempotency key); only fields
 * declared when the collection was opened may be used.
 */
public interface StoreCollection<T> {

    /** Inserts if no record has this id. Returns false, and changes nothing, if one already exists. */
    boolean insertIfAbsent(String id, T value, Map<String, String> index);

    /** Inserts or replaces. A replaced record keeps its original position in {@link #all()}. */
    void upsert(String id, T value, Map<String, String> index);

    /**
     * Replaces the record only if its indexed {@code field} currently equals {@code expected}. This is how two core
     * instances avoid both deciding the same pending action.
     */
    boolean replaceIf(String id, T value, Map<String, String> index, String field, String expected);

    Optional<T> get(String id);

    boolean exists(String id);

    /** Oldest first. */
    List<T> all();

    /** Oldest first. */
    List<T> findBy(String field, String value);

    /** Newest first. */
    List<T> latest(int n);

    /** Deletes one record; false if there was none. */
    boolean delete(String id);

    /** Deletes every record whose indexed {@code field} starts with {@code prefix}. */
    long deleteByPrefix(String field, String prefix);
}
