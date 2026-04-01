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
            double deltaMs = (dtSeconds * MILLIS_PER_SECOND) + fractionalCarryMs;
            if (!Double.isFinite(deltaMs) || deltaMs <= 0.0) {
                return;
            }
            long wholeMs = (long) Math.floor(deltaMs);
            if (wholeMs > 0L) {
                activeRuntimeMs = saturatingAdd(activeRuntimeMs, wholeMs);
            }
            fractionalCarryMs = deltaMs - wholeMs;
            if (!Double.isFinite(fractionalCarryMs) || fractionalCarryMs < 0.0 || fractionalCarryMs >= 1.0) {
                fractionalCarryMs = 0.0;
            }
        }
    }

    static void resetForTests() {
        synchronized (LOCK) {
            activeRuntimeMs = 0L;
            fractionalCarryMs = 0.0;
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
}
