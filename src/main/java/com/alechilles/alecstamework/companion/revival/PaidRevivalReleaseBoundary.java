package com.alechilles.alecstamework.companion.revival;

import com.alechilles.alecstamework.persistence.operation.LiveOperationBoundary;

/**
 * Idempotent cleanup for a proven no-charge/no-spawn revival result.
 *
 * <p>The boundary removes reversible inventory holds and positively confirms
 * that neither exact receipt exists.</p>
 */
@FunctionalInterface
public interface PaidRevivalReleaseBoundary
        extends LiveOperationBoundary<PaidRevivalRequest> {
}
