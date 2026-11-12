package com.s2p.core;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.s2p.core.persistence.CoreStore;
import com.s2p.core.persistence.StoreCollection;
import com.s2p.core.persistence.mongo.MongoCoreStore;
import com.fasterxml.jackson.core.type.TypeReference;

/** Opens a MongoDB store on a database of its own and drops that database when the store is closed. */
final class MongoTestSupport {
    private MongoTestSupport() {
    }

    static CoreStore open(String uri) {
        String db = "s2p_test_" + Long.toHexString(System.nanoTime());
        return dropping(uri, db, new MongoCoreStore(uri, db));
    }

    /** Two stores on one database, as two core instances or a restart would see it. */
    static CoreStore openShared(String uri, String db) {
        return new MongoCoreStore(uri, db);
    }

    static void drop(String uri, String db) {
        MongoClient c = MongoClients.create(uri);
        try {
            c.getDatabase(db).drop();
        } finally {
            c.close();
        }
    }

    private static CoreStore dropping(String uri, String db, CoreStore inner) {
        return new CoreStore() {
            public <T> StoreCollection<T> collection(String name, Class<T> type, String... f) {
                return inner.collection(name, type, f);
            }

            public <T> StoreCollection<T> collection(String name, TypeReference<T> type, String... f) {
                return inner.collection(name, type, f);
            }

            public long nextSequence(String name) {
                return inner.nextSequence(name);
            }

            public String kind() {
                return inner.kind();
            }

            public String describe() {
                return inner.describe();
            }

            public void close() {
                inner.close();
                drop(uri, db);
            }
        };
    }
}
