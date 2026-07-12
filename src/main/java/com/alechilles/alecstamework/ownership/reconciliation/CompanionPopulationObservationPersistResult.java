package com.alechilles.alecstamework.ownership.reconciliation;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Outcome of one coalesced live population observation write. */
public record CompanionPopulationObservationPersistResult(
        @Nonnull Status status,
        long revision,
        @Nullable String reason
) {
    public enum Status {
        CREATED,
        COMMITTED,
        IDEMPOTENT,
        PENDING_OPERATION,
        REVISION_CONFLICT,
        IDENTITY_CONFLICT,
        FAILED
    }

    public boolean persisted() {
        return status == Status.CREATED || status == Status.COMMITTED || status == Status.IDEMPOTENT;
    }

    public boolean retryable() {
        return status == Status.PENDING_OPERATION || status == Status.REVISION_CONFLICT;
    }
}
