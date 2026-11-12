package com.s2p.core.persistence;

import com.fasterxml.jackson.core.type.TypeReference;

/**
 * Where the intelligence layer keeps its own facts: the decision log, the action queue, the suggestion board and the
 * enquiry ledger (which mail was read, what it produced, duplicate fingerprints). Business data is never stored here;
 * the systems of record stay the truth for that.
 *
 * Two implementations: {@code memory} (the default, nothing to install) and {@code mongo} (MongoDB Atlas or any
 * MongoDB 5+ server), chosen by {@link CoreStoreFactory}.
 */
public interface CoreStore extends AutoCloseable {

    /** A named collection of one record type. {@code indexedFields} are the only fields {@link StoreCollection#findBy} may use. */
    <T> StoreCollection<T> collection(String name, Class<T> type, String... indexedFields);

    <T> StoreCollection<T> collection(String name, TypeReference<T> type, String... indexedFields);

    /** Atomic counter shared by every instance using this store: 1, 2, 3, ... */
    long nextSequence(String name);

    /** "memory" or "mongo". */
    String kind();

    /** One line for the system's report on itself, without credentials. */
    String describe();

    @Override
    void close();
}
