package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
import com.alechilles.alecstamework.npc.progression.CompanionStatModifierService;
import com.alechilles.alecstamework.ownership.OwnerMessageUtil;
import com.alechilles.alecstamework.ownership.OwnerPopulationCapService;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.Pair;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Applies the released live spawner-item release flow.
 *
 * <p>Release no longer reserves durable population operations. The live owner cap is checked only
 * when the release would create a new owner assignment.
 */
final class SpawnerPreparedSpawnService {
    private final HytaleLogger logger;
    private final SpawnerSpawnPositionService positions;
    private final SpawnerRolePolicyService roles;
    private final SpawnerOwnershipPolicyService ownership;
    private final SpawnerItemStackMetadataService itemMetadata;
    private final SpawnerPlayerInventoryService inventory;
    private final SpawnerNpcStateService npcState;
    private final SpawnerAttachmentService attachments;
    private final SpawnerNpcProgressionMetadataService progression;
    private final SpawnerLinkedNpcSyncService linkedNpcSync;
    private final SpawnerEffectService effects;
    @Nullable
    private final CommandLinkedNpcCoopService coopService;
    private final Consumer<String> debugLog;

    SpawnerPreparedSpawnService(
            HytaleLogger logger,
            SpawnerSpawnPositionService positions,
            SpawnerRolePolicyService roles,
            SpawnerOwnershipPolicyService ownership,
            SpawnerItemStackMetadataService itemMetadata,
            SpawnerPlayerInventoryService inventory,
            SpawnerNpcStateService npcState,
            SpawnerAttachmentService attachments,
            SpawnerNpcProgressionMetadataService progression,
            SpawnerLinkedNpcSyncService linkedNpcSync,
            SpawnerEffectService effects,
            @Nullable CommandLinkedNpcCoopService coopService,
            Consumer<String> debugLog) {
        this.logger = logger;
        this.positions = positions;
        this.roles = roles;
        this.ownership = ownership;
        this.itemMetadata = itemMetadata;
        this.inventory = inventory;
        this.npcState = npcState;
        this.attachments = attachments;
        this.progression = progression;
        this.linkedNpcSync = linkedNpcSync;
        this.effects = effects;
        this.coopService = coopService;
        this.debugLog = debugLog;
    }

    boolean schedule(
            Player player,
            ItemStack itemStack,
            ItemFeatureConfig config,
            @Nullable Integer hotbarSlot,
            @Nullable String emptyItemIdOverride) {
        ValidatedSpawn validated = validate(player, itemStack, config);
        if (validated == null) {
            return false;
        }
        UUID ownerUuid = resolveOwner(player, itemStack, config, validated.world());
        if (ownerUuid == null && config.isSpawnAssignsOwner()) {
            return false;
        }
        return spawn(validated, player, itemStack, config, hotbarSlot, emptyItemIdOverride, ownerUuid);
    }

    @Nullable
    private ValidatedSpawn validate(
            Player player, ItemStack itemStack, ItemFeatureConfig config) {
        if (player == null || itemStack == null || config == null) {
            debugLog.accept("spawn denied reason=invalid-input");
            return null;
        }
        if (isCooldownActive(itemStack, config)) {
            deny(player, itemStack, "cooldown");
            return null;
        }
        if (!itemMetadata.isFilledItem(itemStack, config)) {
            deny(player, itemStack, "item-not-filled");
            return null;
        }
        String roleId = roles.resolveSpawnRoleId(itemStack);
        if (roleId == null || roleId.isBlank() || !roles.isRoleAllowed(roleId, config)) {
            deny(player, itemStack, "role-not-allowed");
            return null;
        }
        World world = player.getWorld();
        Vector3d position = world == null ? null : positions.resolveSpawnPosition(player, config);
        if (world == null || position == null
                || !positions.isWithinSpawnDistance(player, position, config)) {
            deny(player, itemStack, "spawn-position-unavailable");
            return null;
        }
        NPCPlugin plugin = NPCPlugin.get();
        int roleIndex = plugin == null ? -1 : plugin.getIndex(roleId);
        if (plugin == null || roleIndex < 0) {
            deny(player, itemStack, "unknown-role");
            return null;
        }
        return new ValidatedSpawn(world, position, roleId, roleIndex, plugin);
    }

