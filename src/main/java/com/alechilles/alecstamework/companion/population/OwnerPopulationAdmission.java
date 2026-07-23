package com.alechilles.alecstamework.companion.population;

import javax.annotation.Nonnull;

/**
 * Exact canonical and pending headroom observed while reserving one capacity bucket.
 */
public record OwnerPopulationAdmission(
        @Nonnull Status status,
        @Nonnull OwnerPopulationReservation reservation,
        long committedCount,
        long pendingCount
) {
    public OwnerPopulationAdmission {
        if (status == null || reservation == null
                || committedCount < 0 || pendingCount < 0) {
            throw new IllegalArgumentException(
                    "Complete non-negative population admission evidence is required"
            );
        }
    }

    /** Returns whether this transaction owns the requested reservation. */
    public boolean admitted() {
        return status == Status.ADMITTED;
    }

    /** Stable admission outcomes without feature-specific operation phases. */
    public enum Status {
        ADMITTED,
        CAPACITY_REACHED,
        CONFLICT
    }
}
