package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
import com.alechilles.alecstamework.npc.progression.CompanionStatModifierService;
import com.alechilles.alecstamework.ownership.OwnerMessageUtil;
import com.alechilles.alecstamework.ownership.OwnerPopulationCapService;
import com.alechilles.alecstamework.persistence.TameworkSettingsStore;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.Pair;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nullable;

/**
 * Capture/spawn logic for spawner items, including metadata and attachments.
 */
public final class SpawnerFeatureHandler {

    private final HytaleLogger logger;
    private final ItemFeatureRegistry registry;
    private final SpawnerLinkedNpcSyncService linkedNpcSyncService;
    private final SpawnerOwnershipPolicyService ownershipPolicyService;
    private final SpawnerSpawnPositionService spawnPositionService;
    private final SpawnerCaptureMetadataService captureMetadataService;
    private final SpawnerNpcProgressionMetadataService progressionMetadataService;
    private final SpawnerRolePolicyService rolePolicyService;
    private final SpawnerItemStackMetadataService itemStackMetadataService;
    private final SpawnerNpcStateService npcStateService;
    private final SpawnerPlayerInventoryService playerInventoryService;
    private final SpawnerAttachmentService attachmentService;
    private final SpawnerEffectService effectService;
    private final SpawnerNpcIdentityService npcIdentityService;
    private final SpawnerCaptureFinalizerService captureFinalizerService;
    private final SpawnerCapturePolicyService capturePolicyService;
    @Nullable
    private final CommandNpcRelocationService relocationService;
    @Nullable
    private final CommandLinkedNpcLostService lostService;
    @Nullable
    private final CommandLinkedNpcCoopService coopService;

    public SpawnerFeatureHandler(HytaleLogger logger,
                                 ItemFeatureRegistry registry,
                                 CommandLinkedNpcCaptureService captureService,
                                 @Nullable CommandLinkedNpcCoopService coopService,
                                 @Nullable CommandNpcRelocationService relocationService,
                                 @Nullable CommandLinkedNpcLostService lostService) {
        this.logger = logger;
        this.registry = registry;
        this.linkedNpcSyncService = new SpawnerLinkedNpcSyncService(captureService);
        this.ownershipPolicyService = new SpawnerOwnershipPolicyService();
        this.spawnPositionService = new SpawnerSpawnPositionService(logger);
        this.captureMetadataService = new SpawnerCaptureMetadataService(logger, registry);
        this.progressionMetadataService = new SpawnerNpcProgressionMetadataService();
        this.rolePolicyService = new SpawnerRolePolicyService(logger);
        this.itemStackMetadataService = new SpawnerItemStackMetadataService(
                registry,
                captureMetadataService,
                progressionMetadataService
        );
        this.npcStateService = new SpawnerNpcStateService();
        this.playerInventoryService = new SpawnerPlayerInventoryService();
        this.attachmentService = new SpawnerAttachmentService(logger);
        this.effectService = new SpawnerEffectService();
        this.npcIdentityService = new SpawnerNpcIdentityService();
        this.captureFinalizerService = new SpawnerCaptureFinalizerService();
        this.capturePolicyService = new SpawnerCapturePolicyService(
                logger,
                rolePolicyService,
                npcStateService,
                ownershipPolicyService,
                npcIdentityService
        );
        this.coopService = coopService;
        this.relocationService = relocationService;
        this.lostService = lostService;
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
        return capturePolicyService.canCapture(player, targetRef, config, itemStack);
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
            logSpawnerFlowDebug("spawn denied reason=invalid-input");
            return false;
        }
        UUID playerUuid = player.getUuid();
        config = buildSpawnerConfigForInteraction(config, null);
        if (config == null) {
            logSpawnerFlowDebug("spawn denied reason=missing-config player=" + playerUuid + " item=" + itemStack.getItemId());
            return false;
        }
        if (!config.isSpawnerEnabled()) {
            logSpawnerFlowDebug("spawn denied reason=spawner-disabled player=" + playerUuid + " item=" + itemStack.getItemId());
            return false;
        }
        if (isCooldownActive(itemStack, TameworkMetadataKeys.SPAWN_COOLDOWN_UNTIL, config.getSpawnCooldownMs())) {
            logSpawnerFlowDebug("spawn denied reason=cooldown player=" + playerUuid + " item=" + itemStack.getItemId());
            return false;
        }
        if (!itemStackMetadataService.isFilledItem(itemStack, config)) {
            logSpawnerFlowDebug("spawn denied reason=item-not-filled player=" + playerUuid + " item=" + itemStack.getItemId());
            return false;
        }
        UUID capturedNpcUuid = itemStack.getFromMetadataOrNull(TameworkMetadataKeys.TARGET_UUID, Codec.UUID_STRING);
        CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot capturedSnapshot =
                linkedNpcSyncService.getCapturedSnapshot(capturedNpcUuid);

