package com.alechilles.alecstamework.companion.restoration;

import com.alechilles.alecstamework.persistence.operation.LiveOperationBoundary;

/**
 * Idempotent entity-insertion or receipt-resolution boundary for one restoration.
 *
 * <p>The target alias is durably leased before this boundary is invoked. Implementations must
 * write the spawn receipt to the inserted entity and, after restart, confirm only that positive
 * receipt. Entity absence is never completion evidence.</p>
 */
@FunctionalInterface
public interface CompanionRestorationLiveBoundary
        extends LiveOperationBoundary<CompanionRestorationRequest> {
}
