package com.alechilles.alecstamework.persistence.kernel;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Exact outcome of one replacement persistence transaction.
 *
 * <p>An unknown result requires operation-specific readback and is never automatically retried.</p>
 *
 * @param <T> committed value type
 */
public sealed interface PersistenceTransactionResult<T>
        permits PersistenceTransactionResult.Committed,
        PersistenceTransactionResult.RolledBack,
        PersistenceTransactionResult.Unknown,
        PersistenceTransactionResult.Rejected {

    /** Known committed transaction. */
    record Committed<T>(@Nullable T value) implements PersistenceTransactionResult<T> {
    }

    /** Known rolled-back transaction that produced no durable database effect. */
    record RolledBack<T>(@Nonnull StorageFailure failure) implements PersistenceTransactionResult<T> {
        public RolledBack {
            if (failure == null) {
                throw new IllegalArgumentException("Rolled-back transaction requires failure details");
            }
        }
    }

    /** Transaction whose commit outcome cannot be proven without exact readback. */
    record Unknown<T>(@Nonnull StorageFailure failure) implements PersistenceTransactionResult<T> {
        public Unknown {
            if (failure == null) {
                throw new IllegalArgumentException("Unknown transaction requires failure details");
            }
        }
    }

    /** Operation rejected before writer acceptance and therefore never started. */
    record Rejected<T>(@Nonnull PersistenceWriteRejection reason)
            implements PersistenceTransactionResult<T> {
        public Rejected {
            if (reason == null) {
                throw new IllegalArgumentException("Rejected transaction requires a reason");
            }
        }
    }
}
