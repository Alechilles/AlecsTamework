package com.alechilles.alecstamework.persistence.operation;

import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Idempotent external cleanup required after canonical commit and before publication.
 *
 * <p>The durable operation remains the sole recovery authority. An incomplete cleanup never
 * rolls back canonical state or creates another journal; recovery re-enters this boundary from
 * the shared {@link OperationPhase#DURABLE} phase.</p>
 */
@FunctionalInterface
public interface DurableOperationCleanupBoundary<T> {

    @Nonnull
    CompletionStage<LiveOperationResult> cleanupAfterDurable(
            @Nonnull T payload,
            @Nonnull OperationEnvelope durableOperation
    );

    /** Returns the default boundary for operations with no external durable cleanup. */
    @Nonnull
    static <T> DurableOperationCleanupBoundary<T> notRequired() {
        return (payload, operation) -> LiveOperationResult.confirmed(
                "durable_cleanup_not_required"
        ).completed();
    }
}
