package com.alechilles.alecstamework.companion.population.group;

/** Canonical committed counts plus positive nonterminal reservations. */
public record PopulationGroupCounts(
        long committedOwned,
        long committedActive,
        long pendingOwned,
        long pendingActive
) {
    public PopulationGroupCounts {
        if (committedOwned < 0 || committedActive < 0
                || pendingOwned < 0 || pendingActive < 0
                || committedActive > committedOwned) {
            throw new IllegalArgumentException(
                    "Population group counts must be non-negative subsets"
            );
        }
    }
}
