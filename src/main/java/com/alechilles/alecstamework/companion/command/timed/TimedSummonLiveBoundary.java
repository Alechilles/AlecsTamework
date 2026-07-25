package com.alechilles.alecstamework.companion.command.timed;

import com.alechilles.alecstamework.persistence.operation.LiveOperationBoundary;

/**
 * Idempotent world-thread spawn/store receipt boundary for timed transitions.
 *
 * <p>Implementations resolve the exact operation receipt. Entity absence alone
 * never proves either transition complete.</p>
 */
@FunctionalInterface
public interface TimedSummonLiveBoundary
        extends LiveOperationBoundary<TimedSummonTransitionRequest> {
}

