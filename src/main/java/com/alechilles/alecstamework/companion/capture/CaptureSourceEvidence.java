package com.alechilles.alecstamework.companion.capture;

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
                                    @Nonnull String beforeFingerprint,
                                    @Nonnull String afterFingerprint,
                                    @Nonnull String receiptKey) {
    public CaptureSourceEvidence {
        if (actorUuid == null || slot < 0 || quantity <= 0) {
            throw new IllegalArgumentException("Valid capture source identity is required");
        }
        worldKey = requireText(worldKey, "Capture source world");
        sourceItemId = requireText(sourceItemId, "Capture source item");
        beforeFingerprint = requireText(
                beforeFingerprint,
                "Capture source before fingerprint"
        );
        afterFingerprint = requireText(
                afterFingerprint,
                "Capture source after fingerprint"
        );
        receiptKey = requireText(receiptKey, "Capture source receipt");
        if (beforeFingerprint.equals(afterFingerprint)) {
            throw new IllegalArgumentException(
                    "Capture source fingerprints must describe an exact mutation"
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
