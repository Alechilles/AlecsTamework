package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.ownership.OwnerMessageUtil;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.Pair;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Capture/spawn logic for spawner items, including metadata and attachments.
 */
public final class SpawnerFeatureHandler {

    private static final String MASTER_TARGET_SLOT = "MasterTarget";

    private final HytaleLogger logger;
    private final ItemFeatureRegistry registry;
    private final SpawnerLinkedNpcSyncService linkedNpcSyncService;
    private final SpawnerOwnershipPolicyService ownershipPolicyService;
    private final SpawnerSpawnPositionService spawnPositionService;
    private final SpawnerCaptureMetadataService captureMetadataService;
    private final SpawnerRolePolicyService rolePolicyService;
    private final SpawnerItemStackMetadataService itemStackMetadataService;
    private final SpawnerNpcStateService npcStateService;
    private final SpawnerPlayerInventoryService playerInventoryService;
    private final SpawnerAttachmentService attachmentService;
    private final SpawnerEffectService effectService;
    private final SpawnerNpcIdentityService npcIdentityService;

    public SpawnerFeatureHandler(HytaleLogger logger,
                                 ItemFeatureRegistry registry,
                                 CommandLinkedNpcCaptureService captureService) {
        this.logger = logger;
        this.registry = registry;
        this.linkedNpcSyncService = new SpawnerLinkedNpcSyncService(captureService);
        this.ownershipPolicyService = new SpawnerOwnershipPolicyService();
        this.spawnPositionService = new SpawnerSpawnPositionService(logger);
        this.captureMetadataService = new SpawnerCaptureMetadataService(logger, registry);
        this.rolePolicyService = new SpawnerRolePolicyService(logger);
        this.itemStackMetadataService = new SpawnerItemStackMetadataService(registry, captureMetadataService);
        this.npcStateService = new SpawnerNpcStateService();
        this.playerInventoryService = new SpawnerPlayerInventoryService();
        this.attachmentService = new SpawnerAttachmentService(logger);
        this.effectService = new SpawnerEffectService();
        this.npcIdentityService = new SpawnerNpcIdentityService();
    }

    // Entry point for in-world item interaction; decides capture vs spawn.
    public boolean handle(PlayerInteractEvent event, ItemFeatureConfig config) {
        if (event == null) {
            return false;
        }
        if (config == null || !config.isSpawnerEnabled()) {
            return false;
        }

        ItemStack itemStack = event.getItemInHand();
        if (itemStack == null || itemStack.isEmpty()) {
            return false;
        }

        InteractionType action = event.getActionType();
        if (action != InteractionType.Primary && action != InteractionType.Use) {
            logger.at(Level.FINE).log(
                    "Spawner stub: ignoring action=" + action
                            + " item=" + itemStack.getItemId()
            );
            return false;
        }

        Entity targetEntity = event.getTargetEntity();
        if (targetEntity != null) {
            return captureStub(event.getPlayer(), itemStack, targetEntity, config);
        }

        return spawnFromItem(event.getPlayer(), itemStack, config, null, null);
    }

