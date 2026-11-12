package com.s2p.core.persistence.mongo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import com.s2p.core.persistence.CoreStore;
import com.s2p.core.persistence.StoreCodec;
import com.s2p.core.persistence.StoreCollection;
import com.s2p.core.persistence.StoreException;
import org.bson.Document;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.mongodb.client.model.Filters.eq;

/**
 * MongoDB store, written for MongoDB Atlas (mongodb+srv:// connection strings) and equally usable with any MongoDB
 * 5+ server. Each collection holds documents of the form
 * {@code {_id, seq, idx: {searchable fields}, data: {the record}, updatedAt}}; {@code seq} gives a stable order and
 * comes from an atomic counter in {@code s2p_counters}, so several core instances can share one database.
 */
public final class MongoCoreStore implements CoreStore {
    static final String COUNTERS = "s2p_counters";

    private final MongoClient client;
    private final MongoDatabase db;
    private final String description;
    private final Map<String, MongoStoreCollection<?>> open = new ConcurrentHashMap<>();

    public MongoCoreStore(String uri, String database) {
        MongoClient c = null;
        try {
            c = MongoClients.create(uri);
            this.db = c.getDatabase(database);
            db.runCommand(new Document("ping", 1));
        } catch (RuntimeException e) {
            if (c != null) {
                c.close();
            }
            throw new StoreException("Cannot reach MongoDB at " + redact(uri) + ": " + e.getMessage(), e);
        }
        this.client = c;
        this.description = "MongoDB store at " + redact(uri) + ", database '" + database + "'";
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
        return (StoreCollection<T>) open.computeIfAbsent(name,
                n -> new MongoStoreCollection<T>(db.getCollection(n), type, fields, () -> nextSequence("seq:" + n)));
    }

    @Override
    public long nextSequence(String name) {
        MongoCollection<Document> counters = db.getCollection(COUNTERS);
        Document d = counters.findOneAndUpdate(eq("_id", name), Updates.inc("value", 1L),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        if (d == null) {
            throw new StoreException("Counter " + name + " was not returned by MongoDB");
        }
        return ((Number) d.get("value")).longValue();
    }

    @Override
    public String kind() {
        return "mongo";
    }

    @Override
    public String describe() {
        return description;
    }

    @Override
    public void close() {
        client.close();
    }

    /** mongodb+srv://user:secret@host/ -> mongodb+srv://user:***@host/ */
    static String redact(String uri) {
        return uri == null ? null : uri.replaceAll("(//[^:/@]+):[^@/]*@", "$1:***@");
    }
}
