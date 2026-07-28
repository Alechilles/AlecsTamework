package com.alechilles.alecstamework.persistence.kernel;

/** Submission-time cancellation signal; accepted durable work ignores later cancellation. */
@FunctionalInterface
public interface PersistenceCancellation {
    PersistenceCancellation NONE = () -> false;

    /** Returns whether cancellation was requested before writer acceptance. */
    boolean isCancelled();
}
