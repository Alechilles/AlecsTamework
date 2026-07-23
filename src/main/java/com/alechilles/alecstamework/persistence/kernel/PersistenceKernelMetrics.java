package com.alechilles.alecstamework.persistence.kernel;

import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import javax.annotation.Nonnull;

/** Passive metrics hooks for replacement persistence without coupling to a metrics backend. */
public interface PersistenceKernelMetrics {
    PersistenceKernelMetrics NO_OP = new PersistenceKernelMetrics() {
    };

    default void writeAccepted(@Nonnull OperationId operationId) {
    }

    default void writeAccepted(
            @Nonnull OperationId operationId,
            @Nonnull OperationKind operationKind
    ) {
        writeAccepted(operationId);
    }

    default void writeRejected(@Nonnull OperationId operationId,
                               @Nonnull PersistenceWriteRejection reason) {
    }

    default void writeRejected(
            @Nonnull OperationId operationId,
            @Nonnull OperationKind operationKind,
            @Nonnull PersistenceWriteRejection reason
    ) {
        writeRejected(operationId, reason);
    }

    default void busyRetry(@Nonnull OperationId operationId, int retryNumber) {
    }

    default void busyRetry(
            @Nonnull OperationId operationId,
            @Nonnull OperationKind operationKind,
            int retryNumber
    ) {
        busyRetry(operationId, retryNumber);
    }

    default void writeCompleted(@Nonnull OperationId operationId,
                                @Nonnull PersistenceTransactionResult<?> result) {
    }

    default void writeCompleted(
            @Nonnull OperationId operationId,
            @Nonnull OperationKind operationKind,
            @Nonnull PersistenceTransactionResult<?> result
    ) {
        writeCompleted(operationId, result);
    }

    default void unitOfWorkCompleted(
            @Nonnull OperationKind operationKind,
            @Nonnull PersistenceTransactionResult<?> result
    ) {
    }

    default void readCompleted(
            @Nonnull PersistenceReadKind readKind,
            @Nonnull PersistenceReadResult<?> result
    ) {
    }

    default void writeTimed(
            @Nonnull OperationKind operationKind,
            int acceptedQueueDepth,
            long queueWaitNanos,
            long executionNanos
    ) {
    }

    default void readTimed(
            @Nonnull PersistenceReadKind readKind,
            @Nonnull PersistenceReadPriority priority,
            int acceptedQueueDepth,
            long queueWaitNanos,
            long executionNanos
    ) {
    }

    default void checkpointCompleted(
            int logFrames,
            int checkpointedFrames
    ) {
    }

    default void shutdownCompleted(
            long elapsedNanos,
            int outstandingOperations
    ) {
    }

    default void checkpointFailure(@Nonnull String checkpoint,
                                   @Nonnull Throwable failure) {
    }

    default void shutdownTimedOut(int outstandingOperations) {
    }
}
