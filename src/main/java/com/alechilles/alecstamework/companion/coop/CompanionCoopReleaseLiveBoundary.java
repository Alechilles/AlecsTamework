package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.persistence.operation.LiveOperationBoundary;

/**
 * Idempotent live insertion boundary for coop release.
 *
 * <p>Only the exact spawn receipt may confirm completion. Entity absence remains retryable and
 * can never evict the durable resident.</p>
 */
@FunctionalInterface
public interface CompanionCoopReleaseLiveBoundary
        extends LiveOperationBoundary<CompanionCoopReleaseRequest> {
}