    // Entry point for packet-driven interactions; validates slot/item then forwards to capture/spawn.
    public void handlePacket(Player player,
                             String itemId,
                             int activeHotbarSlot,
                             int targetEntityId,
                             InteractionType interactionType,
                             ItemFeatureConfig config) {
        ItemFeatureConfig activeConfig = config;
        if (interactionType != InteractionType.Primary && interactionType != InteractionType.Use) {
            logger.at(Level.FINE).log(
                    "Spawner stub: packet ignored action=" + interactionType
                            + " item=" + itemId
            );
            return;
        }
        if (activeHotbarSlot < 0) {
            logger.at(Level.FINE).log(
                    "Spawner stub: packet missing hotbar slot for item=" + itemId
            );
            return;
        }
        if (player == null) {
            logger.at(Level.FINE).log(
                    "Spawner stub: packet missing player for item=" + itemId
                            + " slot=" + activeHotbarSlot
            );
            return;
        }

        ItemStack itemStack = playerInventoryService.getHotbarItem(player, activeHotbarSlot);
        if (itemStack == null || itemStack.isEmpty()) {
            logger.at(Level.FINE).log(
                    "Spawner stub: packet empty slot item=" + itemId
                            + " slot=" + activeHotbarSlot
            );
            return;
        }
        if (!itemId.equals(itemStack.getItemId())) {
            logger.at(Level.FINE).log(
                    "Spawner stub: packet item mismatch itemId=" + itemId
                            + " slotItem=" + itemStack.getItemId()
                            + " slot=" + activeHotbarSlot
            );
            if (registry != null) {
                ItemFeatureConfig slotConfig = registry.get(itemStack.getItemId());
                if (slotConfig != null) {
                    activeConfig = slotConfig;
                    itemId = itemStack.getItemId();
                    logger.at(Level.FINE).log(
                            "Spawner stub: packet using slot item config item=" + itemId
                    );
                }
            }
        }
        if (activeConfig == null || !activeConfig.isSpawnerEnabled()) {
            return;
        }

        if (targetEntityId > 0) {
            captureStub(player, itemStack, targetEntityId, activeConfig, activeHotbarSlot);
            return;
        }

        spawnFromItem(player, itemStack, activeConfig, activeHotbarSlot, null);
    }

    private boolean captureStub(Player player, ItemStack itemStack, Entity targetEntity, ItemFeatureConfig config) {
        if (player == null || itemStack == null || config == null || targetEntity == null) {
            return false;
        }
        if (!(targetEntity instanceof NPCEntity)) {
            return false;
        }
        Ref<EntityStore> targetRef = ((NPCEntity) targetEntity).getReference();
        if (targetRef == null || !targetRef.isValid()) {
            return false;
        }
        return captureFromNpcAction(player, targetRef, itemStack, config);
    }

    private void captureStub(Player player, ItemStack itemStack, int targetEntityId, ItemFeatureConfig config, int activeHotbarSlot) {
        if (player == null || itemStack == null || config == null) {
            return;
        }
        Ref<EntityStore> targetRef = playerInventoryService.resolveEntityRef(player, targetEntityId, null);
        if (targetRef == null || !targetRef.isValid()) {
            return;
        }
        captureFromNpcAction(player, targetRef, itemStack, config);
    }

    public boolean canCaptureInteraction(Player player, Ref<EntityStore> targetRef, ItemStack itemStack) {
        if (player == null || targetRef == null || itemStack == null || itemStack.isEmpty()) {
            return false;
        }
        ItemFeatureConfig config = resolveConfigForItem(itemStack);
        if (config == null || !config.isSpawnerEnabled()) {
            return false;
        }
        if (itemStackMetadataService.isAlreadyCaptured(itemStack)) {
            return false;
        }
        return canCapture(player, targetRef, config, itemStack);
    }

    public boolean canSpawnInteraction(ItemStack itemStack) {
        if (itemStack == null || itemStack.isEmpty()) {
            return false;
        }
        ItemFeatureConfig baseConfig = resolveConfigForItem(itemStack);
        ItemFeatureConfig config = buildSpawnerConfigForInteraction(baseConfig, null);
        if (config == null || !config.isSpawnerEnabled()) {
            return false;
        }
        if (isCooldownActive(itemStack, TameworkMetadataKeys.SPAWN_COOLDOWN_UNTIL, config.getSpawnCooldownMs())) {
            return false;
        }
        if (!itemStackMetadataService.isFilledItem(itemStack, config)) {
            return false;
        }
        String roleId = rolePolicyService.resolveSpawnRoleId(itemStack);
        if (roleId == null || roleId.isBlank()) {
            return false;
        }
        return rolePolicyService.isRoleAllowed(roleId, config);
    }

