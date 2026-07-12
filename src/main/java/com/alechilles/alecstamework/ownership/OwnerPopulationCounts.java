package com.alechilles.alecstamework.ownership;

/** Immutable committed and pending counts for one owner and optional world. */
public record OwnerPopulationCounts(long globalCommitted,
                                    long globalPending,
                                    long worldCommitted,
                                    long worldPending) {
    public OwnerPopulationCounts {
        if (globalCommitted < 0L || globalPending < 0L
                || worldCommitted < 0L || worldPending < 0L) {
            throw new IllegalArgumentException("Owner population counts cannot be negative.");
        }
    }
}
