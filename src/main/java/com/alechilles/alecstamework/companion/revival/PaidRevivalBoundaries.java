package com.alechilles.alecstamework.companion.revival;

import javax.annotation.Nonnull;

/** Complete live and no-charge cleanup boundaries for paid revival recovery. */
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

    /** Returns explicit fail-closed boundaries for runtimes that do not enable paid revival. */
    @Nonnull
    public static PaidRevivalBoundaries unavailable() {
        return new PaidRevivalBoundaries(
                (request, operation) ->
                        PaidRevivalLiveResult.retryable(
                                "paid_revival_unavailable", null
                        ).completed(),
                (request, operation) ->
                        com.alechilles.alecstamework.persistence.operation
                                .LiveOperationResult.retryable(
                                        "paid_revival_release_unavailable",
                                        null
                                ).completed()
        );
    }
}
