package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.persistence.operation.LiveOperationBoundary;

/**
 * Idempotent inventory replacement and target-retirement boundary for one capture.
 *
 * <p>Implementations write the operation receipt to the captured artifact first. A restart may
 * then finish target retirement from that positive evidence without interpreting absence as
 * proof.</p>
 */
@FunctionalInterface
public interface CompanionCaptureLiveBoundary
        extends LiveOperationBoundary<CompanionCaptureRequest> {
}
