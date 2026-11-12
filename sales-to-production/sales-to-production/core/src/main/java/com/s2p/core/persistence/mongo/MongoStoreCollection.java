package com.s2p.core.persistence.mongo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndReplaceOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import com.s2p.core.persistence.StoreCodec;
import com.s2p.core.persistence.StoreCollection;
import com.s2p.core.persistence.StoreException;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.regex;

/** One core collection in MongoDB. Same semantics as the in-memory version; see {@link StoreCollection}. */
final class MongoStoreCollection<T> implements StoreCollection<T> {
    private static final JsonWriterSettings RELAXED = JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build();

    private final MongoCollection<Document> coll;
    private final JavaType type;
    private final Set<String> fields;
    private final LongSupplier seq;

    MongoStoreCollection(MongoCollection<Document> coll, JavaType type, String[] fields, LongSupplier seq) {
        this.coll = coll;
        this.type = type;
        this.fields = Set.of(fields);
        this.seq = seq;
        coll.createIndex(Indexes.ascending("seq"), new IndexOptions().name("seq"));
        for (String f : fields) {
            coll.createIndex(Indexes.ascending("idx." + f), new IndexOptions().name("idx_" + f));
        }
    }

    @Override
    public boolean insertIfAbsent(String id, T value, Map<String, String> index) {
        try {
            coll.insertOne(document(id, seq.getAsLong(), value, index));
            return true;
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                return false;
            }
            throw new StoreException("Insert into " + coll.getNamespace() + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void upsert(String id, T value, Map<String, String> index) {
        Document existing = coll.find(eq("_id", id)).projection(Projections.include("seq")).first();
        long s = existing == null ? seq.getAsLong() : ((Number) existing.get("seq")).longValue();
        coll.findOneAndReplace(eq("_id", id), document(id, s, value, index), new FindOneAndReplaceOptions().upsert(true));
    }

    @Override
    public boolean replaceIf(String id, T value, Map<String, String> index, String field, String expected) {
        checkField(field);
        Document existing = coll.find(eq("_id", id)).projection(Projections.include("seq")).first();
        if (existing == null) {
            return false;
        }
        long s = ((Number) existing.get("seq")).longValue();
        return coll.replaceOne(and(eq("_id", id), eq("idx." + field, expected)), document(id, s, value, index))
                .getMatchedCount() == 1;
    }

    @Override
    public Optional<T> get(String id) {
        return Optional.ofNullable(coll.find(eq("_id", id)).first()).map(this::value);
    }

    @Override
    public boolean exists(String id) {
        return coll.find(eq("_id", id)).projection(Projections.include("_id")).first() != null;
    }

    @Override
    public List<T> all() {
        return read(coll.find().sort(Sorts.ascending("seq")));
    }

    @Override
    public List<T> findBy(String field, String value) {
        checkField(field);
        return read(coll.find(eq("idx." + field, value)).sort(Sorts.ascending("seq")));
    }

    @Override
    public List<T> latest(int n) {
        return read(coll.find().sort(Sorts.descending("seq")).limit(n));
    }

    @Override
    public boolean delete(String id) {
        return coll.deleteOne(eq("_id", id)).getDeletedCount() == 1;
    }

    @Override
    public long deleteByPrefix(String field, String prefix) {
        checkField(field);
        return coll.deleteMany(regex("idx." + field, "^" + escape(prefix))).getDeletedCount();
    }

    private List<T> read(Iterable<Document> docs) {
        List<T> out = new ArrayList<>();
        for (Document d : docs) {
            out.add(value(d));
        }
        return out;
    }

    private Document document(String id, long s, T value, Map<String, String> index) {
        Document idx = new Document();
        if (index != null) {
            index.forEach((k, v) -> {
                checkField(k);
                idx.append(k, v);
            });
        }
        Object data = Document.parse("{\"v\":" + StoreCodec.write(value) + "}").get("v");
        return new Document("_id", id).append("seq", s).append("idx", idx).append("data", data)
                .append("updatedAt", new Date());
    }

    private T value(Document d) {
        String json = new Document("v", d.get("data")).toJson(RELAXED);
        try {
            JsonNode node = StoreCodec.mapper().readTree(json).get("v");
            return StoreCodec.mapper().treeToValue(node, type);
        } catch (JsonProcessingException e) {
            throw new StoreException("Cannot read " + coll.getNamespace() + "/" + d.get("_id") + " as "
                    + type.getRawClass().getSimpleName() + ": " + e.getOriginalMessage(), e);
        }
    }

    private void checkField(String f) {
        if (!fields.contains(f)) {
            throw new IllegalArgumentException("Field '" + f + "' is not indexed on " + coll.getNamespace()
                    + " (declared: " + fields + ")");
        }
    }

    /** Escapes every regex metacharacter so the prefix is matched literally. */
    static String escape(String s) {
        return s.replaceAll("[^A-Za-z0-9 _-]", "\\\\$0");
    }
}
