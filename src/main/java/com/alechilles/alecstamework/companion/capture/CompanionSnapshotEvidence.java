package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import javax.annotation.Nonnull;

/** Exact canonical snapshot and filled artifact created by an ordinary capture. */
public record CompanionSnapshotEvidence(
        @Nonnull CompanionSnapshot snapshot,
        @Nonnull CapturedArtifact artifact
) {
    public CompanionSnapshotEvidence {
        if (snapshot == null || artifact == null) {
            throw new IllegalArgumentException(
                    "Capture snapshot and artifact are required"
            );
        }
    }
}
