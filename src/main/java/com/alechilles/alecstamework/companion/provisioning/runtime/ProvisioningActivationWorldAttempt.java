package com.alechilles.alecstamework.companion.provisioning.runtime;

import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Exact world evidence and durability ports for one provisioning activation.
 *
 * <p>An {@code EXACT} projection means the target alias, profile, spawn
 * receipt, and frozen placement all match the immutable activation request.
 * The executor owns ordering; platform gateways only observe, apply, save, or
 * resume the requested step.</p>
 */
interface ProvisioningActivationWorldAttempt {

    /** Observes the exact target projection without mutating world state. */
    @Nonnull
    ProjectionProbe probe();

    /** Observes the exact projection in the chunk reported by durable save. */
    @Nonnull
    ProjectionProbe probeInTargetChunk(long expectedChunkIndex);

    /** Idempotently inserts or resolves the exact requested projection. */
    @Nonnull
    ProjectionAttempt applyOrResolveExactProjection();

    /** Force-saves the chunk that owns the exact target projection. */
    @Nonnull
    CompletionStage<ChunkPersistence> persistTargetChunk(long chunkIndex);

    /** Re-enters the target world thread before invoking the continuation. */
    @Nonnull
    CompletionStage<LiveOperationResult> resumeOnWorldThread(
            @Nonnull Supplier<CompletionStage<LiveOperationResult>>
                    continuation
    );

    enum ProjectionStatus {
        EXACT,
        ABSENT,
        UNAVAILABLE,
        CONFLICT
    }

    record ProjectionProbe(
            @Nonnull ProjectionStatus status,
            @Nullable Long chunkIndex,
            @Nullable Throwable cause
    ) {
        public ProjectionProbe {
            if (status == null
                    || (status == ProjectionStatus.EXACT)
                    != (chunkIndex != null)) {
                throw new IllegalArgumentException(
                        "Provisioning projection probe is inconsistent"
                );
            }
        }

        static ProjectionProbe exact(long chunkIndex) {
            return new ProjectionProbe(
                    ProjectionStatus.EXACT, chunkIndex, null
            );
        }

        static ProjectionProbe absent() {
            return new ProjectionProbe(
                    ProjectionStatus.ABSENT, null, null
            );
        }

        static ProjectionProbe unavailable(@Nullable Throwable cause) {
            return new ProjectionProbe(
                    ProjectionStatus.UNAVAILABLE, null, cause
            );
        }

        static ProjectionProbe conflict(@Nullable Throwable cause) {
            return new ProjectionProbe(
                    ProjectionStatus.CONFLICT, null, cause
            );
        }
    }

    enum ProjectionAttemptStatus {
        EXACT,
        UNCHANGED,
        RETRYABLE,
        CONFLICT
    }

    record ProjectionAttempt(
            @Nonnull ProjectionAttemptStatus status,
            @Nullable Long chunkIndex,
            @Nullable Throwable cause
    ) {
        public ProjectionAttempt {
            if (status == null
                    || (status == ProjectionAttemptStatus.EXACT)
                    != (chunkIndex != null)) {
                throw new IllegalArgumentException(
                        "Provisioning projection attempt is inconsistent"
                );
            }
        }

        static ProjectionAttempt exact(long chunkIndex) {
            return new ProjectionAttempt(
                    ProjectionAttemptStatus.EXACT, chunkIndex, null
            );
        }

        static ProjectionAttempt unchanged(@Nullable Throwable cause) {
            return new ProjectionAttempt(
                    ProjectionAttemptStatus.UNCHANGED, null, cause
            );
        }

        static ProjectionAttempt retryable(@Nullable Throwable cause) {
            return new ProjectionAttempt(
                    ProjectionAttemptStatus.RETRYABLE, null, cause
            );
        }

        static ProjectionAttempt conflict(@Nullable Throwable cause) {
            return new ProjectionAttempt(
                    ProjectionAttemptStatus.CONFLICT, null, cause
            );
        }
    }

    enum PersistenceStatus {
        SAVED,
        RETRYABLE,
        CONFLICT
    }

    record ChunkPersistence(
            @Nonnull PersistenceStatus status,
            @Nullable Long chunkIndex,
            @Nullable Throwable cause
    ) {
        public ChunkPersistence {
            if (status == null
                    || (status == PersistenceStatus.SAVED)
                    != (chunkIndex != null)) {
                throw new IllegalArgumentException(
                        "Provisioning chunk persistence is inconsistent"
                );
            }
        }

        static ChunkPersistence saved(long chunkIndex) {
            return new ChunkPersistence(
                    PersistenceStatus.SAVED, chunkIndex, null
            );
        }

        static ChunkPersistence retryable(@Nullable Throwable cause) {
            return new ChunkPersistence(
                    PersistenceStatus.RETRYABLE, null, cause
            );
        }

        static ChunkPersistence conflict(@Nullable Throwable cause) {
            return new ChunkPersistence(
                    PersistenceStatus.CONFLICT, null, cause
            );
        }
    }
}
