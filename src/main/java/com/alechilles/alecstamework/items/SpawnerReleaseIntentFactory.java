package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Prepares one release from a filled capture item: the exact hotbar slot, the empty item that
 * replaces it, the placement and the spawn effect. Who may release is the caller's decision
 * (the capture item ownership mode); population caps are the restore's job.
 */
final class SpawnerReleaseIntentFactory {
    private final SpawnerSpawnPositionService positions;
    private final SpawnerPlayerInventoryService inventory;
    private final SpawnerItemStackMetadataService itemMetadata;

    SpawnerReleaseIntentFactory(
            SpawnerSpawnPositionService positions,
            SpawnerPlayerInventoryService inventory,
            SpawnerItemStackMetadataService itemMetadata
    ) {
        this.positions = positions;
        this.inventory = inventory;
        this.itemMetadata = itemMetadata;
    }

    /**
     * Returns null when the release cannot start here: no exact slot, no position in range or no
     * empty item id.
     */
    @Nullable
    PreparedRelease prepare(
            @Nullable Player player,
            @Nullable ItemStack source,
            @Nullable ItemFeatureConfig config,
            @Nullable Integer preferredSlot,
            @Nullable String emptyItemIdOverride
    ) {
        World world = player == null ? null : player.getWorld();
        Store<EntityStore> store = world == null
                || world.getEntityStore() == null
                ? null
                : world.getEntityStore().getStore();
        Integer sourceSlot = inventory.resolveExactHotbarSlot(
                player, source, preferredSlot
        );
        Vector3d position = world == null
                ? null
                : positions.resolveSpawnPosition(player, config);
        if (player == null || source == null || source.isEmpty()
                || config == null || world == null || store == null
                || sourceSlot == null || position == null
                || !positions.isWithinSpawnDistance(
                        player, position, config
                )) {
            return null;
        }
        String emptyItemId = emptyItemIdOverride;
        if (emptyItemId == null || emptyItemId.isBlank()) {
            emptyItemId = itemMetadata.resolveEmptyItemId(
                    source.getItemId()
            );
        }
        if (emptyItemId == null || emptyItemId.isBlank()) {
            return null;
        }
        ItemStack receipt = itemMetadata.clearCapturedMetadata(
                itemMetadata.swapItemId(source, emptyItemId)
        );
        if (receipt == null || receipt.isEmpty()) {
            return null;
        }
        Rotation3f rotation = positions.resolveSpawnRotation(
                store, player.getReference(), position
        );
        CompanionSpawnPlacement placement = new CompanionSpawnPlacement(
                world.getName(),
                position.x,
                position.y,
                position.z,
                rotation.pitch(),
                rotation.yaw(),
                rotation.roll()
        );
        return new PreparedRelease(
                sourceSlot,
                receipt,
                placement,
                new SpawnerPublishedEffect(
                        position.x,
                        position.y,
                        position.z,
                        config.getSpawnParticleSystem(),
                        config.getSpawnSoundEvent()
                )
        );
    }

    /** {@code receipt} is the empty capture item the filled one turns into. */
    record PreparedRelease(
            int slot,
            @Nonnull ItemStack receipt,
            @Nonnull CompanionSpawnPlacement placement,
            @Nonnull SpawnerPublishedEffect effect
    ) {
    }
}
