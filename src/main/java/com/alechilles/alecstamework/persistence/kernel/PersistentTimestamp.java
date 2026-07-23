package com.alechilles.alecstamework.persistence.kernel;

/**
 * Persisted world timestamp whose only unset sentinel is zero.
 *
 * <p>Hytale world time may use negative epoch milliseconds. Negative values therefore remain
 * ordered, set timestamps and must never be clamped or treated as absent.</p>
 *
 * @param epochMillis persisted world-time epoch milliseconds
 */
public record PersistentTimestamp(long epochMillis) implements Comparable<PersistentTimestamp> {
    public static final PersistentTimestamp UNSET = new PersistentTimestamp(0);

    /** Creates a timestamp while preserving its sign. */
    public static PersistentTimestamp of(long epochMillis) {
        return epochMillis == 0 ? UNSET : new PersistentTimestamp(epochMillis);
    }

    /** Returns whether this timestamp is the explicit unset sentinel. */
    public boolean isUnset() {
        return epochMillis == 0;
    }

    /** Returns whether this timestamp contains a real persisted value. */
    public boolean isSet() {
        return !isUnset();
    }

    /** Returns whether a set deadline has been reached at the supplied signed world time. */
    public boolean hasElapsedAt(long nowEpochMillis) {
        return isSet() && nowEpochMillis >= epochMillis;
    }

    /** Returns whether a set deadline is still pending at the supplied signed world time. */
    public boolean isPendingAt(long nowEpochMillis) {
        return isSet() && nowEpochMillis < epochMillis;
    }

    @Override
    public int compareTo(PersistentTimestamp other) {
        if (other == null) {
            throw new NullPointerException("Other persistent timestamp is required");
        }
        return Long.compare(epochMillis, other.epochMillis);
    }
}
