package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.persistence.operation.DurableOperationCleanupBoundary;
import com.alechilles.alecstamework.persistence.operation.LiveOperationBoundary;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Idempotent live entity-retirement boundary for coop capture.
 *
 * <p>Only a durably saved exact receipt plus exact source absence may return confirmed. Source
 * absence without that receipt is ambiguous evidence and must fail closed as unknown.</p>
 */
@FunctionalInterface
public interface CompanionCoopCaptureLiveBoundary
        extends LiveOperationBoundary<CompanionCoopCaptureRequest>,
        DurableOperationCleanupBoundary<CompanionCoopCaptureRequest> {

    /**
     * Performs idempotent physical receipt cleanup after durable commit and before publication.
     *
     * <p>Cleanup is not a second persistence workflow and must never roll back the durable coop
     * transition. A retryable result keeps the shared operation durable for ordinary recovery;
     * publication therefore proves cleanup. Boundaries without physical cleanup retain this
     * no-op implementation.</p>
     */
    @Nonnull
    @Override
    default CompletionStage<LiveOperationResult> cleanupAfterDurable(
            @Nonnull CompanionCoopCaptureRequest request,
            @Nonnull OperationEnvelope durableOperation
    ) {
        return LiveOperationResult.confirmed(
                "coop_capture_post_durable_cleanup_not_required"
        ).completed();
    }
}
