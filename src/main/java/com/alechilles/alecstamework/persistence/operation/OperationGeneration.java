package com.alechilles.alecstamework.persistence.operation;

/**
 * Restart/reconciliation generation attached to durable operation evidence.
 *
 * @param value non-negative generation; zero is valid historical evidence
 */
public record OperationGeneration(long value) implements Comparable<OperationGeneration> {
    public static final OperationGeneration INITIAL = new OperationGeneration(0);

    public OperationGeneration {
        if (value < 0) {
            throw new IllegalArgumentException("Operation generation cannot be negative");
        }
    }

    /** Advances the generation without permitting overflow. */
    public OperationGeneration next() {
        if (value == Long.MAX_VALUE) {
            throw new IllegalStateException("Operation generation exhausted");
        }
        return new OperationGeneration(value + 1);
    }

    @Override
    public int compareTo(OperationGeneration other) {
        if (other == null) {
            throw new NullPointerException("Other operation generation is required");
        }
        return Long.compare(value, other.value);
    }
}
