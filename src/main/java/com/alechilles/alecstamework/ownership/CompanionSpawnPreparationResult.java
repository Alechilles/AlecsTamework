package com.alechilles.alecstamework.ownership;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Outcome of planning and durably preparing a pre-spawn population batch. */
public record CompanionSpawnPreparationResult(
        boolean allowed,
        @Nonnull String reason,
        int requestedCount,
        int admittedCount,
        @Nullable CompanionPopulationPreparationResult limitingDecision,
        @Nullable PreparedCompanionSpawnBatch preparedBatch
) {
}
