package com.s2p.core.persistence;

import com.s2p.core.persistence.memory.InMemoryCoreStore;
import com.s2p.core.persistence.mongo.MongoCoreStore;

/** Picks the store from {@code s2p.store.type}: memory (default) or mongo (MongoDB Atlas or any MongoDB 5+). */
public final class CoreStoreFactory {
    private CoreStoreFactory() {
    }

    public static CoreStore create(String type, String mongoUri, String mongoDatabase) {
        if (type == null || type.isBlank() || type.equalsIgnoreCase("memory")) {
            return new InMemoryCoreStore();
        }
        if (!type.equalsIgnoreCase("mongo")) {
            throw new IllegalArgumentException("Unknown s2p.store.type '" + type + "': use memory or mongo");
        }
        if (mongoUri == null || mongoUri.isBlank()) {
            throw new IllegalArgumentException("s2p.store.type=mongo needs MONGODB_URI, for example "
                    + "mongodb+srv://user:password@cluster0.abcde.mongodb.net/");
        }
        return new MongoCoreStore(mongoUri, mongoDatabase == null || mongoDatabase.isBlank() ? "s2p" : mongoDatabase);
    }
}
