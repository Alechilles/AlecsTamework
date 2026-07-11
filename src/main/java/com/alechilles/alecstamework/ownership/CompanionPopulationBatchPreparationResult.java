package com.alechilles.alecstamework.ownership;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable result for an exact or capacity-clamped population batch preparation. */
public record CompanionPopulationBatchPreparationResult(
        boolean allowed,
        @Nonnull String reason,
        int requestedCount,
        int admittedCount,
        @Nullable CompanionPopulationPreparationResult limitingDecision,
        @Nullable PreparedCompanionPopulationBatch preparedBatch
) {
    public CompanionPopulationBatchPreparationResult {
        if (reason == null || reason.isBlank()) {
            reason = "companion-population-batch-result";
        }
        if (requestedCount <= 0 || admittedCount < 0 || admittedCount > requestedCount) {
            throw new IllegalArgumentException("Invalid requested/admitted batch counts.");
        }
        if (allowed != (preparedBatch != null && admittedCount > 0)) {
            throw new IllegalArgumentException("Allowed batch results require a non-empty capability.");
        }
        if (preparedBatch != null
                && (preparedBatch.requestedCount() != requestedCount
                || preparedBatch.admittedCount() != admittedCount)) {
            throw new IllegalArgumentException("Prepared batch counts do not match the result.");
        }
    }
}
