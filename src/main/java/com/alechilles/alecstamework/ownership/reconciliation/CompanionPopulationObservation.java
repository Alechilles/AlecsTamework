package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable live-ECS observation queued for coalesced canonical population persistence.
 */
public record CompanionPopulationObservation(
        @Nonnull String profileId,
        @Nonnull UUID currentNpcUuid,
        @Nullable UUID ownerUuid,
        @Nullable String ownershipWorldName,
        @Nonnull CompanionLifecycleState lifecycleState,
        @Nullable String physicalWorldName,
        @Nullable Integer physicalChunkX,
        @Nullable Integer physicalChunkZ,
        long expectedRevision,
        @Nonnull String source
) {
    public CompanionPopulationObservation {
        profileId = requireText(profileId, "profileId");
        Objects.requireNonNull(currentNpcUuid, "currentNpcUuid");
        Objects.requireNonNull(lifecycleState, "lifecycleState");
        ownershipWorldName = normalizeText(ownershipWorldName);
        physicalWorldName = normalizeText(physicalWorldName);
        source = requireText(source, "source");
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("expectedRevision must be non-negative.");
        }
        boolean noLocation = physicalWorldName == null
                && physicalChunkX == null
                && physicalChunkZ == null;
        boolean completeLocation = physicalWorldName != null
                && physicalChunkX != null
                && physicalChunkZ != null;
        if (!noLocation && !completeLocation) {
            throw new IllegalArgumentException("Physical location must be entirely present or absent.");
        }
        if ((lifecycleState == CompanionLifecycleState.ACTIVE
                || lifecycleState == CompanionLifecycleState.UNLOADED) && !completeLocation) {
            throw new IllegalArgumentException("Physical lifecycle observations require a chunk location.");
        }
    }

    @Nonnull
    public CompanionPopulationObservation withExpectedRevision(long revision) {
        return new CompanionPopulationObservation(
                profileId,
                currentNpcUuid,
                ownerUuid,
                ownershipWorldName,
                lifecycleState,
                physicalWorldName,
                physicalChunkX,
                physicalChunkZ,
                revision,
                source
        );
    }

    @Nonnull
    private static String requireText(@Nonnull String value, @Nonnull String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        return normalized;
    }

    @Nullable
    private static String normalizeText(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
