package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureLiveBoundary;
import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureLiveBoundary;
import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseLiveBoundary;
import com.alechilles.alecstamework.companion.identity.CompanionAliasLiveBoundary;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationLiveBoundary;
import javax.annotation.Nonnull;

/** Complete external mutation/resolution boundaries used by normal work and recovery. */
public record PublicPersistenceLiveBoundaries(
        @Nonnull CompanionAliasLiveBoundary aliases,
        @Nonnull CompanionCaptureLiveBoundary captures,
        @Nonnull CompanionRestorationLiveBoundary restorations,
        @Nonnull CompanionCoopCaptureLiveBoundary coopCaptures,
        @Nonnull CompanionCoopReleaseLiveBoundary coopReleases
) {
    public PublicPersistenceLiveBoundaries {
        if (aliases == null || captures == null || restorations == null
                || coopCaptures == null || coopReleases == null) {
            throw new IllegalArgumentException(
                    "Every public live persistence boundary is required"
            );
        }
    }
}
