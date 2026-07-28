package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
                                    int remainingQuantity,
                                    @Nullable Sha256Hash remainingFingerprint,
                                    @Nonnull String receiptKey) {
    public CaptureSourceEvidence {
        if (actorUuid == null || slot < 0 || quantity <= 0
                || remainingQuantity != quantity - 1
                || (remainingQuantity == 0)
                != (remainingFingerprint == null)) {
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

    /** Source-compatible constructor for prior singleton source evidence. */
    public CaptureSourceEvidence(
            UUID actorUuid,
            String worldKey,
            int slot,
            String sourceItemId,
            int quantity,
            Sha256Hash beforeFingerprint,
            String receiptKey
    ) {
        this(
                actorUuid,
                worldKey,
                slot,
                sourceItemId,
                quantity,
                beforeFingerprint,
                0,
                null,
                receiptKey
        );
        if (quantity != 1) {
            throw new IllegalArgumentException(
                    "Legacy capture source evidence must be a singleton"
            );
        }
    }

    public int spentQuantity() {
        return quantity - remainingQuantity;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