        String roleId = rolePolicyService.resolveSpawnRoleId(itemStack);
        if (roleId == null || roleId.isBlank()) {
            logSpawnerFlowDebug("spawn denied reason=missing-role player=" + playerUuid + " item=" + itemStack.getItemId());
            return false;
        }
        if (!rolePolicyService.isRoleAllowed(roleId, config)) {
            logSpawnerFlowDebug("spawn denied reason=role-not-allowed player=" + playerUuid + " role=" + roleId);
            return false;
        }

        World world = player.getWorld();
        if (world == null) {
            logSpawnerFlowDebug("spawn denied reason=missing-world player=" + playerUuid + " item=" + itemStack.getItemId());
            return false;
        }

        Vector3d spawnPosition = spawnPositionService.resolveSpawnPosition(player, config);
        if (spawnPosition == null) {
            logSpawnerFlowDebug("spawn denied reason=no-spawn-position player=" + playerUuid + " item=" + itemStack.getItemId());
            return false;
        }
        if (!spawnPositionService.isWithinSpawnDistance(player, spawnPosition, config)) {
            logSpawnerFlowDebug("spawn denied reason=spawn-distance player=" + playerUuid + " maxDistance=" + config.getSpawnMaxDistance());
            return false;
        }

        UUID ownerUuid = itemStack.getFromMetadataOrNull(TameworkMetadataKeys.OWNER_UUID, Codec.UUID_STRING);
        UUID captureSourceOwnerUuid = itemStack.getFromMetadataOrNull(TameworkMetadataKeys.CAPTURE_SOURCE_OWNER_UUID, Codec.UUID_STRING);
        UUID ownerForSpawnPolicy = ownerUuid != null ? ownerUuid : captureSourceOwnerUuid;
        if (!ownershipPolicyService.isSpawnAllowed(playerUuid, ownerForSpawnPolicy, config)) {
            logSpawnerFlowDebug(
                    "spawn denied by ownership policy item=" + itemStack.getItemId()
                            + " player=" + playerUuid
                            + " itemOwner=" + ownerUuid
                            + " captureSourceOwner=" + captureSourceOwnerUuid
                            + " ownerForPolicy=" + ownerForSpawnPolicy
                            + " requireOwnerOverride=" + config.getSpawnRequireOwnerOverride()
                            + " ownerRestricted=" + config.isSpawnOwnerRestricted()
            );
            return false;
        }
        boolean tamed = Boolean.TRUE.equals(itemStack.getFromMetadataOrNull(TameworkMetadataKeys.TAMED, Codec.BOOLEAN));
        if (ownerUuid == null && config.isSpawnAssignsOwner()) {
            UUID playerId = playerUuid;
            OwnerPopulationCapService.Decision decision = OwnerPopulationCapService.evaluateAcquisition(
                    world.getEntityStore() != null ? world.getEntityStore().getStore() : null,
                    playerId
            );
            if (!decision.allowed()) {
                OwnerMessageUtil.sendPopulationCapReached(
                        player,
                        decision.currentCount(),
                        decision.limit(),
                        decision.scope()
                );
                logSpawnerFlowDebug(
                        "spawn denied reason=owner-cap player=" + playerUuid
                                + " current=" + decision.currentCount()
                                + " limit=" + decision.limit()
                                + " scope=" + decision.scope()
                );
                return false;
            }
            ownerUuid = playerId;
        }

