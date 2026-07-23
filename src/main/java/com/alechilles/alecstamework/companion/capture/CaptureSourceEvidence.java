package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Exact external inventory evidence frozen before one capture attempt.
 *
 * <p>The receipt key must be written to the replacement artifact before target retirement. On
 * recovery that positive receipt, rather than inventory or entity absence, proves the mutation
 * belongs to this operation.</p>
 */
public record CaptureSourceEvidence(@Nonnull UUID actorUuid,
                                    @Nonnull String worldKey,
                                    int slot,
                                    @Nonnull String sourceItemId,
                                    int quantity,
                                    @Nonnull Sha256Hash beforeFingerprint,
                                    @Nonnull String receiptKey) {
    public CaptureSourceEvidence {
        if (actorUuid == null || slot < 0 || quantity <= 0) {
            throw new IllegalArgumentException("Valid capture source identity is required");
        }
        worldKey = requireText(worldKey, "Capture source world");
        sourceItemId = requireText(sourceItemId, "Capture source item");
        if (beforeFingerprint == null) {
            throw new IllegalArgumentException(
                    "Capture source before fingerprint is required"
            );
        }
        receiptKey = requireText(receiptKey, "Capture source receipt");
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
