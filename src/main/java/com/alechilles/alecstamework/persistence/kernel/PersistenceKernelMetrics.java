package com.alechilles.alecstamework.persistence.kernel;

import com.alechilles.alecstamework.persistence.operation.OperationId;
import javax.annotation.Nonnull;

/** Passive metrics hooks for replacement persistence without coupling to a metrics backend. */
public interface PersistenceKernelMetrics {
    PersistenceKernelMetrics NO_OP = new PersistenceKernelMetrics() {
    };

    default void writeAccepted(@Nonnull OperationId operationId) {
    }

    default void writeRejected(@Nonnull OperationId operationId,
                               @Nonnull PersistenceWriteRejection reason) {
    }

    default void busyRetry(@Nonnull OperationId operationId, int retryNumber) {
    }

    default void writeCompleted(@Nonnull OperationId operationId,
                                @Nonnull PersistenceTransactionResult<?> result) {
    }

    default void checkpointFailure(@Nonnull PersistenceCheckpoint checkpoint,
                                   @Nonnull Throwable failure) {
    }

    default void shutdownTimedOut(int outstandingOperations) {
    }
}
