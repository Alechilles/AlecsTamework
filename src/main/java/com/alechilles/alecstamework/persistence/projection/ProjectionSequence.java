package com.alechilles.alecstamework.persistence.projection;

/**
 * Monotonic durable outbox position.
 *
 * @param value non-negative position; zero represents before the first event
 */
public record ProjectionSequence(long value) implements Comparable<ProjectionSequence> {
    public static final ProjectionSequence ORIGIN = new ProjectionSequence(0);

    public ProjectionSequence {
        if (value < 0) {
            throw new IllegalArgumentException("Projection sequence cannot be negative");
        }
    }

    @Override
    public int compareTo(ProjectionSequence other) {
        if (other == null) {
            throw new NullPointerException("Other projection sequence is required");
        }
        return Long.compare(value, other.value);
    }
}
