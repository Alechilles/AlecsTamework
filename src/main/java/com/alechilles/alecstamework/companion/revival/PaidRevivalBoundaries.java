package com.alechilles.alecstamework.companion.revival;

import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import javax.annotation.Nonnull;

/** Complete composite and no-charge cleanup boundaries for paid revival. */
public record PaidRevivalBoundaries(
        @Nonnull PaidRevivalLiveBoundary revivals,
        @Nonnull PaidRevivalReleaseBoundary releases
) {
    public PaidRevivalBoundaries {
        if (revivals == null || releases == null) {
            throw new IllegalArgumentException(
                    "Both paid revival live boundaries are required"
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
                        ).completed()
        );
    }
}
