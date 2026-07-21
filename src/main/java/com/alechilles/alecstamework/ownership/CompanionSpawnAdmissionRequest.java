package com.alechilles.alecstamework.ownership;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable input for reserving one companion before its NPC holder is created. */
public record CompanionSpawnAdmissionRequest(
        @Nullable String canonicalProfileId,
        @Nullable UUID previousNpcUuid,
        @Nullable CompanionLifecycleState requiredSourceLifecycle,
        boolean allowLegacyAdoption,
        @Nullable UUID ownerId,
        @Nullable String ownerName,
        @Nonnull String worldName,
        int chunkX,
        int chunkZ,
        @Nonnull OwnerPopulationOperation operation,
        @Nonnull String sourceKind,
        @Nonnull String idempotencyKey,
        boolean force,
        @Nullable String durableContextJson,
        @Nullable String targetRoleId
) {
    public CompanionSpawnAdmissionRequest {
        canonicalProfileId = normalizeNullable(canonicalProfileId);
        ownerName = normalizeNullable(ownerName);
        worldName = requireText(worldName, "worldName");
        sourceKind = requireText(sourceKind, "sourceKind");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        durableContextJson = normalizeNullable(durableContextJson);
        targetRoleId = normalizeNullable(targetRoleId);
        CompanionSpawnSourceFinalizationContext.validateExtension(durableContextJson);
        Objects.requireNonNull(operation, "operation");
        boolean canonicalNullNpcRestore = previousNpcUuid == null
                && canonicalProfileId != null
                && requiredSourceLifecycle == CompanionLifecycleState.PROVISIONED_DORMANT
                && !allowLegacyAdoption
                && operation == OwnerPopulationOperation.RESTORE;
        if (previousNpcUuid == null && !canonicalNullNpcRestore) {
            if (canonicalProfileId != null || requiredSourceLifecycle != null || allowLegacyAdoption) {
                throw new IllegalArgumentException("New spawns cannot declare a dormant source.");
            }
            if (operation != OwnerPopulationOperation.NEW_OWNERSHIP
                    && operation != OwnerPopulationOperation.ADMIN_FORCE) {
                throw new IllegalArgumentException("New spawns require a new-ownership operation.");
            }
        } else if (previousNpcUuid != null) {
            Objects.requireNonNull(requiredSourceLifecycle, "requiredSourceLifecycle");
            if (operation != OwnerPopulationOperation.RESTORE
                    && operation != OwnerPopulationOperation.ADMIN_FORCE) {
                throw new IllegalArgumentException("Replacement spawns require a restore operation.");
            }
            if (requiredSourceLifecycle == CompanionLifecycleState.ACTIVE
                    || requiredSourceLifecycle == CompanionLifecycleState.UNLOADED
                    || requiredSourceLifecycle == CompanionLifecycleState.RESTORING) {
                throw new IllegalArgumentException("Replacement sources must be dormant.");
            }
        }
    }

    /** Backward-compatible constructor; existing-profile restores resolve role from persistence. */
    public CompanionSpawnAdmissionRequest(
            @Nullable String canonicalProfileId,
            @Nullable UUID previousNpcUuid,
            @Nullable CompanionLifecycleState requiredSourceLifecycle,
            boolean allowLegacyAdoption,
            @Nullable UUID ownerId,
            @Nullable String ownerName,
            @Nonnull String worldName,
            int chunkX,
            int chunkZ,
            @Nonnull OwnerPopulationOperation operation,
            @Nonnull String sourceKind,
            @Nonnull String idempotencyKey,
            boolean force,
            @Nullable String durableContextJson) {
        this(canonicalProfileId, previousNpcUuid, requiredSourceLifecycle, allowLegacyAdoption,
                ownerId, ownerName, worldName, chunkX, chunkZ, operation, sourceKind,
                idempotencyKey, force, durableContextJson, null);
    }

    /** Backward-compatible constructor for spawns without post-commit source cleanup. */
    public CompanionSpawnAdmissionRequest(
            @Nullable String canonicalProfileId,
            @Nullable UUID previousNpcUuid,
            @Nullable CompanionLifecycleState requiredSourceLifecycle,
            boolean allowLegacyAdoption,
            @Nullable UUID ownerId,
            @Nullable String ownerName,
            @Nonnull String worldName,
            int chunkX,
            int chunkZ,
            @Nonnull OwnerPopulationOperation operation,
            @Nonnull String sourceKind,
            @Nonnull String idempotencyKey,
            boolean force
    ) {
        this(canonicalProfileId, previousNpcUuid, requiredSourceLifecycle, allowLegacyAdoption,
                ownerId, ownerName, worldName, chunkX, chunkZ, operation, sourceKind,
                idempotencyKey, force, null, null);
    }

    public boolean replacement() {
        return previousNpcUuid != null || canonicalProfileId != null;
    }

    /** True only for an intentionally provisioned profile that has never had a live NPC UUID. */
    public boolean canonicalNullNpcRestore() {
        return previousNpcUuid == null
                && canonicalProfileId != null
                && requiredSourceLifecycle == CompanionLifecycleState.PROVISIONED_DORMANT;
    }

    @Nullable
    private static String normalizeNullable(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
