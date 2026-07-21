package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Exact source-item and world context fenced by a bonded-vessel transition. The caller must
 * revalidate the same slot revision and fingerprint immediately before applying the token.
 */
public record BondedVesselTransitionContext(@Nonnull String sourceItemId,
                                            @Nonnull String sourceHolderEvidenceId,
                                            @Nonnull String sourceContainerPath,
                                            int sourceInventorySlot,
                                            long sourceInventoryRevision,
                                            @Nonnull String sourceItemFingerprint,
                                            @Nullable UUID expectedNpcUuid,
                                            @Nullable PopulationAdmissionLocation destination) {
    public static final int MAX_SOURCE_ITEM_ID_LENGTH = 256;
    public static final int MAX_HOLDER_EVIDENCE_ID_LENGTH = 256;
    public static final int MAX_CONTAINER_PATH_LENGTH = 256;
    public static final int MAX_ITEM_FINGERPRINT_LENGTH = 1024;

    public BondedVesselTransitionContext {
        sourceItemId = requireText(
                sourceItemId, "sourceItemId", MAX_SOURCE_ITEM_ID_LENGTH);
        sourceHolderEvidenceId = requireText(
                sourceHolderEvidenceId, "sourceHolderEvidenceId", MAX_HOLDER_EVIDENCE_ID_LENGTH);
        sourceContainerPath = requireText(
                sourceContainerPath, "sourceContainerPath", MAX_CONTAINER_PATH_LENGTH);
        sourceItemFingerprint = requireText(
                sourceItemFingerprint, "sourceItemFingerprint", MAX_ITEM_FINGERPRINT_LENGTH);
        if (sourceInventorySlot < 0 || sourceInventoryRevision < 0L) {
            throw new IllegalArgumentException("Source slot and inventory revision cannot be negative.");
        }
    }

    /** Validates transition-specific context before durable preparation begins. */
    public void validateFor(@Nonnull BondedVesselTransition transition) {
        Objects.requireNonNull(transition, "transition");
        switch (transition) {
            case SUMMON -> {
                if (destination == null || expectedNpcUuid != null) {
                    throw new IllegalArgumentException("SUMMON requires a destination and no live NPC UUID.");
                }
            }
            case STORE -> {
                if (expectedNpcUuid == null || destination != null) {
                    throw new IllegalArgumentException("STORE requires the active NPC UUID and no destination.");
                }
            }
            case REPAIR_DEAD_TO_STORED -> {
                if (expectedNpcUuid != null || destination != null) {
                    throw new IllegalArgumentException("REPAIR_DEAD_TO_STORED accepts only the dead item projection.");
                }
            }
            case RELEASE -> {
                // Release may invalidate either a stored item or an active entity projection.
            }
        }
    }

    private static String requireText(String value, String field, int maxLength) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required.");
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " cannot exceed " + maxLength + " characters.");
        }
        return normalized;
    }
}
