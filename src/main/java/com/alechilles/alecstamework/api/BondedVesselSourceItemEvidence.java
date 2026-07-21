package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * Immutable evidence for one exact item stack at the point a bonded-vessel operation is requested.
 * Location is evidence, not vessel identity; callers must obtain a fresh instance after any move.
 */
public record BondedVesselSourceItemEvidence(@Nonnull String itemId,
                                             @Nonnull String holderEvidenceId,
                                             @Nonnull String containerPath,
                                             int inventorySlot,
                                             long inventoryRevision,
                                             @Nonnull String itemFingerprint) {
    private static final int ID_MAX_LENGTH = 256;
    private static final int PATH_MAX_LENGTH = 512;
    private static final int FINGERPRINT_MAX_LENGTH = 512;

    public BondedVesselSourceItemEvidence {
        itemId = requireText(itemId, "itemId", ID_MAX_LENGTH);
        holderEvidenceId = requireText(holderEvidenceId, "holderEvidenceId", ID_MAX_LENGTH);
        containerPath = requireText(containerPath, "containerPath", PATH_MAX_LENGTH);
        itemFingerprint = requireText(itemFingerprint, "itemFingerprint", FINGERPRINT_MAX_LENGTH);
        if (inventorySlot < 0) {
            throw new IllegalArgumentException("inventorySlot cannot be negative.");
        }
        if (inventoryRevision < 0L) {
            throw new IllegalArgumentException("inventoryRevision cannot be negative.");
        }
    }

    private static String requireText(String value, String field, int maxLength) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters.");
        }
        return normalized;
    }
}
