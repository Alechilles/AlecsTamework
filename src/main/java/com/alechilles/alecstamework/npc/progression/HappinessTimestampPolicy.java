package com.alechilles.alecstamework.npc.progression;

/**
 * Defines the wall-clock timestamp contract used by happiness progression state.
 *
 * <p>Unlike breeding cooldown deadlines, happiness update timestamps are Unix wall-clock values.
 * Zero and negative values are therefore invalid legacy data rather than signed world time.
 */
public final class HappinessTimestampPolicy {
    private HappinessTimestampPolicy() {
    }

    /** Returns whether the value can be used as a happiness wall-clock timestamp. */
    public static boolean isValid(long timestampMs) {
        return timestampMs > 0L;
    }

    /** Preserves a valid value or substitutes the supplied positive wall-clock fallback. */
    public static long orElse(long timestampMs, long fallbackMs) {
        if (isValid(timestampMs)) {
            return timestampMs;
        }
        if (!isValid(fallbackMs)) {
            throw new IllegalArgumentException("fallbackMs must be a positive wall-clock timestamp");
        }
        return fallbackMs;
    }

    /** Preserves a valid value or substitutes the current wall-clock time. */
    public static long orNow(long timestampMs) {
        return orElse(timestampMs, System.currentTimeMillis());
    }
}
