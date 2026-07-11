package com.alechilles.alecstamework.ownership;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Combined nearby, owner, and claim preparation result for one exact planned litter. */
public record BreedingPopulationPreparationResult(
        boolean allowed,
        @Nonnull String reason,
        int requestedCount,
        int admittedCount,
        @Nullable CompanionPopulationBatchPreparationResult populationResult,
        @Nullable PreparedBreedingPopulationBatch preparedBatch
) {
}
