package com.alechilles.alecstamework.persistence.sqlite;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Canonical SQLite authority for one bonded vessel and its current generation. */
public record BondedVesselBindingRecord(
        @Nonnull String bindingId,
        @Nonnull String profileId,
        long generation,
        @Nonnull String configId,
        long configRevision,
        @Nonnull LifecycleState lifecycleState,
        @Nonnull ItemProjectionStatus itemProjectionStatus,
        @Nonnull UUID ownerUuid,
        long expectedProfileRevision,
        @Nullable UUID activeNpcUuid,
        @Nullable PhysicalLocation activeLocation,
        long cooldownUntilMs,
        @Nullable String lastItemId,
        @Nullable String itemEvidenceJson,
        @Nullable String activeOperationId,
        @Nullable String diagnosticReason,
        long rowRevision,
        long createdAtMs,
        long updatedAtMs,
        long releasedAtMs
) {
    public enum LifecycleState {
        STORED,
        SUMMONING,
        ACTIVE,
        STORING,
        DEAD,
        LOST,
        RELEASING,
        RELEASED
    }

    public enum ItemProjectionStatus {
        PRESENT,
        MISSING,
        AMBIGUOUS,
        REISSUE_PENDING,
        QUARANTINED
    }

    /** Last authoritative physical projection location, when the binding is live. */
    public record PhysicalLocation(@Nonnull String worldName, int chunkX, int chunkZ) {
        public PhysicalLocation {
            worldName = requireText(worldName, "worldName");
        }
    }

    public BondedVesselBindingRecord {
        bindingId = requireText(bindingId, "bindingId");
        profileId = requireText(profileId, "profileId");
        configId = requireText(configId, "configId");
        lifecycleState = Objects.requireNonNull(lifecycleState, "lifecycleState");
        itemProjectionStatus = Objects.requireNonNull(itemProjectionStatus, "itemProjectionStatus");
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        lastItemId = normalize(lastItemId);
        itemEvidenceJson = normalize(itemEvidenceJson);
        activeOperationId = normalize(activeOperationId);
        diagnosticReason = normalize(diagnosticReason);
        if (generation <= 0L) {
            throw new IllegalArgumentException("generation must be positive.");
        }
        if (configRevision < 0L || expectedProfileRevision < 0L || rowRevision < 0L) {
            throw new IllegalArgumentException("Revisions must be non-negative.");
        }
        if (lifecycleState == LifecycleState.RELEASED && releasedAtMs == 0L) {
            throw new IllegalArgumentException("Released bindings require releasedAtMs evidence.");
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

    @Nullable
    private static String normalize(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
