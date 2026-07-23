package com.alechilles.alecstamework.persistence.compensation;

import com.alechilles.alecstamework.persistence.operation.LiveOperationBoundary;

/**
 * Idempotent external boundary for one receipt-addressable refund recipe.
 *
 * <p>Implementations must resolve a positive receipt before adding the exact recipe. Missing
 * inventory is not evidence that delivery should be repeated.</p>
 */
@FunctionalInterface
public interface RefundDeliveryBoundary extends LiveOperationBoundary<RefundClaim> {
}
