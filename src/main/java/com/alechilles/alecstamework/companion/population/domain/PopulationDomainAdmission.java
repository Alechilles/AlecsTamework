package com.alechilles.alecstamework.companion.population.domain;

import javax.annotation.Nonnull;

/** Exact result of one named-domain reservation attempt. */
public record PopulationDomainAdmission(
        @Nonnull Status status,
        @Nonnull PopulationDomainReservation reservation,
        @Nonnull PopulationDomainCounts counts
) {
    public PopulationDomainAdmission {
        if (status == null || reservation == null || counts == null) {
            throw new IllegalArgumentException("Complete domain admission result is required");
        }
    }

    public boolean admitted() {
        return status == Status.ADMITTED;
    }

    public enum Status {
        ADMITTED,
        OWNED_CAPACITY_REACHED,
        DEPLOYABLE_CAPACITY_REACHED,
        CONFLICT
    }
}
