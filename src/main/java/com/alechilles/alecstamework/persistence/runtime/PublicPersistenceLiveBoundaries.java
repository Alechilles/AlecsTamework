package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureLiveBoundary;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseLiveBoundary;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLiveBoundary;
import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureLiveBoundary;
import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseLiveBoundary;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationLiveBoundary;
import javax.annotation.Nonnull;

/** Complete external mutation/resolution boundaries used by normal work and recovery. */
public record PublicPersistenceLiveBoundaries(
        @Nonnull CompanionCaptureLiveBoundary captures,
        @Nonnull CompanionCaptureReleaseLiveBoundary capturedReleases,
        @Nonnull CompanionRestorationLiveBoundary restorations,
        @Nonnull CompanionCoopCaptureLiveBoundary coopCaptures,
        @Nonnull CompanionCoopReleaseLiveBoundary coopReleases,
        @Nonnull TimedSummonLiveBoundary timedSummons
) {
    public PublicPersistenceLiveBoundaries {
        if (captures == null || capturedReleases == null
                || restorations == null
                || coopCaptures == null || coopReleases == null
                || timedSummons == null) {
            throw new IllegalArgumentException(
                    "Every public live persistence boundary is required"
            );
        }
    }
}
