package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.BondedVesselMode;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.effects.TameworkEntityEffectService;
import com.alechilles.alecstamework.items.capturepolicy.runtime.CaptureAttemptCoordinator;
import com.alechilles.alecstamework.persistence.sqlite.CaptureAttemptRecord;
import com.alechilles.alecstamework.persistence.sqlite.CaptureRepository;
import com.alechilles.alecstamework.localization.TranslationRegistry;
import com.alechilles.alecstamework.vessels.runtime.BondedVesselSpawnerBridge;
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
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import javax.annotation.Nonnull;
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
    private final SpawnerCaptureAttemptRuntimeCoordinator captureAttemptRuntime;
    private final SpawnerBondedVesselCoordinator bondedVessels;
    private final SpawnerCaptureOperationalLog captureLog;
    private final SpawnerCapturedProfilePersistenceService profilePersistence;
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
                translationRegistry, managedCoopDetachService, null, () -> 0L, null);
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
        this(logger, registry, captureService, coopService, relocationService, lostService,
                translationRegistry, managedCoopDetachService, captureAttemptCoordinator,
                captureRequirementGeneration, null);
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
                                 LongSupplier captureRequirementGeneration,
                                 @Nullable CaptureRepository captureRepository) {
        this.logger = logger;
        this.registry = registry;
        this.profilePersistence = new SpawnerCapturedProfilePersistenceService(logger, captureRepository);
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
        this.captureLog = new SpawnerCaptureOperationalLog(logger);
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
                captureLog::flowDebug
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
        this.captureAttemptRuntime = new SpawnerCaptureAttemptRuntimeCoordinator(
                registry,
                playerInventoryService,
                captureFinalizerService,
                capturePolicyService,
                linkedNpcSyncService,
                rolePolicyService,
                effectService,
                captureAttemptCoordinator,
                captureRequirementGeneration,
                captureLog::capture);
        this.bondedVessels = new SpawnerBondedVesselCoordinator(
                logger, spawnPositionService, captureAttemptRuntime, captureLog::capture);
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
        CaptureAttemptHandle attempt = prepareCaptureAttempt(player, itemStack, null);
        return attempt != null && captureFromNpcAction(
                player, targetRef, itemStack, config, attempt);
    }

    private void captureStub(Player player, ItemStack itemStack, int targetEntityId, ItemFeatureConfig config, int activeHotbarSlot) {
        if (player == null || itemStack == null || config == null) {
            return;
        }
        Ref<EntityStore> targetRef = playerInventoryService.resolveEntityRef(player, targetEntityId, null);
        if (targetRef == null || !targetRef.isValid()) {
            return;
        }
        CaptureAttemptHandle attempt = prepareCaptureAttempt(
                player, itemStack, activeHotbarSlot);
        if (attempt != null) {
            captureFromNpcAction(player, targetRef, itemStack, config, attempt);
        }
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
            captureLog.channelBeginRuntimeDenied(player.getUuid(), itemStack);
            return false;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        UUIDComponent playerUuid = store.getComponent(player.getReference(), UUIDComponent.getComponentType());
        UUIDComponent targetUuid = store.getComponent(targetRef, UUIDComponent.getComponentType());
        CaptureAttemptHandle attempt = prepareCaptureAttempt(player, itemStack, null);
        if (playerUuid == null || playerUuid.getUuid() == null || targetUuid == null || targetUuid.getUuid() == null
                || attempt == null
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
            captureLog.channelBeginSessionDenied(
                    playerUuid == null ? null : playerUuid.getUuid(),
                    targetUuid == null ? null : targetUuid.getUuid(), itemStack, attempt);
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
        captureAttemptRuntime.rememberChannel(playerUuid.getUuid(), attempt);
        captureLog.channelBeginAccepted(
                playerUuid.getUuid(), targetUuid.getUuid(), itemStack, attempt);
        return true;
    }

    /** Installs the production bridge only after bonded-vessel recovery activated successfully. */
    public void installBondedVesselBridge(@Nullable BondedVesselSpawnerBridge bridge) {
        bondedVessels.install(bridge);
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
            captureAttemptRuntime.clearChannel(playerUuid.getUuid());
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
        UUID playerUuid = resolvePlayerUuid(player);
        CaptureAttemptHandle attempt = captureAttemptRuntime.takeChannel(playerUuid);
        endCaptureChannel(player, targetRef, itemStack);
        if (attempt == null) {
            captureLog.channelCompleteMissing(playerUuid, itemStack); // missing-channel-attempt-identity
            return false;
        }
        boolean scheduled = captureFromItemInteraction(
                player, itemStack, targetRef, captureBurstParticleSystem,
                attempt);
        captureLog.channelComplete(scheduled, playerUuid, itemStack, attempt, targetRef);
        return scheduled;
    }

    @Nullable
    private static UUID resolvePlayerUuid(@Nullable Player player) {
        World world = player == null ? null : player.getWorld();
        if (world == null || world.getEntityStore() == null || player.getReference() == null) {
            return null;
        }
        UUIDComponent playerUuid = world.getEntityStore().getStore().getComponent(
                player.getReference(), UUIDComponent.getComponentType());
        return playerUuid == null ? null : playerUuid.getUuid();
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
        if (config.getVesselMechanics().mode() == BondedVesselMode.BONDED) {
            return bondedVessels.canUse(itemStack);
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
    @Nullable
    public CaptureAttemptHandle prepareCaptureAttempt(
            Player player, ItemStack itemStack, @Nullable Integer hotbarSlot) {
        return captureAttemptRuntime.prepare(player, itemStack, hotbarSlot);
    }

    /** Public/direct callers must reuse the same namespace and key for every callback retry. */
    @Nullable
    public CaptureAttemptHandle prepareCaptureAttempt(
            Player player, ItemStack itemStack, @Nullable Integer hotbarSlot,
            @Nonnull String callerNamespace, @Nonnull String idempotencyKey) {
        return captureAttemptRuntime.prepare(
                player, itemStack, hotbarSlot, callerNamespace, idempotencyKey);
    }

    public boolean captureFromItemInteraction(Player player,
                                               ItemStack itemStack,
                                               Ref<EntityStore> targetRef,
                                               @Nonnull CaptureAttemptHandle attempt) {
        return captureFromItemInteraction(player, itemStack, targetRef, null, attempt);
    }

    private boolean captureFromItemInteraction(Player player,
                                               ItemStack itemStack,
                                               Ref<EntityStore> targetRef,
                                               @Nullable String captureBurstParticleSystem,
                                               @Nonnull CaptureAttemptHandle attempt) {
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
                player, targetRef, itemStack, config, captureBurstParticleSystem, attempt, false,
                null, null, 0L);
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
            captureLog.flowDebug("spawn denied reason=invalid-input");
            return false;
        }
        ItemFeatureConfig resolved = buildSpawnerConfigForInteraction(config, null);
        if (resolved != null
                && resolved.getVesselMechanics().mode() == BondedVesselMode.BONDED) {
            return bondedVessels.toggle(player, itemStack, resolved, hotbarSlot);
        }
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

    // Called by NPC action chains with an identity allocated before any async continuation.
    public boolean captureFromNpcAction(Player player, Ref<EntityStore> targetRef,
                                        ItemStack itemStack, ItemFeatureConfig config,
                                        @Nonnull CaptureAttemptHandle attempt) {
        return captureFromNpcAction(player, targetRef, itemStack, config, null, attempt, false,
                null, null, 0L);
    }

    static boolean hasUsableBondedProjection(@Nullable ItemStack itemStack) {
        return SpawnerBondedVesselCoordinator.hasUsableProjection(itemStack);
    }

    private boolean captureFromNpcAction(Player player,
                                         Ref<EntityStore> targetRef,
                                         ItemStack itemStack,
                                         ItemFeatureConfig config,
                                         @Nullable String captureBurstParticleSystem,
                                         @Nonnull CaptureAttemptHandle attempt,
                                         boolean outcomeResolved,
                                         @Nullable SpawnerCaptureFinalizerService.PreparedCaptureMutation preparedMutation,
                                         @Nullable CaptureAttemptRecord resolvedAttempt,
                                         long expectedRequirementGeneration) {
        if (player == null || targetRef == null || itemStack == null || config == null) {
            return false;
        }
        UUID attemptId = attempt.attemptId();
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
            captureLog.capture(
                    "capture denied reason=stacked-spawner-item player=" + player.getUuid()
                            + " item=" + itemStack.getItemId()
                            + " quantity=" + itemStack.getQuantity()
            );
            return false;
        }
        if (!capturePolicyService.canCapture(player, targetRef, config, itemStack)) {
            captureLog.capture(
                    "capture denied by policy item=" + itemStack.getItemId()
                            + " player=" + player.getUuid()
                            + " targetRef=" + targetRef
                            + " requireOwnerOverride=" + config.getCaptureRequireOwnerOverride()
                            + " ownerRestricted=" + config.isCaptureOwnerRestricted()
                            + " requireTamed=" + config.isCaptureRequireTamed()
            );
            return false;
        }
        if (!captureAttemptRuntime.sourceMatches(player, attempt)) {
            captureLog.capture("capture denied reason=source-attempt-fence-changed"
                    + " player=" + player.getUuid() + " attempt=" + attemptId);
            if (outcomeResolved) captureAttemptRuntime.quarantine(
                    attemptId, "capture-source-attempt-fence-changed");
            return false;
        }
        World world = player.getWorld();
        if (world == null) {
            return false;
        }
        Store<EntityStore> worldStore = world != null && world.getEntityStore() != null
                ? world.getEntityStore().getStore() : null;
        UUID targetUuid = linkedNpcSyncService.resolveEntityUuid(player, targetRef);
        SpawnerManagedCoopCaptureDetachService.Plan detachPlan = managedCoopDetachService == null
                ? new SpawnerManagedCoopCaptureDetachService.Plan(
                        SpawnerManagedCoopCaptureDetachService.PlanStatus.NOT_MANAGED,
                        null,
                        null
                )
                : managedCoopDetachService.prepare(targetUuid);
        if (!detachPlan.accepted()) {
            captureLog.capture(
                    "capture denied reason=" + detachPlan.detail()
                            + " player=" + player.getUuid()
                            + " targetUuid=" + targetUuid
            );
            return false;
        }
        if (!outcomeResolved && captureAttemptRuntime.durableRuntimeInstalled()) {
            ItemFeatureConfig durableConfig = config;
            return captureAttemptRuntime.prepareAndResolve(
                    player, targetRef, itemStack, durableConfig, captureBurstParticleSystem,
                    attempt, detachPlan.durableContextJson(),
                    (resolvedHandle, mutation, record, generation) -> captureFromNpcAction(
                            player, targetRef, itemStack, durableConfig, captureBurstParticleSystem,
                            resolvedHandle, true, mutation, record, generation));
        }
        if (outcomeResolved) {
            if (preparedMutation == null || resolvedAttempt == null
                    || !captureAttemptRuntime.durableRuntimeInstalled() || worldStore == null) {
                return false;
            }
            SpawnerCapturePolicyService.CaptureHealth currentHealth =
                    capturePolicyService.resolveCaptureHealth(targetRef, worldStore);
            NPCEntity currentNpc = worldStore.getComponent(targetRef, NPCEntity.getComponentType());
            String currentRoleId = currentNpc == null ? null : rolePolicyService.resolveRoleIdFromNpc(currentNpc);
            if (currentHealth == null || currentRoleId == null || currentRoleId.isBlank()
                    || targetUuid == null) {
                preparedMutation.cancel("capture-terminal-revalidation-failed");
                captureAttemptRuntime.quarantine(attemptId, "capture-terminal-revalidation-failed");
                return false;
            }
            if (!captureAttemptRuntime.revalidateBeforeApply(
                    attemptId, resolvedAttempt, player, targetUuid, preparedMutation.profileId(),
                    currentRoleId, world, itemStack.getItemId(),
                    currentHealth.currentHealth() / currentHealth.maximumHealth(),
                    expectedRequirementGeneration, preparedMutation)) {
                return false;
            }
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
        boolean bondedCapture = config.getVesselMechanics().mode() == BondedVesselMode.BONDED;
        SpawnerBondedVesselCoordinator.InitialBindingAuthority bindingAuthority = bondedCapture
                ? bondedVessels.prepareInitialBinding(itemStack) : null;
        if (bondedCapture && bindingAuthority == null) {
            captureLog.capture("capture denied reason=bonded-vessel-runtime-unavailable");
            if (preparedMutation != null) {
                preparedMutation.cancel("bonded-vessel-runtime-unavailable");
            }
            return false;
        }
        ItemFeatureConfig finalizedConfig = config;
        UUID finalizedAttemptId = outcomeResolved ? attemptId : null;
        UUID finalizedOwnerToStore = ownerToStore;
        String finalizedCaptureRoleId = captureRoleId;
        String finalizedSnapshotDisplayName = snapshotDisplayName;
        Integer sourceHotbarSlot = attempt.hotbarSlot();
        if (bondedCapture && sourceHotbarSlot == null) {
            captureLog.capture("capture denied reason=bonded-source-slot-unavailable");
            if (preparedMutation != null) {
                preparedMutation.cancel("bonded-source-slot-unavailable");
            }
            return false;
        }
        SpawnerSourceItemTransaction sourceItem = new SpawnerSourceItemTransaction(
                playerInventoryService, world, player.getUuid(), sourceHotbarSlot,
                itemStack, logger, "Spawner capture");
        AtomicReference<String> capturedProfileId = new AtomicReference<>();
        AtomicReference<ItemStack> profiledCaptureItem = new AtomicReference<>();
        SpawnerCaptureFinalizerService.CaptureCallbacks callbacks =
                new SpawnerCaptureFinalizerService.CaptureCallbacks() {
                    @Override
                    public boolean beforeApply(String profileId) {
                        ItemStack profiledItem = capturedItem.withMetadata(
                                TameworkMetadataKeys.COMPANION_PROFILE_ID,
                                Codec.STRING,
                                profileId
                        );
                        capturedProfileId.set(profileId);
                        profiledCaptureItem.set(profiledItem);
                        return bondedCapture || sourceItem.prepare(profiledItem);
                    }

                    @Override
                    public void onApplyCompensated(String profileId, String reason) {
                        if (!bondedCapture) sourceItem.compensate();
                        captureAttemptRuntime.quarantine(finalizedAttemptId, reason);
                    }

                    @Override
                    public void onApplied(String profileId,
                                          com.alechilles.alecstamework.ownership.OwnerMutationContext context) {
                        if (!bondedCapture) sourceItem.commit();
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
                        effectService.playCaptureSuccessParticle(captureBurstParticleSystem, context);
                        effectService.playCaptureEffects(world, context.npcRef(), finalizedConfig);
                        logger.at(Level.FINE).log(
                                "Spawner stub: capture request item=" + itemStack.getItemId()
                                        + " targetUuid=" + targetUuid
                                        + " captureClearsOwner=" + finalizedConfig.isCaptureClearsOwner()
                        );
                        captureLog.capture(
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
                        captureAttemptRuntime.quarantine(finalizedAttemptId, reason);
                        captureLog.capture(
                                "capture denied reason=" + reason
                                        + " player=" + player.getUuid()
                                        + " targetUuid=" + targetUuid
                        );
                    }

                    @Override
                    public void onPopulationCommitted(
                            com.alechilles.alecstamework.ownership.CompanionPopulationCommitResult result) {
                        profilePersistence.persist(
                                targetUuid, finalizedOwnerToStore, finalizedCaptureRoleId,
                                finalizedSnapshotDisplayName);
                        if (bondedCapture) {
                            bondedVessels.bindInitialCapture(
                                    bindingAuthority,
                                    sourceItem, itemStack,
                                    profiledCaptureItem.get(), capturedProfileId.get(),
                                    world, player.getUuid(), sourceHotbarSlot, result,
                                    preparedMutation == null ? null
                                            : preparedMutation.populationOperationId(),
                                    finalizedAttemptId);
                        }
                        if (!bondedCapture) captureAttemptRuntime.commit(finalizedAttemptId);
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
                };
        return preparedMutation == null
                ? captureFinalizerService.finalizeCapture(
                        player, finalizedConfig, targetRef,
                        detachPlan.durableContextJson(), callbacks)
                : preparedMutation.apply(callbacks);
    }

    @Nullable
    static UUID resolveCapturedOwnerMetadata(@Nullable UUID existingOwner, boolean captureClearsOwner) {
        return captureClearsOwner ? null : existingOwner;
    }
    public void logCaptureChannelDiagnostic(String message) {
        captureLog.capture("interaction status=denied " + message);
    }

}
