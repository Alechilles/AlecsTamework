package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * Post-commit notification for one durably stored bonded capture.
 *
 * <p>Exact source cleanup and item finalization may still be pending. Consumers
 * must use {@link BondedCompanionApi#findCapture} for restart recovery because
 * event subscriptions are live notifications rather than historical cursors.</p>
 */
public record BondedCompanionCaptureResolvedEvent(
        @Nonnull BondedCompanionCaptureEvidenceView capture,
        long emittedAtMs
) implements TameworkEvent {
    public BondedCompanionCaptureResolvedEvent {
        capture = Objects.requireNonNull(capture, "capture");
    }
}