    @Nullable
    private UUID resolveOwner(
            Player player,
            ItemStack itemStack,
            ItemFeatureConfig config,
            World world) {
        UUID ownerUuid = itemStack.getFromMetadataOrNull(
                TameworkMetadataKeys.OWNER_UUID, Codec.UUID_STRING);
        UUID sourceOwnerUuid = itemStack.getFromMetadataOrNull(
                TameworkMetadataKeys.CAPTURE_SOURCE_OWNER_UUID, Codec.UUID_STRING);
        UUID policyOwner = SpawnerOwnershipPolicyService.resolveSpawnPolicyOwner(
                ownerUuid, sourceOwnerUuid, config);
        if (!ownership.isSpawnAllowed(player.getUuid(), policyOwner, config)) {
            deny(player, itemStack, "ownership-policy");
            return null;
        }
        if (ownerUuid != null || !config.isSpawnAssignsOwner()) {
            return ownerUuid;
        }
        OwnerPopulationCapService.Decision cap = OwnerPopulationCapService.evaluateAcquisition(
                world.getEntityStore() == null ? null : world.getEntityStore().getStore(),
                player.getUuid());
        if (cap.allowed()) {
            return player.getUuid();
        }
        OwnerMessageUtil.sendPopulationCapReached(
                player, cap.currentCount(), cap.limit(), cap.scope());
        debugLog.accept("spawn denied reason=owner-cap player=" + player.getUuid()
                + " current=" + cap.currentCount()
                + " limit=" + cap.limit()
                + " scope=" + cap.scope());
        return null;
    }

    private boolean spawn(
            ValidatedSpawn validated,
            Player player,
            ItemStack itemStack,
            ItemFeatureConfig config,
            @Nullable Integer hotbarSlot,
            @Nullable String emptyItemIdOverride,
            @Nullable UUID ownerUuid) {
        Store<EntityStore> store = validated.world().getEntityStore().getStore();
        Ref<EntityStore> playerRef = player.getReference();
        Rotation3f rotation = positions.resolveSpawnRotation(
                store, playerRef, validated.position());
        ItemStack replacement = replacement(
                itemStack, config, emptyItemIdOverride);
        if (!replaceSource(player, hotbarSlot, replacement)) {
            deny(player, itemStack, "update-held-item-failed");
            return false;
        }
        Pair<Ref<EntityStore>, NPCEntity> spawned = validated.plugin().spawnEntity(
                store,
                validated.roleIndex(),
                validated.position(),
                rotation,
                null,
                null);
        if (spawned == null || spawned.first() == null || spawned.second() == null) {
            rollbackSource(player, hotbarSlot, itemStack);
            deny(player, itemStack, "spawn-entity-failed");
            return false;
        }
        finishSpawn(
                validated, player, itemStack, config, ownerUuid,
                playerRef, store, spawned.first(), spawned.second());
        return true;
    }

