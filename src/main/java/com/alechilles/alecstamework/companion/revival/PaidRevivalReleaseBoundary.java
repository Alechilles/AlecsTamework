package com.alechilles.alecstamework.companion.revival;

import com.alechilles.alecstamework.persistence.operation.LiveOperationBoundary;

/**
 * Idempotent live cleanup for a proven no-charge/no-spawn revival outcome.
 *
 * <p>The boundary removes any reversible inventory hold evidence and confirms that no exact
 * charge or spawn receipt exists.</p>
 */
@FunctionalInterface
public interface PaidRevivalReleaseBoundary
        extends LiveOperationBoundary<PaidRevivalRequest> {
}
