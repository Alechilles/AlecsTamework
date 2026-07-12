package com.alechilles.alecstamework.ownership;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Result of the asynchronous durable preparation phase.
 */
public record OwnerPopulationPreparationResult(
        boolean allowed,
        @Nonnull String reason,
        @Nonnull OwnerPopulationDecision decision,
        @Nullable PreparedOwnerPopulationAdmission preparedAdmission
) {
    public OwnerPopulationPreparationResult {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A preparation reason is required.");
        }
        if (decision == null) {
            throw new IllegalArgumentException("An owner decision is required.");
        }
        if (allowed != (preparedAdmission != null)) {
            throw new IllegalArgumentException("Only allowed preparation results expose a capability.");
        }
    }
}
