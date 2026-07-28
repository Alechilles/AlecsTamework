package com.alechilles.alecstamework.companion.lifecycle;

/**
 * Completed canonical reconciliation generation for one lifecycle.
 *
 * @param value non-negative generation; zero is valid initial evidence
 */
public record ReconciliationGeneration(long value)
        implements Comparable<ReconciliationGeneration> {
    public static final ReconciliationGeneration INITIAL = new ReconciliationGeneration(0);

    public ReconciliationGeneration {
        if (value < 0) {
            throw new IllegalArgumentException("Reconciliation generation cannot be negative");
        }
    }

    /** Advances the generation without permitting overflow. */
    public ReconciliationGeneration next() {
        if (value == Long.MAX_VALUE) {
            throw new IllegalStateException("Reconciliation generation exhausted");
        }
        return new ReconciliationGeneration(value + 1);
    }

    @Override
    public int compareTo(ReconciliationGeneration other) {
        if (other == null) {
            throw new NullPointerException("Other reconciliation generation is required");
        }
        return Long.compare(value, other.value);
    }
}
