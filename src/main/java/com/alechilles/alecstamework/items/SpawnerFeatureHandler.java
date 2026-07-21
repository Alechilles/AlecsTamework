package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.CaptureRequirementContext;
import com.alechilles.alecstamework.api.CaptureRequirementPhase;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.effects.TameworkEntityEffectService;
import com.alechilles.alecstamework.inventory.PlayerInventoryAccess;
import com.alechilles.alecstamework.items.capturepolicy.runtime.CaptureAttemptCoordinator;
import com.alechilles.alecstamework.localization.TranslationRegistry;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
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
    private final SpawnerItemDisplayMetadataService itemDisplayMetadataService;
    private final SpawnerNpcStateService npcStateService;
    private final SpawnerPlayerInventoryService playerInventoryService;
    private final SpawnerAttachmentService attachmentService;
    private final SpawnerEffectService effectService;
    private final SpawnerNpcIdentityService npcIdentityService;
    private final SpawnerCaptureFinalizerService captureFinalizerService;
    private final SpawnerCapturePolicyService capturePolicyService;
    private final SpawnerPreparedSpawnService preparedSpawnService;
    private final SpawnerPendingSourceRecoveryService pendingSourceRecoveryService;
    @Nullable
    private final CaptureAttemptCoordinator captureAttemptCoordinator;
    private final LongSupplier captureRequirementGeneration;
    private final ConcurrentHashMap<UUID, UUID> channelAttemptIds = new ConcurrentHashMap<>();
    @Nullable
    private final SpawnerManagedCoopCaptureDetachService managedCoopDetachService;
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
                                 @Nullable CommandLinkedNpcLostService lostService,
                                 @Nullable TranslationRegistry translationRegistry) {
        this(
                logger,
                registry,
                captureService,
                coopService,
                relocationService,
                lostService,
                translationRegistry,
                null
        );
    }

    public SpawnerFeatureHandler(HytaleLogger logger,
                                 ItemFeatureRegistry registry,
                                 CommandLinkedNpcCaptureService captureService,
                                 @Nullable CommandLinkedNpcCoopService coopService,
                                 @Nullable CommandNpcRelocationService relocationService,
                                 @Nullable CommandLinkedNpcLostService lostService,
                                 @Nullable TranslationRegistry translationRegistry,
                                 @Nullable SpawnerManagedCoopCaptureDetachService managedCoopDetachService) {
        this(logger, registry, captureService, coopService, relocationService, lostService,
                translationRegistry, managedCoopDetachService, null, () -> 0L);
    }

    public SpawnerFeatureHandler(HytaleLogger logger,
                                 ItemFeatureRegistry registry,
                                 CommandLinkedNpcCaptureService captureService,
                                 @Nullable CommandLinkedNpcCoopService coopService,
                                 @Nullable CommandNpcRelocationService relocationService,
                                 @Nullable CommandLinkedNpcLostService lostService,
                                 @Nullable TranslationRegistry translationRegistry,
                                 @Nullable SpawnerManagedCoopCaptureDetachService managedCoopDetachService,
                                 @Nullable CaptureAttemptCoordinator captureAttemptCoordinator,
                                 LongSupplier captureRequirementGeneration) {
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
        this.itemDisplayMetadataService = new SpawnerItemDisplayMetadataService(translationRegistry);
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
        this.preparedSpawnService = new SpawnerPreparedSpawnService(
                spawnPositionService,
                rolePolicyService,
                ownershipPolicyService,
                itemStackMetadataService,
                playerInventoryService,
                npcStateService,
                attachmentService,
                progressionMetadataService,
                linkedNpcSyncService,
                effectService,
                coopService,
                this::logSpawnerFlowDebug
        );
        this.pendingSourceRecoveryService = new SpawnerPendingSourceRecoveryService(
                registry,
                itemStackMetadataService,
                linkedNpcSyncService,
                coopService,
                logger
        );
        this.coopService = coopService;
        this.relocationService = relocationService;
        this.lostService = lostService;
        this.managedCoopDetachService = managedCoopDetachService;
        this.captureAttemptCoordinator = captureAttemptCoordinator;
        this.captureRequirementGeneration = captureRequirementGeneration == null ? () -> 0L : captureRequirementGeneration;
    }

    /** Attempts exact recovery of a pre-restart release whose filled source was retained. */
    public void recoverPendingSpawnerSources(World world, UUID playerUuid) {
        pendingSourceRecoveryService.recoverAfterWorldJoin(world, playerUuid);
    }

    public void onAddPlayerToWorld(AddPlayerToWorldEvent event) {
        if (event == null || event.getWorld() == null || event.getHolder() == null) {
            return;
        }
        PlayerRef playerRef = event.getHolder().getComponent(PlayerRef.getComponentType());
        recoverPendingSpawnerSources(
                event.getWorld(), playerRef == null ? null : playerRef.getUuid()
        );
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

    public boolean canBeginCaptureChannelInteraction(Player player,
                                                     Ref<EntityStore> targetRef,
                                                     ItemStack itemStack) {
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
        return capturePolicyService.canBeginCaptureChannel(player, targetRef, config, itemStack);
    }

    public boolean beginCaptureChannel(Player player, Ref<EntityStore> targetRef, ItemStack itemStack) {
        return beginCaptureChannel(player, targetRef, itemStack, null, 50.0D, 3.0D);
    }

    public boolean beginCaptureChannel(Player player,
                                       Ref<EntityStore> targetRef,
                                       ItemStack itemStack,
                                       String beamParticleSystem,
                                       double beamNativeLength,
                                       double channelDurationSeconds) {
        return beginCaptureChannel(
                player,
                targetRef,
                itemStack,
                beamParticleSystem,
                beamNativeLength,
                0.5D,
                true,
                false,
                channelDurationSeconds
        );
    }

    public boolean beginCaptureChannel(Player player,
                                       Ref<EntityStore> targetRef,
                                       ItemStack itemStack,
                                       String beamParticleSystem,
                                       double beamNativeLength,
                                       boolean scaleBeamToTarget,
                                       double channelDurationSeconds) {
        return beginCaptureChannel(
                player,
                targetRef,
                itemStack,
                beamParticleSystem,
                beamNativeLength,
                0.5D,
                scaleBeamToTarget,
                false,
                channelDurationSeconds
        );
    }

    public boolean beginCaptureChannel(Player player,
                                       Ref<EntityStore> targetRef,
                                       ItemStack itemStack,
                                       String beamParticleSystem,
                                       double beamNativeLength,
                                       double beamNativeDurationSeconds,
                                       boolean scaleBeamToTarget,
                                       boolean beamFromTarget,
                                       double channelDurationSeconds) {
        return beginCaptureChannel(
                player,
                targetRef,
                itemStack,
                beamParticleSystem,
                beamNativeLength,
                beamNativeDurationSeconds,
                scaleBeamToTarget,
                beamFromTarget,
                channelDurationSeconds,
                CaptureHomingProjectileSettings.disabled()
        );
    }

    public boolean beginCaptureChannel(Player player,
                                       Ref<EntityStore> targetRef,
                                       ItemStack itemStack,
                                       String beamParticleSystem,
                                       double beamNativeLength,
                                       double beamNativeDurationSeconds,
                                       boolean scaleBeamToTarget,
                                       boolean beamFromTarget,
                                       double channelDurationSeconds,
                                       CaptureHomingProjectileSettings homingProjectileSettings) {
        if (!canBeginCaptureChannelInteraction(player, targetRef, itemStack)) {
            return false;
        }
        ItemFeatureConfig config = resolveConfigForItem(itemStack);
        World world = player.getWorld();
        if (config == null || world == null || world.getEntityStore() == null) {
            return false;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        UUIDComponent playerUuid = store.getComponent(player.getReference(), UUIDComponent.getComponentType());
        UUIDComponent targetUuid = store.getComponent(targetRef, UUIDComponent.getComponentType());
        if (playerUuid == null || playerUuid.getUuid() == null || targetUuid == null || targetUuid.getUuid() == null
                || !CaptureChannelVfxSystem.start(
                        playerUuid.getUuid(),
                        targetUuid.getUuid(),
                        world,
                        beamParticleSystem,
                        beamNativeLength,
                        beamNativeDurationSeconds,
                        scaleBeamToTarget,
                        beamFromTarget,
                        channelDurationSeconds,
                        config.getCaptureMaxDistance(),
                        config.getCaptureChannelAuraEffectId(),
                        homingProjectileSettings
                )) {
            return false;
        }
        String auraEffectId = config.getCaptureChannelAuraEffectId();
        if (auraEffectId != null && !auraEffectId.isBlank()) {
            TameworkEntityEffectService.applyEffect(
                    targetRef,
                    auraEffectId,
                    store
            );
        }
        channelAttemptIds.put(playerUuid.getUuid(), UUID.randomUUID());
        return true;
    }

    public void endCaptureChannel(Player player, Ref<EntityStore> targetRef, ItemStack itemStack) {
        ItemFeatureConfig config = resolveConfigForItem(itemStack);
        World world = player == null ? null : player.getWorld();
        if (config == null || world == null || world.getEntityStore() == null) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        UUIDComponent playerUuid = player.getReference() == null
                ? null
                : store.getComponent(player.getReference(), UUIDComponent.getComponentType());
        if (playerUuid != null && playerUuid.getUuid() != null) {
            channelAttemptIds.remove(playerUuid.getUuid());
        }
        Ref<EntityStore> lockedTarget = playerUuid == null || playerUuid.getUuid() == null
                ? null
                : CaptureChannelVfxSystem.stop(playerUuid.getUuid(), world);
        if (targetRef == null) {
            targetRef = lockedTarget;
        }
        TameworkEntityEffectService.removeEffect(
                targetRef,
                config.getCaptureChannelAuraEffectId(),
                store
        );
    }

    public boolean completeCaptureChannel(Player player, Ref<EntityStore> targetRef, ItemStack itemStack) {
        return completeCaptureChannel(player, targetRef, itemStack, null);
    }

    public boolean completeCaptureChannel(Player player,
                                          Ref<EntityStore> targetRef,
                                          ItemStack itemStack,
                                          @Nullable String captureBurstParticleSystem) {
        UUID attemptId = player == null ? null : channelAttemptIds.remove(player.getUuid());
        endCaptureChannel(player, targetRef, itemStack);
        return captureFromItemInteraction(
                player, itemStack, targetRef, captureBurstParticleSystem,
                attemptId == null ? UUID.randomUUID() : attemptId);
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
        if (itemStackMetadataService.isCooldownActive(itemStack, TameworkMetadataKeys.SPAWN_COOLDOWN_UNTIL, config.getSpawnCooldownMs())) {
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
        return captureFromItemInteraction(player, itemStack, targetRef, null);
    }

    private boolean captureFromItemInteraction(Player player,
                                               ItemStack itemStack,
                                               Ref<EntityStore> targetRef,
                                               @Nullable String captureBurstParticleSystem) {
        return captureFromItemInteraction(
                player, itemStack, targetRef, captureBurstParticleSystem, UUID.randomUUID());
    }

    private boolean captureFromItemInteraction(Player player,
                                               ItemStack itemStack,
                                               Ref<EntityStore> targetRef,
                                               @Nullable String captureBurstParticleSystem,
                                               UUID attemptId) {
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
        return captureFromNpcAction(
                player, targetRef, itemStack, config, captureBurstParticleSystem, attemptId, false);
    }

    // Used by TameworkSpawnInteraction: builds a minimal config to spawn from the held item.
    public boolean spawnFromItemInteraction(Player player,
                                            ItemStack itemStack,
                                            Integer hotbarSlot,
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
        return spawnFromItem(player, itemStack, config, hotbarSlot, emptyItemIdOverride);
    }

    private boolean spawnFromItem(Player player, ItemStack itemStack, ItemFeatureConfig config,
                                  Integer hotbarSlot, String emptyItemIdOverride) {
        if (player == null || itemStack == null || config == null) {
            logSpawnerFlowDebug("spawn denied reason=invalid-input");
            return false;
        }
        ItemFeatureConfig resolved = buildSpawnerConfigForInteraction(config, null);
        return resolved != null && preparedSpawnService.schedule(
                player, itemStack, resolved, hotbarSlot, emptyItemIdOverride
        );
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
        return SpawnerInteractionConfigResolver.resolve(baseConfig, spawnAssignsOwnerOverride);
    }

    // Called by NPC action chains to capture an NPC into the held spawner item.
    public boolean captureFromNpcAction(Player player, Ref<EntityStore> targetRef, ItemStack itemStack, ItemFeatureConfig config) {
        return captureFromNpcAction(player, targetRef, itemStack, config, null, UUID.randomUUID(), false);
    }

    private boolean captureFromNpcAction(Player player,
                                         Ref<EntityStore> targetRef,
                                         ItemStack itemStack,
                                         ItemFeatureConfig config,
                                         @Nullable String captureBurstParticleSystem) {
        return captureFromNpcAction(
                player, targetRef, itemStack, config, captureBurstParticleSystem, UUID.randomUUID(), false);
    }

    private boolean captureFromNpcAction(Player player,
                                         Ref<EntityStore> targetRef,
                                         ItemStack itemStack,
                                         ItemFeatureConfig config,
                                         @Nullable String captureBurstParticleSystem,
                                         UUID attemptId,
                                         boolean outcomeResolved) {
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
        if (itemStack.getQuantity() != 1) {
            logSpawnerFlowDebug(
                    "capture denied reason=stacked-spawner-item player=" + player.getUuid()
                            + " item=" + itemStack.getItemId()
                            + " quantity=" + itemStack.getQuantity()
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
        if (!outcomeResolved && captureAttemptCoordinator != null) {
            return scheduleDurableCaptureAttempt(
                    player, targetRef, itemStack, config, captureBurstParticleSystem, attemptId);
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
        SpawnerManagedCoopCaptureDetachService.Plan detachPlan = managedCoopDetachService == null
                ? new SpawnerManagedCoopCaptureDetachService.Plan(
                        SpawnerManagedCoopCaptureDetachService.PlanStatus.NOT_MANAGED,
                        null,
                        null
                )
                : managedCoopDetachService.prepare(targetUuid);
        if (!detachPlan.accepted()) {
            logSpawnerFlowDebug(
                    "capture denied reason=" + detachPlan.detail()
                            + " player=" + player.getUuid()
                            + " targetUuid=" + targetUuid
            );
            return false;
        }
        UUID existingOwner = npcStateService.resolveOwnerFromComponent(targetRef, world);
        UUID ownerToStore = config.isCaptureTamesTarget()
                ? player.getUuid()
                : resolveCapturedOwnerMetadata(existingOwner, config.isCaptureClearsOwner());
        String snapshotDisplayName = (captureInfo.capturedName() != null
                && captureInfo.capturedName().name() != null
                && !captureInfo.capturedName().name().isBlank())
                ? captureInfo.capturedName().name()
                : null;
        if ((snapshotDisplayName == null || snapshotDisplayName.isBlank())
                && captureInfo.tooltipDisplayName() != null
                && !captureInfo.tooltipDisplayName().isBlank()) {
            snapshotDisplayName = captureInfo.tooltipDisplayName();
        }
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
        String captureRoleId = config.isCaptureTamesTarget()
                ? config.resolveCaptureTamedRole(snapshotRoleId)
                : snapshotRoleId;
        CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot preparedLinkedSnapshot =
                linkedNpcSyncService.prepareCapturedLinkedNpcSnapshot(
                        targetRef,
                        world,
                        targetUuid,
                        ownerToStore,
                        captureRoleId,
                        snapshotDisplayName
                );
        ItemStack updated = itemStackMetadataService.swapItemId(itemStack, config.getSpawnerFilledItemId())
                .withMetadata(TameworkMetadataKeys.CAPTURED, Codec.BOOLEAN, true)
                .withMetadata(TameworkMetadataKeys.TARGET_UUID, Codec.UUID_STRING, targetUuid);
        if (attachmentsJson != null) {
            updated = updated.withMetadata(TameworkMetadataKeys.ATTACHMENTS, Codec.STRING, attachmentsJson);
        }
        boolean tamed = config.isCaptureTamesTarget() || npcStateService.resolveTamedState(targetRef, world);
        if (tamed) {
            updated = updated.withMetadata(TameworkMetadataKeys.TAMED, Codec.BOOLEAN, true);
        }
        updated = itemStackMetadataService.applyOwnerMetadata(updated, ownerToStore);
        updated = updated.withMetadata(
                TameworkMetadataKeys.CAPTURE_OWNER_CLEARED,
                Codec.BOOLEAN,
                config.isCaptureClearsOwner() && !config.isCaptureTamesTarget()
        );
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
        if (captureRoleId != null && !captureRoleId.isBlank()) {
            updated = updated.withMetadata(TameworkMetadataKeys.CAPTURE_ROLE_ID, Codec.STRING, captureRoleId);
        } else {
            updated = itemStackMetadataService.clearMetadataKey(updated, TameworkMetadataKeys.CAPTURE_ROLE_ID);
        }
        updated = captureMetadataService.applyCaptureNameKeyMetadata(updated, captureInfo);
        updated = captureMetadataService.applyCapturedMetadata(updated, captureInfo, fullItemIcon);
        updated = captureMetadataService.applyCapturedModelMetadata(updated, captureInfo);
        updated = captureMetadataService.applyCapturedNameMetadata(updated, captureInfo);
        updated = captureMetadataService.applyTooltipDisplayNameMetadata(updated, captureInfo);
        if (worldStore != null) {
            updated = progressionMetadataService.applyNpcProgressionMetadata(updated, targetRef, worldStore);
        }
        updated = itemDisplayMetadataService.applyCapturedDisplayMetadata(updated, config);
        updated = itemStackMetadataService.applyCooldown(updated, TameworkMetadataKeys.CAPTURE_COOLDOWN_UNTIL, config.getCaptureCooldownMs());
        ItemStack capturedItem = updated;
        ItemFeatureConfig finalizedConfig = config;
        UUID finalizedAttemptId = outcomeResolved ? attemptId : null;
        UUID finalizedOwnerToStore = ownerToStore;
        Integer sourceHotbarSlot = resolveSourceHotbarSlot(player, null);
        SpawnerSourceItemTransaction sourceItem = new SpawnerSourceItemTransaction(
                playerInventoryService,
                player,
                sourceHotbarSlot,
                itemStack,
                logger,
                "Spawner capture"
        );
        return captureFinalizerService.finalizeCapture(
                player,
                finalizedConfig,
                targetRef,
                detachPlan.durableContextJson(),
                new SpawnerCaptureFinalizerService.CaptureCallbacks() {
                    @Override
                    public boolean beforeApply(String profileId) {
                        ItemStack profiledItem = capturedItem.withMetadata(
                                TameworkMetadataKeys.COMPANION_PROFILE_ID,
                                Codec.STRING,
                                profileId
                        );
                        if (!sourceItem.prepare(profiledItem)) {
                            return false;
                        }
                        return true;
                    }

                    @Override
                    public void onApplyCompensated(String profileId, String reason) {
                        sourceItem.compensate();
                        if (finalizedAttemptId != null && captureAttemptCoordinator != null) {
                            captureAttemptCoordinator.quarantineApply(finalizedAttemptId, reason);
                        }
                    }

                    @Override
                    public void onApplied(String profileId,
                                          com.alechilles.alecstamework.ownership.OwnerMutationContext context) {
                        sourceItem.commit();
                        linkedNpcSyncService.publishPreparedCapturedLinkedNpcSnapshot(
                                preparedLinkedSnapshot,
                                context.npcUuid()
                        );
                        UUID liveUuid = context.npcUuid();
                        if (relocationService != null) {
                            relocationService.cancelPendingRelocation(liveUuid);
                        }
                        if (lostService != null) {
                            lostService.clearLostSnapshot(liveUuid);
                        }
                        if (coopService != null) {
                            coopService.clearCoopSnapshot(liveUuid);
                        }
                        spawnCaptureSuccessParticle(captureBurstParticleSystem, context);
                        effectService.playCaptureEffects(world, context.npcRef(), finalizedConfig);
                        logger.at(Level.FINE).log(
                                "Spawner stub: capture request item=" + itemStack.getItemId()
                                        + " targetUuid=" + targetUuid
                                        + " captureClearsOwner=" + finalizedConfig.isCaptureClearsOwner()
                        );
                        logSpawnerFlowDebug(
                                "capture success item=" + itemStack.getItemId()
                                        + " player=" + player.getUuid()
                                        + " targetUuid=" + targetUuid
                                        + " existingOwner=" + existingOwner
                                        + " storedOwner=" + finalizedOwnerToStore
                                        + " captureClearsOwner=" + finalizedConfig.isCaptureClearsOwner()
                        );
                    }

                    @Override
                    public void onDenied(String reason) {
                        if (finalizedAttemptId != null && captureAttemptCoordinator != null) {
                            captureAttemptCoordinator.quarantineApply(finalizedAttemptId, reason);
                        }
                        logSpawnerFlowDebug(
                                "capture denied reason=" + reason
                                        + " player=" + player.getUuid()
                                        + " targetUuid=" + targetUuid
                        );
                    }

                    @Override
                    public void onPopulationCommitted(
                            com.alechilles.alecstamework.ownership.CompanionPopulationCommitResult result) {
                        if (finalizedAttemptId != null && captureAttemptCoordinator != null) {
                            captureAttemptCoordinator.commit(finalizedAttemptId);
                        }
                        if (detachPlan.requiresDetach()
                                && (managedCoopDetachService == null
                                || !managedCoopDetachService.refreshAfterCommit())) {
                            logger.at(Level.WARNING).log(
                                    "Spawner capture committed, but managed-coop indexes did not refresh."
                            );
                        }
                    }

                    @Override
                    public void onDurabilityDegraded(String reason) {
                        logger.at(Level.WARNING).log(
                                "Spawner capture ownership durability degraded after apply: " + reason
                        );
                    }
                }
        );
    }

    private boolean scheduleDurableCaptureAttempt(
            Player player,
            Ref<EntityStore> targetRef,
            ItemStack itemStack,
            ItemFeatureConfig config,
            @Nullable String captureBurstParticleSystem,
            UUID attemptId) {
        World world = player.getWorld();
        Store<EntityStore> store = world == null || world.getEntityStore() == null
                ? null : world.getEntityStore().getStore();
        NPCEntity npc = store == null ? null : store.getComponent(targetRef, NPCEntity.getComponentType());
        UUID targetUuid = linkedNpcSyncService.resolveEntityUuid(player, targetRef);
        String roleId = npc == null ? null : rolePolicyService.resolveRoleIdFromNpc(npc);
        SpawnerCapturePolicyService.CaptureHealth health =
                capturePolicyService.resolveCaptureHealth(targetRef, store);
        if (world == null || store == null || targetUuid == null || roleId == null || roleId.isBlank()
                || health == null || attemptId == null) {
            return false;
        }
        double healthFraction = health.currentHealth() / health.maximumHealth();
        long requirementGeneration = Math.max(0L, captureRequirementGeneration.getAsLong());
        long expiresAt;
        try {
            expiresAt = Math.addExact(System.currentTimeMillis(), 120_000L);
        } catch (ArithmeticException overflow) {
            expiresAt = Long.MAX_VALUE;
        }
        CaptureRequirementContext requirementContext = new CaptureRequirementContext(
                attemptId,
                CaptureRequirementPhase.FINAL_REVALIDATION,
                player.getUuid(),
                targetUuid,
                null,
                roleId,
                world.getName(),
                itemStack.getItemId(),
                healthFraction,
                CaptureRequirementContext.UNKNOWN_PROFILE_REVISION
        );
        CaptureAttemptCoordinator.AttemptRequest request =
                new CaptureAttemptCoordinator.AttemptRequest(
                        attemptId,
                        UUID.randomUUID(),
                        null,
                        null,
                        player.getUuid(),
                        targetUuid,
                        null,
                        CaptureRequirementContext.UNKNOWN_PROFILE_REVISION,
                        itemStack.getItemId(),
                        roleId,
                        "{\"world\":\"" + jsonEscape(world.getName()) + "\"}",
                        itemStack.getItemId(),
                        registry.revision(),
                        config.getCaptureMechanics(),
                        health.currentHealth(),
                        health.maximumHealth(),
                        requirementContext,
                        requirementGeneration,
                        null,
                        expiresAt
                );
        captureAttemptCoordinator.resolve(request).thenCompose(result -> {
            if (result.status() == CaptureAttemptCoordinator.ResultStatus.FAILED_ROLL) {
                world.execute(() -> effectService.playEffects(
                        world, targetRef,
                        config.getCaptureMechanics().failureParticleSystem(),
                        config.getCaptureMechanics().failureSoundEvent()));
                return java.util.concurrent.CompletableFuture.completedFuture(false);
            }
            if (result.status() != CaptureAttemptCoordinator.ResultStatus.SUCCESS) {
                logSpawnerFlowDebug("capture denied reason=" + result.reason()
                        + " player=" + player.getUuid() + " targetUuid=" + targetUuid);
                return java.util.concurrent.CompletableFuture.completedFuture(false);
            }
            return captureAttemptCoordinator.beginApply(attemptId);
        }).whenComplete((applyReady, failure) -> {
            if (failure != null || !Boolean.TRUE.equals(applyReady)) return;
            world.execute(() -> {
                boolean scheduled = captureFromNpcAction(
                        player, targetRef, itemStack, config,
                        captureBurstParticleSystem, attemptId, true);
                if (!scheduled) {
                    captureAttemptCoordinator.quarantineApply(
                            attemptId, "capture-terminal-revalidation-failed");
                }
            });
        });
        return true;
    }

    private static String jsonEscape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void spawnCaptureSuccessParticle(
            @Nullable String particleSystem,
            com.alechilles.alecstamework.ownership.OwnerMutationContext context) {
        if (particleSystem == null || particleSystem.isBlank()
                || context == null || context.npcRef() == null || !context.npcRef().isValid()) {
            return;
        }
        TransformComponent transform = context.store().getComponent(
                context.npcRef(), TransformComponent.getComponentType()
        );
        if (transform == null || transform.getPosition() == null) {
            return;
        }
        ParticleUtil.spawnParticleEffect(particleSystem, transform.getPosition(), context.store());
    }

    @Nullable
    private Integer resolveSourceHotbarSlot(Player player, @Nullable Integer explicitSlot) {
        if (explicitSlot != null && explicitSlot >= 0) {
            return explicitSlot;
        }
        byte activeSlot = PlayerInventoryAccess.getActiveHotbarSlot(player);
        return activeSlot < 0 ? null : (int) activeSlot;
    }

    @Nullable
    static UUID resolveCapturedOwnerMetadata(@Nullable UUID existingOwner, boolean captureClearsOwner) {
        return captureClearsOwner ? null : existingOwner;
    }
    private void logSpawnerFlowDebug(String message) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || !instance.isDebugSpawnerEnabled()) {
            return;
        }
        logger.at(Level.INFO).log("Spawner flow debug: " + message);
    }

}
