package com.alechilles.alecstamework.companion.revival;

import com.alechilles.alecstamework.persistence.operation
        .DurableOperationCleanupBoundary;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import javax.annotation.Nonnull;

/**
 * Complete live, compensation-release, and post-canonical cleanup boundaries.
 */
public record PaidRevivalBoundaries(
        @Nonnull PaidRevivalLiveBoundary revivals,
        @Nonnull PaidRevivalReleaseBoundary releases,
        @Nonnull DurableOperationCleanupBoundary<PaidRevivalRequest> cleanups
) {
    public PaidRevivalBoundaries {
        if (revivals == null || releases == null || cleanups == null) {
            throw new IllegalArgumentException(
                    "Every paid revival live boundary is required"
            );
        }
    }

    /** Returns fail-closed boundaries for runtimes without paid revival. */
    @Nonnull
    public static PaidRevivalBoundaries unavailable() {
        return new PaidRevivalBoundaries(
                (request, operation) ->
                        PaidRevivalLiveResult.retryable(
                                "paid_revival_unavailable", null
                        ).completed(),
                (request, operation) ->
                        LiveOperationResult.retryable(
                                "paid_revival_release_unavailable", null
                        ).completed(),
                (request, operation) ->
                        LiveOperationResult.retryable(
                                "paid_revival_cleanup_unavailable", null
                        ).completed()
        );
    }
}
