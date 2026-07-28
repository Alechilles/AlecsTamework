package com.alechilles.alecstamework.persistence.projection;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Explicit canonical rebuild comparison result. */
public record ProjectionRebuildResult(@Nonnull Status status, @Nullable Throwable failure) {
    public ProjectionRebuildResult {
        if (status == null || ((status == Status.FAILED) != (failure != null))) {
            throw new IllegalArgumentException("Projection rebuild result is inconsistent");
        }
    }

    public enum Status {
        EQUIVALENT,
        MISMATCH,
        FAILED
    }
}
