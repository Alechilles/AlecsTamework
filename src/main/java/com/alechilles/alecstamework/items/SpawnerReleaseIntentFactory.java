package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.items.persistence.SpawnerCapturedArtifactReleaseIntent;
import com.alechilles.alecstamework.ownership.OwnerMessageUtil;
import com.alechilles.alecstamework.ownership.OwnerNameUtil;
import com.alechilles.alecstamework.ownership.OwnerPopulationCapService;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Freezes one filled-spawner source, empty receipt, and exact release placement. */
final class SpawnerReleaseIntentFactory {
    private final SpawnerSpawnPositionService positions;
    private final SpawnerPlayerInventoryService inventory;
    private final SpawnerItemStackMetadataService itemMetadata;
    private final SpawnerOwnershipPolicyService ownership;

    SpawnerReleaseIntentFactory(
            SpawnerSpawnPositionService positions,
            SpawnerPlayerInventoryService inventory,
            SpawnerItemStackMetadataService itemMetadata,
            SpawnerOwnershipPolicyService ownership
    ) {
        this.positions = positions;
        this.inventory = inventory;
        this.itemMetadata = itemMetadata;
        this.ownership = ownership;
    }

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
        Rotation3f rotation = positions.resolveSpawnRotation(
                store, player.getReference(), position
        );
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
        UUID capturedOwner = source.getFromMetadataOrNull(
                TameworkMetadataKeys.OWNER_UUID,
                Codec.UUID_STRING
        );
        UUID captureSourceOwner = source.getFromMetadataOrNull(
                TameworkMetadataKeys.CAPTURE_SOURCE_OWNER_UUID,
                Codec.UUID_STRING
        );
        UUID policyOwner = SpawnerOwnershipPolicyService
                .resolveSpawnPolicyOwner(
                        capturedOwner, captureSourceOwner, config
                );
        if (!ownership.isSpawnAllowed(
                player.getUuid(), policyOwner, config
        )) {
            return null;
        }
        OwnerId ownerAssignment = null;
        String ownerAssignmentName = null;
        if (capturedOwner == null && config.isSpawnAssignsOwner()) {
            OwnerPopulationCapService.Decision cap =
                    OwnerPopulationCapService.evaluateAcquisition(
                            store, player.getUuid()
                    );
            if (!cap.allowed()) {
                denyOwnerAssignment(player, cap);
                return null;
            }
            ownerAssignment = new OwnerId(player.getUuid());
            ownerAssignmentName = OwnerNameUtil.resolve(player);
        }
        String intentKey = player.getUuid()
                + ":" + world.getName()
                + ":" + sourceSlot
                + ":" + SpawnerSourceFingerprint.of(source);
        SpawnerCapturedArtifactReleaseIntent intent =
                new SpawnerCapturedArtifactReleaseIntent(
                        intentKey,
                        player.getUuid(),
                        world.getName(),
                        sourceSlot,
                        source,
                        receipt,
                        ownerAssignment,
                        ownerAssignmentName
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
        return new PreparedRelease(intent, placement);
    }

    record PreparedRelease(
            SpawnerCapturedArtifactReleaseIntent intent,
            CompanionSpawnPlacement placement
    ) {
    }

    private void denyOwnerAssignment(
            Player player,
            OwnerPopulationCapService.Decision cap
    ) {
        if ("owner-cap-reached".equals(cap.reason())) {
            OwnerMessageUtil.sendPopulationCapReached(
                    player,
                    cap.currentCount(),
                    cap.limit(),
                    cap.scope()
            );
            return;
        }
        OwnerMessageUtil.sendPopulationUnavailable(player, cap.reason());
    }
}
