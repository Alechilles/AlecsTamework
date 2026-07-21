package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.PopulationPersistenceTransition;
import javax.annotation.Nonnull;

/** Builds denial results from failed durable population preparation outcomes. */
final class OwnerPopulationPreparationFailure {
    private OwnerPopulationPreparationFailure() {
    }

    @Nonnull
    static OwnerPopulationDecision deniedWithoutReservation(
            @Nonnull OwnerPopulationDecision original,
            @Nonnull String reason
    ) {
        return new OwnerPopulationDecision(
                false,
                reason,
                null,
                original.readiness(),
                original.limit(),
                original.committedCount(),
                original.pendingCount(),
                original.currentRevision(),
                original.positiveDelta(),
                original.forced()
        );
    }

    @Nonnull
    static String reason(
            @Nonnull PersistenceWriteQueue.WriteOutcome<PopulationPersistenceTransition.Result> outcome
    ) {
        PopulationPersistenceTransition.Result result = outcome.value();
        return result != null && result.reason() != null && !result.reason().isBlank()
                ? result.reason()
                : "owner-population-prepare-failed";
    }
}
