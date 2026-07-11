package com.alechilles.alecstamework.persistence.sqlite;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Durable canonical profile state used to seed and reconcile the population index.
 */
public record CompanionPopulationStateRecord(
        @Nonnull String profileId,
        @Nullable UUID currentNpcUuid,
        @Nullable UUID ownerUuid,
        @Nullable String profileLastWorldName,
        @Nullable String ownershipWorldName,
        @Nonnull String lifecycleState,
        @Nullable String physicalWorldName,
        @Nullable Integer physicalChunkX,
        @Nullable Integer physicalChunkZ,
        long revision,
        @Nullable String source,
        long createdAtMs,
        long updatedAtMs
) {
    public CompanionPopulationStateRecord {
        profileId = requireText(profileId, "profileId");
        lifecycleState = requireText(lifecycleState, "lifecycleState");
        if (revision < 0L) {
            throw new IllegalArgumentException("revision must be non-negative.");
        }
        boolean noPhysicalLocation = physicalWorldName == null
                && physicalChunkX == null
                && physicalChunkZ == null;
        boolean completePhysicalLocation = physicalWorldName != null
                && !physicalWorldName.isBlank()
                && physicalChunkX != null
                && physicalChunkZ != null;
        if (!noPhysicalLocation && !completePhysicalLocation) {
            throw new IllegalArgumentException("Physical location must be entirely present or absent.");
        }
    }

    @Nonnull
    private static String requireText(@Nonnull String value, @Nonnull String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        return normalized;
    }
}
