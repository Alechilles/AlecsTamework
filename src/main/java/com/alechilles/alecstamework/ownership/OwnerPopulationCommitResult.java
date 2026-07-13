package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.persistence.sqlite.PopulationPersistenceTransition;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Outcome after live mutation and durable owner-population finalization.
 */
public record OwnerPopulationCommitResult(@Nonnull Status status,
                                          @Nonnull String reason,
                                          @Nullable PopulationPersistenceTransition.Result persistenceResult) {
    public enum Status {
        COMMITTED,
        SOURCE_FINALIZATION_PENDING,
        INVALID_CAPABILITY,
        INDEX_COMMIT_FAILED,
        DURABLE_CONFLICT,
        PERSISTENCE_DEGRADED
    }

    public OwnerPopulationCommitResult {
        if (status == null) {
            throw new IllegalArgumentException("Commit status is required.");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Commit reason is required.");
        }
    }

    public boolean committed() {
        return status == Status.COMMITTED || status == Status.SOURCE_FINALIZATION_PENDING;
    }

    public boolean sourceFinalizationPending() {
        return status == Status.SOURCE_FINALIZATION_PENDING;
    }
}
