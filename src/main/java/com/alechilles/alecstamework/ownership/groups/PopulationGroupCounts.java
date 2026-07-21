package com.alechilles.alecstamework.ownership.groups;

/** Committed and pending authority counts for one group bucket. */
public record PopulationGroupCounts(long committedOwned,
                                    long pendingOwned,
                                    long committedActive,
                                    long pendingActive) {
    public static final PopulationGroupCounts ZERO = new PopulationGroupCounts(0L, 0L, 0L, 0L);

    public PopulationGroupCounts {
        if (committedOwned < 0L || pendingOwned < 0L || committedActive < 0L || pendingActive < 0L) {
            throw new IllegalArgumentException("Population group counts cannot be negative.");
        }
    }
}
