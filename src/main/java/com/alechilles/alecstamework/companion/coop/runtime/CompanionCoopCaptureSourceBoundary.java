package com.alechilles.alecstamework.companion.coop.runtime;

import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureLiveBoundary;
import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureRequest;
import com.alechilles.alecstamework.companion.coop.CoopCapturedItemSourceEvidence;
import com.alechilles.alecstamework.companion.coop.CoopCaptureSourceEvidence;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Routes each coop capture source through one canonical live operation boundary.
 *
 * <p>The existing live-entity boundary remains unchanged. Captured inventory items use the
 * receipt-first exact-CAS protocol without introducing a second durable operation.</p>
 */
public final class CompanionCoopCaptureSourceBoundary
        implements CompanionCoopCaptureLiveBoundary {
    private final CompanionCoopCaptureLiveBoundary liveEntityBoundary;
    private final CompanionCoopCapturedItemAttemptFactory capturedItemAttempts;
    private final CompanionCoopCapturedItemWorldExecutor capturedItemExecutor =
            new CompanionCoopCapturedItemWorldExecutor();

    public CompanionCoopCaptureSourceBoundary(
            @Nonnull CompanionCoopCaptureLiveBoundary liveEntityBoundary,
            @Nonnull CompanionCoopCapturedItemAttemptFactory
                    capturedItemAttempts
    ) {
        if (liveEntityBoundary == null || capturedItemAttempts == null) {
            throw new IllegalArgumentException(
                    "Complete coop capture source boundaries are required"
            );
        }
        this.liveEntityBoundary = liveEntityBoundary;
        this.capturedItemAttempts = capturedItemAttempts;
    }

    @Override
    @Nonnull
    public CompletionStage<LiveOperationResult> applyOrResolve(
            @Nonnull CompanionCoopCaptureRequest request,
            @Nonnull OperationEnvelope operation
    ) throws Exception {
        if (request != null
                && request.source() instanceof CoopCaptureSourceEvidence) {
            return liveEntityBoundary.applyOrResolve(request, operation);
        }
        if (request == null || operation == null
                || !(request.source()
                instanceof CoopCapturedItemSourceEvidence)) {
            return LiveOperationResult.unknown(
                    "coop_capture_source_variant_invalid", null
            ).completed();
        }
        CompanionCoopCapturedItemAttempt attempt;
        try {
            attempt = capturedItemAttempts.open(request, operation);
        } catch (RuntimeException | LinkageError failure) {
            return LiveOperationResult.retryable(
                    "coop_capture_item_gateway_unavailable", failure
            ).completed();
        }
        return capturedItemExecutor.execute(request, operation, attempt);
    }

    /**
     * Cleans item-side receipts after durable commit and before publication.
     */
    @Nonnull
    @Override
    public CompletionStage<LiveOperationResult> cleanupAfterDurable(
            @Nonnull CompanionCoopCaptureRequest request,
            @Nonnull OperationEnvelope durableOperation
    ) {
        if (request == null || durableOperation == null
                || !(request.source()
                instanceof CoopCapturedItemSourceEvidence)) {
            if (request != null && durableOperation != null
                    && request.source()
                    instanceof CoopCaptureSourceEvidence) {
                return liveEntityBoundary.cleanupAfterDurable(
                        request, durableOperation
                );
            }
            return LiveOperationResult.unknown(
                    "coop_capture_item_cleanup_source_invalid", null
            ).completed();
        }
        CompanionCoopCapturedItemAttempt attempt;
        try {
            attempt = capturedItemAttempts.open(request, durableOperation);
        } catch (RuntimeException | LinkageError failure) {
            return LiveOperationResult.retryable(
                    "coop_capture_item_cleanup_gateway_unavailable",
                    failure
            ).completed();
        }
        return capturedItemExecutor.cleanupAfterDurableCommit(
                request, durableOperation, attempt
        );
    }
}