    // Used by TameworkSpawnInteraction: capture from a targeted NPC using the held spawner item.
    public boolean captureFromItemInteraction(Player player, ItemStack itemStack, Ref<EntityStore> targetRef) {
        if (player == null || itemStack == null || itemStack.isEmpty() || targetRef == null) {
            return false;
        }
        ItemFeatureConfig config = resolveConfigForItem(itemStack);
        if (config == null || !config.isSpawnerEnabled()) {
            return false;
        }
        if (itemStackMetadataService.isAlreadyCaptured(itemStack)) {
            logger.at(Level.FINE).log(
                    "Spawner stub: capture denied (item already captured) item=" + itemStack.getItemId()
            );
            return false;
        }
        return captureFromNpcAction(player, targetRef, itemStack, config);
    }

    // Used by TameworkSpawnInteraction: builds a minimal config to spawn from the held item.
    public boolean spawnFromItemInteraction(Player player,
                                            ItemStack itemStack,
                                            String emptyItemIdOverride,
                                            Boolean spawnAssignsOwnerOverride) {
        ItemFeatureConfig baseConfig = resolveConfigForItem(itemStack);
        ItemFeatureConfig config = buildSpawnerConfigForInteraction(
                baseConfig,
                spawnAssignsOwnerOverride
        );
        if (config == null || !config.isSpawnerEnabled()) {
            return false;
        }
        return spawnFromItem(player, itemStack, config, null, emptyItemIdOverride);
    }

    private boolean spawnFromItem(Player player, ItemStack itemStack, ItemFeatureConfig config, Integer hotbarSlot, String emptyItemIdOverride) {
        if (player == null || itemStack == null || config == null) {
            return false;
        }
        if (!config.isSpawnerEnabled()) {
            return false;
        }
        if (isCooldownActive(itemStack, TameworkMetadataKeys.SPAWN_COOLDOWN_UNTIL, config.getSpawnCooldownMs())) {
            return false;
        }
        if (!itemStackMetadataService.isFilledItem(itemStack, config)) {
            return false;
        }
        UUID capturedNpcUuid = itemStack.getFromMetadataOrNull(TameworkMetadataKeys.TARGET_UUID, Codec.UUID_STRING);
        CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot capturedSnapshot =
                linkedNpcSyncService.getCapturedSnapshot(capturedNpcUuid);

        String roleId = rolePolicyService.resolveSpawnRoleId(itemStack);
        if (roleId == null || roleId.isBlank()) {
            logger.at(Level.FINE).log("Spawner spawn: missing role for item=" + itemStack.getItemId());
            return false;
        }
        if (!rolePolicyService.isRoleAllowed(roleId, config)) {
            logger.at(Level.FINE).log("Spawner spawn: role not allowed role=" + roleId);
            return false;
        }

        World world = player.getWorld();
        if (world == null) {
            return false;
        }

        Vector3d spawnPosition = spawnPositionService.resolveSpawnPosition(player, config);
        if (spawnPosition == null) {
            return false;
        }
        if (!spawnPositionService.isWithinSpawnDistance(player, spawnPosition, config)) {
            return false;
        }

        UUID ownerUuid = itemStack.getFromMetadataOrNull(TameworkMetadataKeys.OWNER_UUID, Codec.UUID_STRING);
        if (!ownershipPolicyService.isSpawnAllowed(player.getUuid(), ownerUuid, config)) {
            return false;
        }
        boolean tamed = Boolean.TRUE.equals(itemStack.getFromMetadataOrNull(TameworkMetadataKeys.TAMED, Codec.BOOLEAN));
        if (ownerUuid == null && config.isSpawnAssignsOwner()) {
            ownerUuid = player.getUuid();
        }

        Store<EntityStore> store = world.getEntityStore().getStore();
        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin == null) {
            return false;
        }
        int roleIndex = npcPlugin.getIndex(roleId);
        if (roleIndex < 0) {
            logger.at(Level.FINE).log("Spawner spawn: unknown role=" + roleId);
            return false;
        }
        Ref<EntityStore> playerRef = player.getReference();
        Vector3f rotation = spawnPositionService.resolveSpawnRotation(store, playerRef, spawnPosition);
        Pair<Ref<EntityStore>, NPCEntity> spawned = npcPlugin.spawnEntity(store, roleIndex, spawnPosition, rotation, null, null);
        if (spawned == null || spawned.first() == null || spawned.second() == null) {
            logger.at(Level.FINE).log("Spawner spawn: failed to spawn role=" + roleId);
            return false;
        }

