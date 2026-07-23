package com.alechilles.alecstamework.persistence.kernel;

/**
 * Result of a bounded kernel component shutdown.
 *
 * @param status drain outcome
 * @param outstandingOperations accepted operations still owned by the draining component
 */
public record PersistenceShutdownResult(Status status, int outstandingOperations) {
    public PersistenceShutdownResult {
        if (status == null) {
            throw new IllegalArgumentException("Shutdown status is required");
        }
        if (outstandingOperations < 0) {
            throw new IllegalArgumentException("Outstanding operation count cannot be negative");
        }
    }

    public enum Status {
        DRAINED,
        TIMED_OUT,
        ALREADY_CLOSED
    }
}
