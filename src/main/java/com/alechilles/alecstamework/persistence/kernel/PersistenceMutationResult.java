package com.alechilles.alecstamework.persistence.kernel;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Typed result of a transaction-local persistence mutation.
 *
 * @param status exact mutation outcome
 * @param value resulting immutable row when the mutation applied
 * @param <T> immutable row type
 */
public record PersistenceMutationResult<T>(@Nonnull PersistenceMutationStatus status,
                                           @Nullable T value) {
    public PersistenceMutationResult {
        if (status == null) {
            throw new IllegalArgumentException("Mutation status is required");
        }
        if ((status == PersistenceMutationStatus.APPLIED) != (value != null)) {
            throw new IllegalArgumentException("Only applied mutations carry a resulting value");
        }
    }

    /** Creates a successful mutation result. */
    @Nonnull
    public static <T> PersistenceMutationResult<T> applied(@Nonnull T value) {
        if (value == null) {
            throw new IllegalArgumentException("Applied mutation value is required");
        }
        return new PersistenceMutationResult<>(PersistenceMutationStatus.APPLIED, value);
    }

    /** Creates a rejected mutation result without fabricating a row. */
    @Nonnull
    public static <T> PersistenceMutationResult<T> rejected(
            @Nonnull PersistenceMutationStatus status
    ) {
        if (status == null || status == PersistenceMutationStatus.APPLIED) {
            throw new IllegalArgumentException("Rejected mutation status is required");
        }
        return new PersistenceMutationResult<>(status, null);
    }

    /** Returns whether the mutation applied. */
    public boolean applied() {
        return status == PersistenceMutationStatus.APPLIED;
    }
}