        Store<EntityStore> store = world.getEntityStore().getStore();
        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin == null) {
            logSpawnerFlowDebug("spawn denied reason=npc-plugin-missing player=" + playerUuid);
            return false;
        }
        int roleIndex = npcPlugin.getIndex(roleId);
        if (roleIndex < 0) {
            logSpawnerFlowDebug("spawn denied reason=unknown-role player=" + playerUuid + " role=" + roleId);
            return false;
        }
        Ref<EntityStore> playerRef = player.getReference();
        Vector3f rotation = spawnPositionService.resolveSpawnRotation(store, playerRef, spawnPosition);
        Pair<Ref<EntityStore>, NPCEntity> spawned = npcPlugin.spawnEntity(store, roleIndex, spawnPosition, rotation, null, null);
        if (spawned == null || spawned.first() == null || spawned.second() == null) {
            logSpawnerFlowDebug("spawn denied reason=spawn-entity-failed player=" + playerUuid + " role=" + roleId);
            return false;
        }

        Ref<EntityStore> npcRef = spawned.first();
        NPCEntity npc = spawned.second();
        attachmentService.applyAttachments(itemStack, npcRef, npc, store);
        npcStateService.applyOwner(config, npcRef, npc, playerRef, ownerUuid, world);
        npcStateService.applyTamed(npcRef, tamed, world);
        npcStateService.applyCapturedName(itemStack, npcRef, store);
        progressionMetadataService.applyNpcProgressionFromItem(itemStack, npcRef, store);
        refreshProgressionAfterRestore(npcRef, npc, store);
        progressionMetadataService.applyNpcHealthFromItem(itemStack, npcRef, store);
        linkedNpcSyncService.restoreCommandLinksFromCapturedSnapshot(npcRef, store, ownerUuid, capturedSnapshot);
        UUID spawnedNpcUuid = npc.getUuid();
        if (capturedNpcUuid != null && spawnedNpcUuid != null) {
            linkedNpcSyncService.remapLinkedNpcRecordsAfterRespawn(player, capturedNpcUuid, spawnedNpcUuid);
        }
        linkedNpcSyncService.clearCapturedSnapshotIfPresent(capturedNpcUuid);
        if (coopService != null && capturedNpcUuid != null) {
            coopService.clearCoopSnapshot(capturedNpcUuid);
        }

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
            logSpawnerFlowDebug("spawn denied reason=update-held-item-failed player=" + playerUuid + " item=" + itemStack.getItemId());
            return false;
        }

        effectService.playSpawnEffects(world, npcRef, config);
        logSpawnerFlowDebug(
                "spawn success item=" + itemStack.getItemId()
                        + " role=" + roleId
                        + " player=" + playerUuid
                        + " spawnedNpc=" + spawnedNpcUuid
                        + " appliedOwner=" + ownerUuid
                        + " captureSourceOwner=" + captureSourceOwnerUuid
                        + " assignsOwner=" + config.isSpawnAssignsOwner()
        );
        return true;
    }

    /**
     * Re-applies restored lifecycle state to model scale and growth scheduling.
     *
     * <p>Spawn flows can bootstrap tamed progression before item metadata restore, which leaves same-role offspring
     * (for example Cat_Pet babies) visually at adult scale unless lifecycle refresh is run again post-restore.
     */
    private void refreshProgressionAfterRestore(@Nullable Ref<EntityStore> npcRef,
                                                @Nullable NPCEntity npc,
                                                @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return;
        }
        CompanionStatModifierService.applyTraitModifiers(npcRef, store);
        CompanionLifeStageService.refreshLifeStage(npcRef, npc, store);
        CompanionLifeStageService.ensureGrowthTickScheduled(npcRef, npc, store);
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
        TameworkSettingsStore.GlobalOverrides overrides = TameworkSettingsStore.loadRuntimeGlobalOverrides();
        boolean captureClearsOwner = overrides != null && overrides.captureClearsOwner() != null
                ? overrides.captureClearsOwner()
                : baseConfig.isCaptureClearsOwner();
        boolean spawnAssignsOwner = spawnAssignsOwnerOverride != null
                ? spawnAssignsOwnerOverride
                : baseConfig.isSpawnAssignsOwner();
        if (overrides != null && overrides.spawnSetsOwner() != null) {
            spawnAssignsOwner = overrides.spawnSetsOwner();
        }
        return ItemFeatureConfig.builder()
                .spawnerEnabled(baseConfig.isSpawnerEnabled())
                .whistleEnabled(baseConfig.isWhistleEnabled())
                .captureClearsOwner(captureClearsOwner)
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
                .spawnerTooltipMode(baseConfig.getSpawnerTooltipMode())
                .build();
    }

    // Called by NPC action chains to capture an NPC into the held spawner item.
    public boolean captureFromNpcAction(Player player, Ref<EntityStore> targetRef, ItemStack itemStack, ItemFeatureConfig config) {
        if (player == null || targetRef == null || itemStack == null || config == null) {
            return false;
        }
        config = buildSpawnerConfigForInteraction(config, null);
        if (config == null) {
            return false;
        }
        if (itemStackMetadataService.isAlreadyCaptured(itemStack)) {
            logger.at(Level.FINE).log(
                    "Spawner stub: capture denied (item already captured) item=" + itemStack.getItemId()
            );
            return false;
        }
        if (!capturePolicyService.canCapture(player, targetRef, config, itemStack)) {
            logSpawnerFlowDebug(
                    "capture denied by policy item=" + itemStack.getItemId()
                            + " player=" + player.getUuid()
                            + " targetRef=" + targetRef
                            + " requireOwnerOverride=" + config.getCaptureRequireOwnerOverride()
                            + " ownerRestricted=" + config.isCaptureOwnerRestricted()
                            + " requireTamed=" + config.isCaptureRequireTamed()
            );
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
        Store<EntityStore> worldStore = world != null ? world.getEntityStore().getStore() : null;
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
        if (worldStore != null) {
            NPCEntity npc = worldStore.getComponent(targetRef, NPCEntity.getComponentType());
            if (npc != null) {
                snapshotRoleId = npcIdentityService.resolveRoleId(npc);
                if (snapshotDisplayName == null || snapshotDisplayName.isBlank()) {
                    snapshotDisplayName = npcIdentityService.resolveDisplayName(targetRef, worldStore, npc);
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
        if (existingOwner != null) {
            updated = updated.withMetadata(
                    TameworkMetadataKeys.CAPTURE_SOURCE_OWNER_UUID,
                    Codec.UUID_STRING,
                    existingOwner
            );
        } else {
            updated = itemStackMetadataService.clearMetadataKey(
                    updated,
                    TameworkMetadataKeys.CAPTURE_SOURCE_OWNER_UUID
            );
        }
        if (captureInfo.npcNameKey() != null && !captureInfo.npcNameKey().isBlank()) {
            updated = updated.withMetadata(TameworkMetadataKeys.CAPTURE_ROLE_ID, Codec.STRING, captureInfo.npcNameKey());
        } else {
            updated = itemStackMetadataService.clearMetadataKey(updated, TameworkMetadataKeys.CAPTURE_ROLE_ID);
        }
        updated = captureMetadataService.applyCapturedMetadata(updated, captureInfo, fullItemIcon);
        updated = captureMetadataService.applyCapturedNameMetadata(updated, captureInfo);
        updated = captureMetadataService.applyTooltipDisplayNameMetadata(updated, captureInfo);
        if (worldStore != null) {
            updated = progressionMetadataService.applyNpcProgressionMetadata(updated, targetRef, worldStore);
        }
        updated = itemStackMetadataService.applyCooldown(updated, TameworkMetadataKeys.CAPTURE_COOLDOWN_UNTIL, config.getCaptureCooldownMs());

        if (!playerInventoryService.updateHeldItem(player, updated)) {
            logger.at(Level.WARNING).log("Spawner stub: failed to update held item.");
            return false;
        }
        if (targetUuid != null) {
            if (relocationService != null) {
                relocationService.cancelPendingRelocation(targetUuid);
            }
            if (lostService != null) {
                lostService.clearLostSnapshot(targetUuid);
            }
            if (coopService != null) {
                coopService.clearCoopSnapshot(targetUuid);
            }
        }
        effectService.playCaptureEffects(world, targetRef, config);
        captureFinalizerService.clearOwnerIfConfigured(player, config, targetRef);
        captureFinalizerService.despawnNpc(player, targetRef, null);

        logger.at(Level.FINE).log(
                "Spawner stub: capture request item=" + itemStack.getItemId()
                        + " targetUuid=" + targetUuid
                        + " captureClearsOwner=" + config.isCaptureClearsOwner()
        );
        logSpawnerFlowDebug(
                "capture success item=" + itemStack.getItemId()
                        + " player=" + player.getUuid()
                        + " targetUuid=" + targetUuid
                        + " existingOwner=" + existingOwner
                        + " storedOwner=" + ownerToStore
                        + " captureClearsOwner=" + config.isCaptureClearsOwner()
        );
        return true;
    }

    private void logSpawnerFlowDebug(String message) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || !instance.isDebugSpawnerEnabled()) {
            return;
        }
        logger.at(Level.INFO).log("Spawner flow debug: " + message);
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

}
