package com.alechilles.alecstamework.companion.population.group;

import javax.annotation.Nonnull;

/** Exact admission result for one group reservation. */
public record PopulationGroupAdmission(
        @Nonnull Status status,
        @Nonnull PopulationGroupReservation reservation,
        @Nonnull PopulationGroupCounts counts
) {
    public PopulationGroupAdmission {
        if (status == null || reservation == null || counts == null) {
            throw new IllegalArgumentException(
                    "Complete group admission result is required"
            );
        }
    }

    public boolean admitted() {
        return status == Status.ADMITTED;
    }

    public enum Status {
        ADMITTED,
        OWNED_CAPACITY_REACHED,
        ACTIVE_CAPACITY_REACHED,
        CONFLICT
    }
}
