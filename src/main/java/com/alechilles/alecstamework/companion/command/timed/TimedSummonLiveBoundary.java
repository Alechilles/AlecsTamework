package com.alechilles.alecstamework.companion.command.timed;

import com.alechilles.alecstamework.persistence.operation.DurableOperationCleanupBoundary;
import com.alechilles.alecstamework.persistence.operation.LiveOperationBoundary;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Idempotent world-thread spawn/store receipt boundary for timed transitions.
 *
 * <p>Implementations resolve the exact operation receipt. Entity absence alone
 * never proves either transition complete.</p>
 */
@FunctionalInterface
public interface TimedSummonLiveBoundary
        extends LiveOperationBoundary<TimedSummonTransitionRequest>,
        DurableOperationCleanupBoundary<TimedSummonTransitionRequest> {

    /**
     * Performs physical projection cleanup after the canonical transition is durable.
     *
     * <p>Production STORE boundaries remove only the exact marked source. The default preserves
     * engine-neutral and unavailable boundaries that have no physical cleanup to perform.</p>
     */
    @Nonnull
    @Override
    default CompletionStage<LiveOperationResult> cleanupAfterDurable(
            @Nonnull TimedSummonTransitionRequest request,
            @Nonnull OperationEnvelope durableOperation
    ) {
        return LiveOperationResult.confirmed(
                "timed_summon_post_durable_cleanup_not_required"
        ).completed();
    }
}
