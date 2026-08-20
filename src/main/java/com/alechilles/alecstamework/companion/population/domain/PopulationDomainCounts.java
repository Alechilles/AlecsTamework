package com.alechilles.alecstamework.companion.population.domain;

/** Committed usage and positive weighted reservations for one domain bucket. */
public record PopulationDomainCounts(
        long committedOwned,
        long committedDeployable,
        long pendingOwned,
        long pendingDeployable
) {
    public PopulationDomainCounts {
        if (committedOwned < 0 || committedDeployable < 0
                || pendingOwned < 0 || pendingDeployable < 0) {
            throw new IllegalArgumentException("Domain counts must be non-negative");
        }
    }
}
