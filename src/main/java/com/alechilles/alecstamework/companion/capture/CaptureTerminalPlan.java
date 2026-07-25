package com.alechilles.alecstamework.companion.capture;

import javax.annotation.Nonnull;

/** Exact terminal result selected before the canonical capture operation is submitted. */
public sealed interface CaptureTerminalPlan {
    @Nonnull
    CaptureAttemptResolution resolution();

    /** Successful ordinary capture that produces one filled artifact. */
    record CapturedItem(
            @Nonnull CaptureAttemptResolution resolution,
            @Nonnull CompanionSnapshotEvidence evidence
    ) implements CaptureTerminalPlan {
        public CapturedItem {
            if (resolution == null || evidence == null
                    || !resolution.successful()
                    || resolution.successDisposition()
                    != com.alechilles.alecstamework.api
                    .CaptureSuccessDisposition.CAPTURED_ITEM) {
                throw new IllegalArgumentException(
                        "Captured-item terminal evidence is inconsistent"
                );
            }
        }
    }

    /** Terminal failed roll that spends only a resolved-attempt source. */
    record FailedAttempt(
            @Nonnull CaptureAttemptResolution resolution
    ) implements CaptureTerminalPlan {
        public FailedAttempt {
            if (resolution == null || resolution.successful()) {
                throw new IllegalArgumentException(
                        "Failed capture terminal evidence is required"
                );
            }
        }
    }
}
