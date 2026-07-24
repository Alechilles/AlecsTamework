package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Complete immutable caller context for one live spawner capture.
 *
 * <p>The intent key must remain stable for retries of the same gameplay action.</p>
 */
public record SpawnerCaptureIntent(
        @Nonnull String intentKey,
        @Nonnull UUID actorUuid,
        @Nonnull String worldKey,
        int sourceSlot,
        @Nonnull ItemStack sourceStack,
        @Nonnull ItemStack filledArtifactStack,
        @Nullable Ref<EntityStore> sourceRef,
        @Nullable Store<EntityStore> sourceStore,
        @Nonnull ProfileId profileId,
        @Nonnull NpcAlias sourceAlias,
        @Nullable OwnerId liveOwnerId,
        @Nullable OwnerId resultingOwnerId,
        @Nullable String resultingOwnerName,
        @Nullable String roleId,
        @Nullable SpawnerPublishedEffect publishedEffect
) {
    public SpawnerCaptureIntent {
        if (intentKey == null || intentKey.isBlank()
                || actorUuid == null || worldKey == null
                || worldKey.isBlank() || sourceSlot < 0
                || sourceStack == null || filledArtifactStack == null
                || profileId == null || sourceAlias == null) {
            throw new IllegalArgumentException(
                    "Complete spawner capture intent is required"
            );
        }
        intentKey = intentKey.trim();
        worldKey = worldKey.trim();
        roleId = roleId == null || roleId.isBlank()
                ? null
                : roleId.trim();
        resultingOwnerName = resultingOwnerName == null
                || resultingOwnerName.isBlank()
                ? null
                : resultingOwnerName.trim();
    }

    SpawnerCaptureIntent withProfileId(@Nonnull ProfileId canonicalProfileId) {
        return new SpawnerCaptureIntent(
                intentKey,
                actorUuid,
                worldKey,
                sourceSlot,
                sourceStack,
                filledArtifactStack,
                sourceRef,
                sourceStore,
                canonicalProfileId,
                sourceAlias,
                liveOwnerId,
                resultingOwnerId,
                resultingOwnerName,
                roleId,
                publishedEffect
        );
    }

    SpawnerCaptureContext frozenContext() {
        return new SpawnerCaptureContext(
                intentKey,
                actorUuid,
                worldKey,
                sourceSlot,
                profileId,
                sourceAlias,
                liveOwnerId,
                resultingOwnerId,
                roleId,
                publishedEffect
        );
    }
}
