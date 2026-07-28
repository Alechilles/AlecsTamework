package com.alechilles.alecstamework.persistence.kernel;

import com.alechilles.alecstamework.persistence.operation.OperationId;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Testable observer/fault hook for exact replacement transaction boundaries. */
@FunctionalInterface
public interface PersistenceCheckpointHook {
    PersistenceCheckpointHook NO_OP = (checkpoint, operationId) -> {
    };

    /**
     * Observes a boundary and may throw to simulate a process/driver failure.
     *
     * @param checkpoint transaction boundary
     * @param operationId operation being executed, or null for kernel-wide close
     */
    void hit(@Nonnull PersistenceCheckpoint checkpoint, @Nullable OperationId operationId) throws Exception;
}
