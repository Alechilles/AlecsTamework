package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Exact relevant live-NPC state before and after in-place tame/link convergence. */
public record CaptureTameLiveEvidence(
        @Nonnull String expectedRoleId,
        @Nullable OwnerId expectedOwnerId,
        boolean expectedTamed,
        @Nonnull Sha256Hash expectedStateHash,
        @Nonnull String targetRoleId,
        @Nonnull OwnerId targetOwnerId,
        @Nonnull String targetOwnerName,
        @Nonnull Sha256Hash targetStateHash,
        @Nonnull CaptureCommandAccessEvidence commandAccess
) {
    public CaptureTameLiveEvidence {
        expectedRoleId = text(expectedRoleId, "Expected live role");
        targetRoleId = text(targetRoleId, "Target live role");
        targetOwnerName = text(
                targetOwnerName, "Target owner display name"
        );
        if (expectedStateHash == null || targetOwnerId == null
                || targetStateHash == null || commandAccess == null) {
            throw new IllegalArgumentException(
                    "Complete tame/link live evidence is required"
            );
        }
    }

    private static String text(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
