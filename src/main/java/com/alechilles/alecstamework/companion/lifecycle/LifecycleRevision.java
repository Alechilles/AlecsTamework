package com.alechilles.alecstamework.companion.lifecycle;

/**
 * Optimistic revision of one canonical companion lifecycle.
 *
 * @param value non-negative revision; zero is the initial revision
 */
public record LifecycleRevision(long value) implements Comparable<LifecycleRevision> {
    public static final LifecycleRevision INITIAL = new LifecycleRevision(0);

    public LifecycleRevision {
        if (value < 0) {
            throw new IllegalArgumentException("Lifecycle revision cannot be negative");
        }
    }

    /** Returns the next revision, rejecting overflow instead of wrapping. */
    public LifecycleRevision next() {
        if (value == Long.MAX_VALUE) {
            throw new IllegalStateException("Lifecycle revision exhausted");
        }
        return new LifecycleRevision(value + 1);
    }

    @Override
    public int compareTo(LifecycleRevision other) {
        if (other == null) {
            throw new NullPointerException("Other lifecycle revision is required");
        }
        return Long.compare(value, other.value);
    }
}
