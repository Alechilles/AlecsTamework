package com.alechilles.alecstamework.npc.progression;


/**
 * Session-local runtime clock that advances only while systems are ticking.
 *
 * <p>This clock intentionally does not use wall-clock time, so paused/closed downtime does not
 * accrue elapsed progression that would later be "caught up" in a burst.
 */
public final class CompanionRuntimeClock {
    private static final double MILLIS_PER_SECOND = 1000.0;
    private static final Object LOCK = new Object();

    private static long activeRuntimeMs;
    private static double fractionalCarryMs;
    private static long lastWorldTickNanos = Long.MIN_VALUE;

    private CompanionRuntimeClock() {
    }

    public static long nowMs() {
        synchronized (LOCK) {
            return activeRuntimeMs;
        }
    }

    public static void advanceByDeltaSeconds(float dtSeconds) {
        if (!Float.isFinite(dtSeconds) || dtSeconds <= 0.0f) {
            return;
        }
        synchronized (LOCK) {
            advanceMillis(dtSeconds * MILLIS_PER_SECOND);
        }
    }

    /**
     * Advances the session clock from one world tick without counting the same
     * server interval once per loaded world. Monotonic elapsed time is capped
     * by the reporting tick, so resuming after a pause cannot replay downtime.
     */
    public static void advanceForWorld(float dtSeconds) {
        advanceForWorldTick(dtSeconds, System.nanoTime());
    }

    /** Package-visible deterministic seam for multi-world clock tests. */
    static void advanceForWorldTick(float dtSeconds, long monotonicNanos) {
        if (!Float.isFinite(dtSeconds) || dtSeconds <= 0.0f) {
            return;
        }
        synchronized (LOCK) {
            double boundedMs = dtSeconds * MILLIS_PER_SECOND;
            if (lastWorldTickNanos != Long.MIN_VALUE) {
                if (monotonicNanos <= lastWorldTickNanos) return;
                boundedMs = Math.min(boundedMs, (monotonicNanos - lastWorldTickNanos) / 1_000_000.0);
            }
            lastWorldTickNanos = monotonicNanos;
            advanceMillis(boundedMs);
        }
    }

    static void resetForTests() {
        synchronized (LOCK) {
            activeRuntimeMs = 0L;
            fractionalCarryMs = 0.0;
            lastWorldTickNanos = Long.MIN_VALUE;
        }
    }

    private static long saturatingAdd(long left, long right) {
        if (right <= 0L) {
            return left;
        }
        if (left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static void advanceMillis(double deltaMs) {
        deltaMs += fractionalCarryMs;
        if (!Double.isFinite(deltaMs) || deltaMs <= 0.0) return;
        long wholeMs = (long) Math.floor(deltaMs);
        if (wholeMs > 0L) activeRuntimeMs = saturatingAdd(activeRuntimeMs, wholeMs);
        fractionalCarryMs = deltaMs - wholeMs;
        if (!Double.isFinite(fractionalCarryMs) || fractionalCarryMs < 0.0 || fractionalCarryMs >= 1.0) {
            fractionalCarryMs = 0.0;
        }
    }
}