        Ref<EntityStore> npcRef = spawned.first();
        NPCEntity npc = spawned.second();
        attachmentService.applyAttachments(itemStack, npcRef, npc, store);
        npcStateService.applyOwner(config, npcRef, npc, playerRef, ownerUuid, world);
        npcStateService.applyTamed(npcRef, tamed, world);
        npcStateService.applyCapturedName(itemStack, npcRef, store);
        linkedNpcSyncService.restoreCommandLinksFromCapturedSnapshot(npcRef, store, ownerUuid, capturedSnapshot);
        UUID spawnedNpcUuid = npc.getUuid();
        if (capturedNpcUuid != null && spawnedNpcUuid != null) {
            linkedNpcSyncService.remapLinkedNpcRecordsAfterRespawn(player, capturedNpcUuid, spawnedNpcUuid);
        }
        linkedNpcSyncService.clearCapturedSnapshotIfPresent(capturedNpcUuid);

        ItemStack updated = itemStack;
        if (itemStackMetadataService.isAlreadyCaptured(itemStack)) {
            String emptyItemId = emptyItemIdOverride != null
                    ? emptyItemIdOverride
                    : itemStackMetadataService.resolveEmptyItemId(itemStack.getItemId());
            if (emptyItemId != null && !emptyItemId.isBlank()) {
                updated = itemStackMetadataService.swapItemId(updated, emptyItemId);
            }
            updated = itemStackMetadataService.clearCapturedMetadata(updated);
        }
        updated = itemStackMetadataService.applyCooldown(updated, TameworkMetadataKeys.SPAWN_COOLDOWN_UNTIL, config.getSpawnCooldownMs());

        boolean updatedOk = hotbarSlot != null
                ? playerInventoryService.updateHotbarSlot(player, hotbarSlot, updated)
                : playerInventoryService.updateHeldItem(player, updated);
        if (!updatedOk) {
            logger.at(Level.WARNING).log("Spawner spawn: failed to update held item.");
            return false;
        }

