package com.alechilles.alecstamework.persistence.compensation;

import com.alechilles.alecstamework.persistence.operation.LiveOperationBoundary;

/**
 * Idempotent external boundary for one receipt-addressable refund.
 *
 * <p>Implementations must resolve a positive receipt before adding an item. Missing inventory is
 * not evidence that delivery should be repeated.</p>
 */
@FunctionalInterface
public interface RefundDeliveryBoundary extends LiveOperationBoundary<RefundClaim> {
}
