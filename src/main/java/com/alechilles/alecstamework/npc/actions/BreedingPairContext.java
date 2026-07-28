package com.alechilles.alecstamework.npc.actions;

import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Small in-memory snapshot carried across the released delayed breeding animation.
 *
 * <p>This is not persisted and has no replay or reservation semantics. Stable parent
 * identifiers are resolved again on the world thread before offspring are spawned.
 */
record BreedingPairContext(
        @Nonnull UUID parentAUuid,
        @Nonnull UUID parentBUuid,
        @Nullable String parentARoleId,
        @Nullable String parentBRoleId,
        int parentARoleIndex,
        int parentBRoleIndex,
        @Nullable Vector3d spawnAnchor,
        @Nonnull BreedingOffspringProgressionService.OwnerSnapshot parentAOwner,
        @Nonnull BreedingOffspringProgressionService.OwnerSnapshot parentBOwner,
        boolean parentATamed,
        boolean parentBTamed,
        @Nullable String breedingConfigId
) {
    BreedingPairContext {
        spawnAnchor = spawnAnchor == null ? null : new Vector3d(spawnAnchor);
    }

    @Override
    public Vector3d spawnAnchor() {
        return spawnAnchor == null ? null : new Vector3d(spawnAnchor);
    }
}
