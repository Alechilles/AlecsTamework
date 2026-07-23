package com.alechilles.alecstamework.companion.command.timed;

/** Signed-world-time and monotonic-duration arithmetic for timed summons. */
public final class TimedSummonTime {
    private TimedSummonTime() {
    }

    /** Adds a nonnegative duration without clamping a signed base timestamp. */
    public static long saturatingAdd(long baseMs, long durationMs) {
        if (durationMs < 0) {
            throw new IllegalArgumentException(
                    "Timed summon duration cannot be negative"
            );
        }
        try {
            return Math.addExact(baseMs, durationMs);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /** Decrements a finite remaining duration by process-monotonic elapsed time. */
    public static long remaining(
            long persistedRemainingMs,
            long observedAtNanos,
            long nowNanos
    ) {
        long elapsedNanos = nowNanos - observedAtNanos;
        if (persistedRemainingMs < 0 || elapsedNanos < 0) {
            throw new IllegalArgumentException(
                    "Monotonic timed summon observation is required"
            );
        }
        long elapsedMs = elapsedNanos / 1_000_000L;
        return Math.max(0L, persistedRemainingMs - Math.min(
                persistedRemainingMs, elapsedMs
        ));
    }
}