        effectService.playSpawnEffects(world, npcRef, config);
        return true;
    }

    private ItemFeatureConfig resolveConfigForItem(ItemStack itemStack) {
        if (registry == null || itemStack == null) {
            return null;
        }
        String itemId = itemStack.getItemId();
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        ItemFeatureConfig config = registry.get(itemId);
        if (config != null) {
            return config;
        }
        String emptyItemId = itemStackMetadataService.resolveEmptyItemId(itemId);
        if (emptyItemId != null && !emptyItemId.isBlank()) {
            return registry.get(emptyItemId);
        }
        return null;
    }


    private ItemFeatureConfig buildSpawnerConfigForInteraction(ItemFeatureConfig baseConfig,
                                                               Boolean spawnAssignsOwnerOverride) {
        if (baseConfig == null) {
            return null;
        }
        boolean spawnAssignsOwner = spawnAssignsOwnerOverride != null
                ? spawnAssignsOwnerOverride
                : baseConfig.isSpawnAssignsOwner();
        return ItemFeatureConfig.builder()
                .spawnerEnabled(baseConfig.isSpawnerEnabled())
                .whistleEnabled(baseConfig.isWhistleEnabled())
                .captureClearsOwner(baseConfig.isCaptureClearsOwner())
                .captureRequireTamed(baseConfig.isCaptureRequireTamed())
                .captureOwnerRestricted(baseConfig.isCaptureOwnerRestricted())
                .spawnAssignsOwner(spawnAssignsOwner)
                .spawnOwnerRestricted(baseConfig.isSpawnOwnerRestricted())
                .whistleRadius(baseConfig.getWhistleRadius())
                .spawnerRoleAllowlist(baseConfig.getSpawnerRoleAllowlist())
                .spawnerRoleDenylist(baseConfig.getSpawnerRoleDenylist())
                .spawnerRoleListMode(baseConfig.getSpawnerRoleListMode())
                                .captureRequireOwnerOverride(baseConfig.getCaptureRequireOwnerOverride())
                .spawnRequireOwnerOverride(baseConfig.getSpawnRequireOwnerOverride())
                .captureParticleSystem(baseConfig.getCaptureParticleSystem())
                .spawnParticleSystem(baseConfig.getSpawnParticleSystem())
                .captureSoundEvent(baseConfig.getCaptureSoundEvent())
                .spawnSoundEvent(baseConfig.getSpawnSoundEvent())
                .captureCooldownMs(baseConfig.getCaptureCooldownMs())
                .spawnCooldownMs(baseConfig.getSpawnCooldownMs())
                .captureMaxDistance(baseConfig.getCaptureMaxDistance())
                .spawnMaxDistance(baseConfig.getSpawnMaxDistance())
                .spawnerFilledItemId(baseConfig.getSpawnerFilledItemId())
                .spawnerIconDefault(baseConfig.getSpawnerIconDefault())
                .spawnerIconOverrides(baseConfig.getSpawnerIconOverrides())
                .spawnerIconOverridesByRole(baseConfig.getSpawnerIconOverridesByRole())
                .build();
    }

    // Called by NPC action chains to capture an NPC into the held spawner item.
    public boolean captureFromNpcAction(Player player, Ref<EntityStore> targetRef, ItemStack itemStack, ItemFeatureConfig config) {
        if (player == null || targetRef == null || itemStack == null || config == null) {
            return false;
        }
        if (itemStackMetadataService.isAlreadyCaptured(itemStack)) {
            logger.at(Level.FINE).log(
                    "Spawner stub: capture denied (item already captured) item=" + itemStack.getItemId()
            );
            return false;
        }
        if (!canCapture(player, targetRef, config, itemStack)) {
            return false;
        }
        SpawnerCaptureMetadataService.CaptureInfo captureInfo = captureMetadataService.buildCaptureInfo(
                player,
                targetRef,
                npcIdentityService::resolveDisplayName
        );
        String attachmentsJson = captureInfo.attachmentsJson();
        if (attachmentsJson != null && !attachmentsJson.isBlank()) {
            logger.at(Level.FINE).log(
                    "Spawner capture attachments: item=" + itemStack.getItemId() + " attachments=" + attachmentsJson
            );
        }
        logger.at(Level.FINE).log(
                "Spawner capture debug: item=" + itemStack.getItemId()
                        + " modelAssetId=" + npcIdentityService.resolveModelAssetId(player, targetRef)
                        + " attachmentsPresent=" + (attachmentsJson != null && !attachmentsJson.isBlank())
        );
        String fullItemIcon = captureMetadataService.resolveFullItemIcon(
                config,
                attachmentsJson,
                itemStack.getItemId(),
                captureInfo.npcNameKey()
        );

        World world = player.getWorld();
        UUID targetUuid = linkedNpcSyncService.resolveEntityUuid(player, targetRef);
        UUID existingOwner = npcStateService.resolveOwnerFromComponent(targetRef, world);
        UUID ownerToStore = null;
        if (!config.isCaptureClearsOwner()) {
            ownerToStore = existingOwner != null ? existingOwner : player.getUuid();
        }
        String snapshotDisplayName = (captureInfo.capturedName() != null
                && captureInfo.capturedName().name() != null
                && !captureInfo.capturedName().name().isBlank())
                ? captureInfo.capturedName().name()
                : null;
        String snapshotRoleId = null;
        if (world != null) {
            Store<EntityStore> store = world.getEntityStore().getStore();
            NPCEntity npc = store.getComponent(targetRef, NPCEntity.getComponentType());
            if (npc != null) {
                snapshotRoleId = npcIdentityService.resolveRoleId(npc);
                if (snapshotDisplayName == null || snapshotDisplayName.isBlank()) {
                    snapshotDisplayName = npcIdentityService.resolveDisplayName(targetRef, store, npc);
                }
            }
        }
        linkedNpcSyncService.publishCapturedLinkedNpcSnapshot(
                targetRef,
                world,
                targetUuid,
                existingOwner,
                snapshotRoleId,
                snapshotDisplayName
        );

        ItemStack updated = itemStackMetadataService.swapItemId(itemStack, config.getSpawnerFilledItemId())
                .withMetadata(TameworkMetadataKeys.CAPTURED, Codec.BOOLEAN, true)
                .withMetadata(TameworkMetadataKeys.TARGET_UUID, Codec.UUID_STRING, targetUuid);
        if (attachmentsJson != null) {
            updated = updated.withMetadata(TameworkMetadataKeys.ATTACHMENTS, Codec.STRING, attachmentsJson);
        }
        boolean tamed = npcStateService.resolveTamedState(targetRef, world);
        if (tamed) {
            updated = updated.withMetadata(TameworkMetadataKeys.TAMED, Codec.BOOLEAN, true);
        }
        updated = itemStackMetadataService.applyOwnerMetadata(updated, ownerToStore);
        if (captureInfo.npcNameKey() != null && !captureInfo.npcNameKey().isBlank()) {
            updated = updated.withMetadata(TameworkMetadataKeys.CAPTURE_ROLE_ID, Codec.STRING, captureInfo.npcNameKey());
        } else {
            updated = itemStackMetadataService.clearMetadataKey(updated, TameworkMetadataKeys.CAPTURE_ROLE_ID);
        }
        updated = captureMetadataService.applyCapturedMetadata(updated, captureInfo, fullItemIcon);
        updated = captureMetadataService.applyCapturedNameMetadata(updated, captureInfo);
        updated = itemStackMetadataService.applyCooldown(updated, TameworkMetadataKeys.CAPTURE_COOLDOWN_UNTIL, config.getCaptureCooldownMs());

        if (!playerInventoryService.updateHeldItem(player, updated)) {
            logger.at(Level.WARNING).log("Spawner stub: failed to update held item.");
            return false;
        }
        effectService.playCaptureEffects(world, targetRef, config);
        clearOwnerIfConfigured(player, config, targetRef);
        despawnNpc(player, targetRef, null);

        logger.at(Level.FINE).log(
                "Spawner stub: capture request item=" + itemStack.getItemId()
                        + " targetUuid=" + targetUuid
                        + " captureClearsOwner=" + config.isCaptureClearsOwner()
        );
        return true;
    }
    private boolean canCapture(Player player, Ref<EntityStore> targetRef, ItemFeatureConfig config, ItemStack itemStack) {
        if (player == null || targetRef == null || config == null || itemStack == null) {
            return false;
        }
        if (!targetRef.isValid()) {
            return false;
        }
        if (isCooldownActive(itemStack, TameworkMetadataKeys.CAPTURE_COOLDOWN_UNTIL, config.getCaptureCooldownMs())) {
            return false;
        }
        World world = player.getWorld();
        if (world == null) {
            return false;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        NPCEntity npc = store.getComponent(targetRef, NPCEntity.getComponentType());
        if (npc == null) {
            return false;
        }
        String roleId = rolePolicyService.resolveRoleIdFromNpc(npc);
        if (!rolePolicyService.isRoleAllowed(roleId, config)) {
            return false;
        }
        if (config.isCaptureRequireTamed() && !npcStateService.resolveTamedState(targetRef, world)) {
            String npcName = npcIdentityService.resolveDisplayName(npc);
            OwnerMessageUtil.sendUntamed(player, npcName);
            return false;
        }
        UUID ownerUuid = npcStateService.resolveOwnerFromComponent(targetRef, world);
        if (!ownershipPolicyService.isCaptureAllowed(player.getUuid(), ownerUuid, config)) {
            if (ownerUuid != null) {
                String npcName = npcIdentityService.resolveDisplayName(npc);
                String ownerName = npcStateService.resolveOwnerNameFromComponent(targetRef, world);
                OwnerMessageUtil.sendDenied(player, npcName, ownerName, ownerUuid, "capture");
            }
            return false;
        }
        return isWithinCaptureDistance(player, targetRef, config, store);
    }

    private boolean isWithinCaptureDistance(Player player,
                                            Ref<EntityStore> targetRef,
                                            ItemFeatureConfig config,
                                            Store<EntityStore> store) {
        if (player == null || targetRef == null || config == null || store == null) {
            return false;
        }
        double maxDistance = config.getCaptureMaxDistance();
        if (maxDistance <= 0) {
            return true;
        }
        Ref<EntityStore> playerRef = player.getReference();
        if (playerRef == null || !playerRef.isValid()) {
            return false;
        }
        TransformComponent playerTransform = store.getComponent(playerRef, TransformComponent.getComponentType());
        TransformComponent targetTransform = store.getComponent(targetRef, TransformComponent.getComponentType());
        if (playerTransform == null || targetTransform == null) {
            return false;
        }
        Vector3d p = new Vector3d(playerTransform.getPosition());
        Vector3d t = new Vector3d(targetTransform.getPosition());
        double dx = p.x - t.x;
        double dy = p.y - t.y;
        double dz = p.z - t.z;
        double maxDistSq = maxDistance * maxDistance;
        return (dx * dx + dy * dy + dz * dz) <= maxDistSq;
    }

    private boolean isCooldownActive(ItemStack itemStack, String key, int cooldownMs) {
        if (itemStack == null || key == null || cooldownMs <= 0) {
            return false;
        }
        Long until = itemStack.getFromMetadataOrNull(key, Codec.LONG);
        if (until == null) {
            return false;
        }
        return until > System.currentTimeMillis();
    }

    private void despawnNpc(Player player, Ref<EntityStore> targetRef, Entity targetEntity) {
        if (player == null) {
            return;
        }
        if (targetEntity instanceof NPCEntity npcEntity) {
            npcEntity.setToDespawn();
            return;
        }
        if (targetRef == null || !targetRef.isValid()) {
            return;
        }
        World world = player.getWorld();
        if (world == null) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        NPCEntity npc = store.getComponent(targetRef, NPCEntity.getComponentType());
        if (npc != null) {
            npc.setToDespawn();
        }
    }
    private void clearOwnerIfConfigured(Player player, ItemFeatureConfig config, Ref<EntityStore> targetRef) {
        if (player == null || !config.isCaptureClearsOwner() || targetRef == null) {
            return;
        }
        World world = player.getWorld();
        if (world == null) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        NPCEntity npc = store.getComponent(targetRef, NPCEntity.getComponentType());
        if (npc == null || npc.getRole() == null) {
            return;
        }
        ComponentType<EntityStore, TameworkOwnerComponent> type = TameworkOwnerComponent.getComponentType();
        if (type != null) {
            store.putComponent(targetRef, type, new TameworkOwnerComponent(null, null));
        }
        npc.getRole().getMarkedEntitySupport().setMarkedEntity(MASTER_TARGET_SLOT, null);
    }

}


























