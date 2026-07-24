package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.persistence.operation.LiveOperationBoundary;

/**
 * Idempotent live entity-retirement boundary for coop capture.
 *
 * <p>Only a durably saved exact receipt plus exact source absence may return confirmed. Source
 * absence without that receipt is ambiguous evidence and must fail closed as unknown.</p>
 */
@FunctionalInterface
public interface CompanionCoopCaptureLiveBoundary
        extends LiveOperationBoundary<CompanionCoopCaptureRequest> {
}
