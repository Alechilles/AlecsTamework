package com.alechilles.alecstamework.ownership;

import java.util.Collection;
import java.util.Map;

/** Lock-confined count-map operations shared by the owner population index. */
final class OwnerPopulationCountOps {
    private OwnerPopulationCountOps() {
    }

    static void addCounts(Map<OwnerPopulationScopeKey, Long> counts,
                          Collection<OwnerPopulationScopeKey> keys) {
        for (OwnerPopulationScopeKey key : keys) {
            counts.merge(key, 1L, Long::sum);
        }
    }

    static void removeCounts(Map<OwnerPopulationScopeKey, Long> counts,
                             Collection<OwnerPopulationScopeKey> keys) {
        for (OwnerPopulationScopeKey key : keys) {
            long updated = count(counts, key) - 1L;
            if (updated < 0L) {
                throw new IllegalStateException("Owner population count underflow for " + key);
            }
            if (updated == 0L) {
                counts.remove(key);
            } else {
                counts.put(key, updated);
            }
        }
    }

    static long count(Map<OwnerPopulationScopeKey, Long> counts, OwnerPopulationScopeKey key) {
        return key == null ? 0L : counts.getOrDefault(key, 0L);
    }
}
