package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.persistence.operation.LiveOperationBoundary;

/**
 * Idempotent live entity-retirement boundary for coop capture.
 *
 * <p>Only positive exact receipt evidence may return confirmed. Absence is retryable evidence,
 * never proof that retirement completed.</p>
 */
@FunctionalInterface
public interface CompanionCoopCaptureLiveBoundary
        extends LiveOperationBoundary<CompanionCoopCaptureRequest> {
}
