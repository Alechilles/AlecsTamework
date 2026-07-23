package com.alechilles.alecstamework.persistence.projection;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Explicit result of one after-commit or startup projection catch-up attempt. */
public record ProjectionCatchUpResult(@Nonnull Status status,
                                      @Nonnull ProjectionSequence acknowledged,
                                      int deliveredCount,
                                      long retryAfterMs,
                                      @Nullable Throwable failure) {
    public ProjectionCatchUpResult {
        if (status == null || acknowledged == null || deliveredCount < 0 || retryAfterMs < 0) {
            throw new IllegalArgumentException("Complete projection catch-up result is required");
        }
        if ((status == Status.CAUGHT_UP) != (retryAfterMs == 0 && failure == null)) {
            throw new IllegalArgumentException("Only successful catch-up has no failure or retry delay");
        }
    }

    public enum Status {
        CAUGHT_UP,
        READ_FAILED,
        CONSUMER_FAILED,
        CHECKPOINT_FAILED
    }
}
