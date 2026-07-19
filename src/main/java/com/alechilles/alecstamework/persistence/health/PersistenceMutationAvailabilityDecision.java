package com.alechilles.alecstamework.persistence.health;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Stable availability result with a player/admin reason and optional incident reference. */
public record PersistenceMutationAvailabilityDecision(
        @Nonnull PersistenceMutationAvailabilityStatus status,
        @Nonnull String reasonCode,
        @Nullable String incidentId) {
    public boolean allowed() {
        return status == PersistenceMutationAvailabilityStatus.ALLOW;
    }
}
