package com.alechilles.alecstamework.persistence.operation;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Constructor-only checkpoint seam. Production composition always uses {@link #NO_OP}; test code
 * may inject a deterministic instance through package-private constructors.
 */
@FunctionalInterface
public interface PersistenceCheckpointHook {
    PersistenceCheckpointHook NO_OP = (checkpoint, metadata) -> { };

    void hit(@Nonnull PersistenceCheckpoint checkpoint,
             @Nullable PersistenceOperationMetadata metadata) throws Exception;
}
