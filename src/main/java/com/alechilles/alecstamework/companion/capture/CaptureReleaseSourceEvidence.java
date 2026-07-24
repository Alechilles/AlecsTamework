package com.alechilles.alecstamework.companion.capture;

import java.util.UUID;
import javax.annotation.Nonnull;

/** Exact inventory location and before/after artifacts for one captured-item release. */
public record CaptureReleaseSourceEvidence(
        @Nonnull UUID actorUuid,
        @Nonnull String worldKey,
        int slot,
        @Nonnull CapturedArtifact sourceArtifact,
        @Nonnull CapturedArtifact receiptArtifact
) {
    public CaptureReleaseSourceEvidence {
        if (actorUuid == null || sourceArtifact == null
                || receiptArtifact == null) {
            throw new IllegalArgumentException(
                    "Complete capture release source evidence is required"
            );
        }
        worldKey = requireText(worldKey, "Capture release source world");
        if (slot < 0) {
            throw new IllegalArgumentException(
                    "Capture release source slot must be non-negative"
            );
        }
        if (sourceArtifact.equals(receiptArtifact)) {
            throw new IllegalArgumentException(
                    "Capture release source and receipt artifacts must differ"
            );
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