    private void finishSpawn(
            ValidatedSpawn validated,
            Player player,
            ItemStack itemStack,
            ItemFeatureConfig config,
            @Nullable UUID ownerUuid,
            @Nullable Ref<EntityStore> playerRef,
            Store<EntityStore> store,
            Ref<EntityStore> npcRef,
            NPCEntity npc) {
        UUID capturedNpcUuid = itemStack.getFromMetadataOrNull(
                TameworkMetadataKeys.TARGET_UUID, Codec.UUID_STRING);
        CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot snapshot =
                linkedNpcSync.getCapturedSnapshot(capturedNpcUuid);
        attachments.applyAttachments(itemStack, npcRef, npc, store);
        npcState.applyOwner(config, npcRef, npc, playerRef, ownerUuid, validated.world());
        npcState.applyTamed(npcRef, resolveTamed(itemStack), validated.world());
        npcState.applyCapturedName(itemStack, npcRef, store);
        progression.applyNpcProgressionFromItem(itemStack, npcRef, store);
        refreshProgression(npcRef, npc, store);
        progression.applyNpcHealthFromItem(itemStack, npcRef, store);
        linkedNpcSync.restoreCommandLinksFromCapturedSnapshot(npcRef, store, ownerUuid, snapshot);
        UUID spawnedNpcUuid = npc.getUuid();
        if (capturedNpcUuid != null && spawnedNpcUuid != null) {
            linkedNpcSync.remapLinkedNpcRecordsAfterRespawn(
                    player, capturedNpcUuid, spawnedNpcUuid);
        }
        linkedNpcSync.clearCapturedSnapshotIfPresent(capturedNpcUuid);
        if (coopService != null && capturedNpcUuid != null) {
            coopService.clearCoopSnapshot(capturedNpcUuid);
        }
        effects.playSpawnEffects(validated.world(), npcRef, config);
        debugLog.accept("spawn success item=" + itemStack.getItemId()
                + " role=" + validated.roleId()
                + " player=" + player.getUuid()
                + " spawnedNpc=" + spawnedNpcUuid
                + " appliedOwner=" + ownerUuid);
    }

    private ItemStack replacement(
            ItemStack itemStack,
            ItemFeatureConfig config,
            @Nullable String emptyItemIdOverride) {
        ItemStack updated = itemStack;
        if (itemMetadata.isAlreadyCaptured(itemStack)) {
            String emptyItemId = emptyItemIdOverride != null
                    ? emptyItemIdOverride
                    : itemMetadata.resolveEmptyItemId(itemStack.getItemId());
            if (emptyItemId != null && !emptyItemId.isBlank()) {
                updated = itemMetadata.swapItemId(updated, emptyItemId);
            }
            updated = itemMetadata.clearCapturedMetadata(updated);
        }
        return itemMetadata.applyCooldown(
                updated,
                TameworkMetadataKeys.SPAWN_COOLDOWN_UNTIL,
                config.getSpawnCooldownMs());
    }

    private boolean replaceSource(
            Player player, @Nullable Integer hotbarSlot, ItemStack replacement) {
        return hotbarSlot != null
                ? inventory.updateHotbarSlot(player, hotbarSlot, replacement)
                : inventory.updateHeldItem(player, replacement);
    }

    private void rollbackSource(
            Player player, @Nullable Integer hotbarSlot, ItemStack original) {
        if (!replaceSource(player, hotbarSlot, original)) {
            logger.at(Level.WARNING).log(
                    "Spawner spawn: failed to roll back held item after spawn failure.");
        }
    }

    private void refreshProgression(
            Ref<EntityStore> npcRef, NPCEntity npc, Store<EntityStore> store) {
        CompanionStatModifierService.applyTraitModifiers(npcRef, store);
        CompanionLifeStageService.refreshLifeStage(npcRef, npc, store);
        CompanionLifeStageService.ensureGrowthTickScheduled(npcRef, npc, store);
    }

    private boolean resolveTamed(ItemStack itemStack) {
        return Boolean.TRUE.equals(itemStack.getFromMetadataOrNull(
                TameworkMetadataKeys.TAMED, Codec.BOOLEAN));
    }

    private boolean isCooldownActive(ItemStack itemStack, ItemFeatureConfig config) {
        Long until = itemStack.getFromMetadataOrNull(
                TameworkMetadataKeys.SPAWN_COOLDOWN_UNTIL, Codec.LONG);
        return config.getSpawnCooldownMs() > 0
                && until != null
                && until > System.currentTimeMillis();
    }

    private void deny(Player player, ItemStack itemStack, String reason) {
        debugLog.accept("spawn denied reason=" + reason
                + " player=" + (player == null ? null : player.getUuid())
                + " item=" + (itemStack == null ? null : itemStack.getItemId()));
    }

    private record ValidatedSpawn(
            World world,
            Vector3d position,
            String roleId,
            int roleIndex,
            NPCPlugin plugin) {
    }
}
