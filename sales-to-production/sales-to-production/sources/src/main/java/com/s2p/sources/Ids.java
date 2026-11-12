package com.s2p.sources;

import java.util.Collection;

final class Ids {
    private Ids() {
    }

    /** Next numeric suffix after the highest seeded id with this prefix, so generated ids never collide with seed data. */
    static int next(Collection<String> ids, String prefix, int floor) {
        int max = floor;
        for (String id : ids) {
            if (id != null && id.startsWith(prefix)) {
                try {
                    max = Math.max(max, Integer.parseInt(id.substring(prefix.length())));
                } catch (NumberFormatException ignored) {
                    // non-numeric ids are left alone
                }
            }
        }
        return max + 1;
    }
}
