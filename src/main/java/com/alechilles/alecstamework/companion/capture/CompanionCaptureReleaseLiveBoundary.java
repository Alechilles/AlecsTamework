package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.persistence.operation.LiveOperationBoundary;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Receipt-first inventory consumption and projection insertion boundary for capture release.
 *
 * <p>Implementations must confirm the exact replacement artifact before resolving or inserting
 * the entity projection. Success requires both durable live receipts; ambiguity is never a
 * retryable result.</p>
 */
@FunctionalInterface
public interface CompanionCaptureReleaseLiveBoundary
        extends LiveOperationBoundary<CompanionCaptureReleaseRequest> {

    /**
     * Releases the non-serialized movement hold after the canonical operation is published.
     *
     * <p>The default keeps test and non-Hytale boundaries source-compatible. Production uses the
     * hook to keep a newly durable projection immobile through the database commit boundary.</p>
     */
    @Nonnull
    default CompletionStage<Void> releaseProjectionHold(
            @Nonnull CompanionCaptureReleaseRequest request,
            @Nonnull OperationEnvelope operation
    ) {
        return CompletableFuture.completedFuture(null);
    }
}
