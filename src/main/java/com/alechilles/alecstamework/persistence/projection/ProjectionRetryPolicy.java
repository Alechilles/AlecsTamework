package com.alechilles.alecstamework.persistence.projection;

/** Bounded exponential retry guidance for projection catch-up failures. */
public record ProjectionRetryPolicy(long initialDelayMs, long maximumDelayMs) {
    public static final ProjectionRetryPolicy DEFAULT = new ProjectionRetryPolicy(100, 30_000);

    public ProjectionRetryPolicy {
        if (initialDelayMs <= 0 || maximumDelayMs < initialDelayMs) {
            throw new IllegalArgumentException("Valid positive projection retry bounds are required");
        }
    }

    /** Returns the bounded delay for a one-based consecutive failure count. */
    public long delayMs(int consecutiveFailures) {
        if (consecutiveFailures <= 0) {
            throw new IllegalArgumentException("Projection failure count must be positive");
        }
        long delay = initialDelayMs;
        for (int index = 1; index < consecutiveFailures; index++) {
            if (delay >= maximumDelayMs / 2) {
                return maximumDelayMs;
            }
            delay *= 2;
        }
        return Math.min(delay, maximumDelayMs);
    }
}
