package com.alechilles.alecstamework.ownership.reconciliation;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One persisted-source observation used to repair canonical companion population state.
 */
public record CompanionPopulationEvidence(
        @Nonnull String evidenceKey,
        @Nonnull UUID npcUuid,
        @Nullable UUID ownerUuid,
        boolean ownerObserved,
        @Nonnull Kind kind,
        @Nullable String ownershipWorldName,
        @Nullable String physicalWorldName,
        @Nullable Integer physicalChunkX,
        @Nullable Integer physicalChunkZ,
        @Nonnull String source
) {
    public enum Kind {
        PHYSICAL_ENTITY,
        PHYSICAL_DEAD_ENTITY,
        CAPTURED_SNAPSHOT,
        DEATH_SNAPSHOT,
        LOST_SNAPSHOT,
        COOP_SNAPSHOT,
        CAPTURED_ITEM,
        PROFILE_RECORD,
        PROJECTION_MARKER;

        public boolean isPhysical() {
            return this == PHYSICAL_ENTITY || this == PHYSICAL_DEAD_ENTITY;
        }

        public boolean isDeadPhysical() {
            return this == PHYSICAL_DEAD_ENTITY;
        }

        public boolean isProjectionMarker() {
            return this == PROJECTION_MARKER;
        }
    }

    public CompanionPopulationEvidence {
        evidenceKey = requireText(evidenceKey, "evidenceKey");
        Objects.requireNonNull(npcUuid, "npcUuid");
        Objects.requireNonNull(kind, "kind");
        source = requireText(source, "source");
        ownershipWorldName = normalizeText(ownershipWorldName);
        physicalWorldName = normalizeText(physicalWorldName);
        boolean noPhysicalLocation = physicalWorldName == null
                && physicalChunkX == null
                && physicalChunkZ == null;
        boolean completePhysicalLocation = physicalWorldName != null
                && physicalChunkX != null
                && physicalChunkZ != null;
        if (!noPhysicalLocation && !completePhysicalLocation) {
            throw new IllegalArgumentException("Physical evidence location must be entirely present or absent.");
        }
        if ((kind.isPhysical() || kind.isProjectionMarker()) && !completePhysicalLocation) {
            throw new IllegalArgumentException(
                    "Physical and projection-marker evidence requires a complete chunk location."
            );
        }
        if (!kind.isPhysical() && !kind.isProjectionMarker() && !noPhysicalLocation) {
            throw new IllegalArgumentException("Dormant/profile evidence cannot claim physical occupancy.");
        }
        CompanionProjectionEvidence.ProjectionObservation projection =
                CompanionProjectionEvidence.parseEvidenceKey(evidenceKey);
        if (CompanionProjectionEvidence.containsReservedSuffix(evidenceKey) && projection == null) {
            throw new IllegalArgumentException("Projection evidence key suffix is malformed.");
        }
        if (kind.isProjectionMarker() != (projection != null)) {
            throw new IllegalArgumentException(
                    "Projection marker kind and evidence key suffix must be present together."
            );
        }
    }

    /** Compatibility constructor: concrete non-profile sources observe even a null owner. */
    public CompanionPopulationEvidence(
            @Nonnull String evidenceKey,
            @Nonnull UUID npcUuid,
            @Nullable UUID ownerUuid,
            @Nonnull Kind kind,
            @Nullable String ownershipWorldName,
            @Nullable String physicalWorldName,
            @Nullable Integer physicalChunkX,
            @Nullable Integer physicalChunkZ,
            @Nonnull String source
    ) {
        this(
                evidenceKey,
                npcUuid,
                ownerUuid,
                kind != Kind.PROFILE_RECORD,
                kind,
                ownershipWorldName,
                physicalWorldName,
                physicalChunkX,
                physicalChunkZ,
                source
        );
    }

    /** Returns marker identity encoded in this row without changing the persisted schema. */
    @Nullable
    public CompanionProjectionEvidence.ProjectionObservation projectionObservation() {
        return CompanionProjectionEvidence.parseEvidenceKey(evidenceKey);
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
