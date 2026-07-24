package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.Vector3View;
import com.alechilles.alecstamework.command.roster.CommandFamilyRosterService;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.ClearCombatStep;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.ClearTargetStep;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.CommandEntry;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.CommandStep;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.FailurePolicy;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.ModeMapping;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.MoveSource;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.MoveToPositionStep;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.SetStateStep;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.SetTargetStep;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.StoreHomeStep;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.StoreSource;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.TargetSource;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.TriggerHookStep;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.metrics.TameworkTelemetryContext;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkHookComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.npc.progression.CompanionTalentService;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.alechilles.alecstamework.persistence.health.PersistencePlayerFeedback;
import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import com.alechilles.alecstamework.ownership.CompanionIdentityResolver;
import com.alechilles.alecstamework.ui.TameworkCompanionTalentsPage;
import com.alechilles.alecstamework.ui.TameworkCommandSelectionPage;
import com.alechilles.alecstamework.ui.TameworkUiMessageService;
import com.hypixel.hytale.builtin.mounts.MountPlugin;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import org.joml.Vector3d;
import org.joml.Vector3f;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import it.unimi.dsi.fastutil.Pair;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * Handles command-item linking and command dispatch.
 */
public final class CommandItemFeatureHandler {
    private static final double DEFAULT_RAYCAST_DISTANCE = 64.0;
    private static final double HYBRID_TELEPORT_DISTANCE_THRESHOLD = 96.0;
    private static final double HYBRID_PATH_DISTANCE_BEFORE_TELEPORT = 24.0;
    private static final long HYBRID_TELEPORT_DELAY_MS = 2500L;
    private static final double RECALL_SAFE_SPAWN_DISTANCE = 20.0;
    private static final double RECALL_FORCE_RELOCATE_DISTANCE = 80.0;
    private static final String CYCLE_SELECTION_COMMAND_ID = "CycleSelection";
    private static final String OPEN_SELECTION_MENU_COMMAND_ID = "OpenSelectionMenu";
    private static final long RESPAWN_FOLLOW_RETRY_DELAY_MS = 1250L;

    private final CommandItemRegistry registry;
    @Nullable private final TameworkPersistenceRuntime persistenceRuntime;
    private final CommandNpcRelocationService relocationService;
    private final CommandLinkedNpcDeathService deathService;
    private final CommandLinkedNpcCaptureService captureService;
    private final CommandLinkedNpcCoopService coopService;
    private final CommandLinkedNpcLostService lostService;
    private final CommandLinkedNpcStateSnapshotService stateSnapshotService;
    private final CommandLinkedNpcRecordStore linkedNpcRecordStore;
    private final CommandGroupService groupService;
    private final CommandFeedbackService feedbackService;
    private final CommandNpcNameResolver npcNameResolver;
    private final CommandLinkedPanelEntryService panelEntryService;
    private final CommandPanelEntrySourceService panelEntrySourceService;
    private final CommandToolInventoryService toolInventoryService;
    private final CommandResolutionService resolutionService;
    private final CommandLinkPolicyService linkPolicyService;
    private final CommandCompanionPlacementService companionPlacementService;
    private final CommandRecipientService recipientService;
    private final CommandStepExecutionService stepExecutionService;
    private final CommandLinkMutationService linkMutationService;
    private final CommandNpcExistenceService npcExistenceService;
    private final CommandCanonicalRecordCommitGate canonicalRecordCommitGate;
    private final CommandLinkedNpcInventoryRepairService inventoryRepairService;
    @Nullable
    private final CommandNpcProfileActionResolver profileActionResolver;
    private final CommandRelocationDispatchService relocationDispatchService;
    private final CommandRespawnService respawnService;
    @Nullable
    private final CommandLostRecoveryCoordinator lostRecoveryCoordinator;
    private final CommandOwnerReleaseService ownerReleaseService;
    private final CommandOwnerCullService ownerCullService;
    private final CommandMenuMoveService menuMoveService;
    private final CommandLinkedNpcLocateService locateService;
    private final CommandPanelPreferenceService panelPreferenceService;
    private final CommandPanelActionService panelActionService;
    private final CommandGroupManagerPageService groupManagerPageService;
    private final CommandGroupAssignPageService groupAssignPageService;
    private final CommandGroupActivationService groupActivationService;
    private final CommandTalentPageService talentPageService;
    @Nullable private final CommandRosterActionAuthority rosterActionAuthority;

    public CommandItemFeatureHandler(CommandItemRegistry registry,
                                     CommandNpcRelocationService relocationService,
                                     CommandLinkedNpcDeathService deathService,
                                     CommandLinkedNpcCaptureService captureService,
                                     CommandLinkedNpcCoopService coopService,
                                     CommandLinkedNpcLostService lostService,
                                     CommandLinkedNpcStateSnapshotService stateSnapshotService) {
        this(registry, relocationService, deathService, captureService, coopService, lostService,
                stateSnapshotService, null, null);
    }

    public CommandItemFeatureHandler(CommandItemRegistry registry,
                                     CommandNpcRelocationService relocationService,
                                     CommandLinkedNpcDeathService deathService,
                                     CommandLinkedNpcCaptureService captureService,
                                     CommandLinkedNpcCoopService coopService,
                                     CommandLinkedNpcLostService lostService,
                                     CommandLinkedNpcStateSnapshotService stateSnapshotService,
                                     @Nullable TameworkPersistenceRuntime persistenceRuntime) {
        this(registry, relocationService, deathService, captureService, coopService, lostService,
                stateSnapshotService, persistenceRuntime, null);
    }

    public CommandItemFeatureHandler(CommandItemRegistry registry,
                                     CommandNpcRelocationService relocationService,
                                     CommandLinkedNpcDeathService deathService,
                                     CommandLinkedNpcCaptureService captureService,
                                     CommandLinkedNpcCoopService coopService,
                                     CommandLinkedNpcLostService lostService,
                                     CommandLinkedNpcStateSnapshotService stateSnapshotService,
                                     @Nullable TameworkPersistenceRuntime persistenceRuntime,
                                     @Nullable CompanionIdentityResolver populationIdentityResolver) {
        this(registry, relocationService, deathService, captureService, coopService, lostService,
                stateSnapshotService, persistenceRuntime, populationIdentityResolver, null);
    }

    public CommandItemFeatureHandler(CommandItemRegistry registry,
                                     CommandNpcRelocationService relocationService,
                                     CommandLinkedNpcDeathService deathService,
                                     CommandLinkedNpcCaptureService captureService,
                                     CommandLinkedNpcCoopService coopService,
                                     CommandLinkedNpcLostService lostService,
                                     CommandLinkedNpcStateSnapshotService stateSnapshotService,
                                     @Nullable TameworkPersistenceRuntime persistenceRuntime,
                                     @Nullable CompanionIdentityResolver populationIdentityResolver,
                                     @Nullable CommandFamilyRosterService rosterService) {
        this(registry, relocationService, deathService, captureService, coopService, lostService,
                stateSnapshotService, persistenceRuntime, populationIdentityResolver, rosterService, null);
    }

    public CommandItemFeatureHandler(CommandItemRegistry registry,
                                     CommandNpcRelocationService relocationService,
                                     CommandLinkedNpcDeathService deathService,
                                     CommandLinkedNpcCaptureService captureService,
                                     CommandLinkedNpcCoopService coopService,
                                     CommandLinkedNpcLostService lostService,
                                     CommandLinkedNpcStateSnapshotService stateSnapshotService,
                                     @Nullable TameworkPersistenceRuntime persistenceRuntime,
                                     @Nullable CompanionIdentityResolver populationIdentityResolver,
                                     @Nullable CommandFamilyRosterService rosterService,
                                     @Nullable Executor rosterReadExecutor) {
        this.registry = registry;
        this.persistenceRuntime = persistenceRuntime;
        this.relocationService = relocationService;
        this.deathService = deathService;
        this.captureService = captureService;
        this.coopService = coopService;
        this.lostService = lostService;
        this.stateSnapshotService = stateSnapshotService;
        this.rosterActionAuthority = persistenceRuntime == null || rosterReadExecutor == null ? null
                : new CommandRosterActionAuthority(
                persistenceRuntime.getCommandFamilyRosterRepository(),
                persistenceRuntime.getNpcProfileRepository(), rosterReadExecutor, rosterService);
        this.linkedNpcRecordStore = new CommandLinkedNpcRecordStore();
        this.groupService = new CommandGroupService();
        this.feedbackService = new CommandFeedbackService(new TameworkUiMessageService());
        this.npcNameResolver = new CommandNpcNameResolver();
        this.linkPolicyService = new CommandLinkPolicyService();
        this.npcExistenceService = stateSnapshotService != null
                ? new CommandNpcExistenceService(stateSnapshotService.getLoadedNpcIdentityIndex())
                : new CommandNpcExistenceService();
        CommandNpcIdentityService npcIdentityService = persistenceRuntime != null
                ? new CommandNpcIdentityService(
                        persistenceRuntime.getNpcIdentityRepository(), npcExistenceService)
                : null;
        this.profileActionResolver = npcIdentityService != null
                ? new CommandNpcProfileActionResolver(npcIdentityService)
                : null;
        this.panelEntryService = new CommandLinkedPanelEntryService(
                linkedNpcRecordStore,
                deathService,
                captureService,
                coopService,
                lostService,
                relocationService,
                npcNameResolver,
                stateSnapshotService,
                persistenceRuntime != null ? persistenceRuntime.getNpcProfileRepository() : null,
                linkPolicyService,
                this.groupService,
                profileActionResolver,
                persistenceRuntime != null ? persistenceRuntime.getQuarantineRegistry() : null
        );
        this.resolutionService = new CommandResolutionService(registry, DEFAULT_RAYCAST_DISTANCE);
        this.panelPreferenceService = new CommandPanelPreferenceService();
        this.panelEntrySourceService = new CommandPanelEntrySourceService(
                panelEntryService,
                panelPreferenceService,
                linkPolicyService,
                npcNameResolver,
                persistenceRuntime != null ? persistenceRuntime.getCommandFamilyRosterRepository() : null,
                persistenceRuntime != null ? persistenceRuntime.getNpcProfileRepository() : null,
                rosterActionAuthority
        );
        this.linkMutationService = new CommandLinkMutationService(
                linkedNpcRecordStore,
                linkPolicyService,
                npcNameResolver,
                stateSnapshotService
        );
        this.toolInventoryService = new CommandToolInventoryService(
                panelEntryService,
                panelEntrySourceService,
                panelPreferenceService,
                this.groupService
        );
        this.talentPageService = new CommandTalentPageService(
                linkMutationService,
                toolInventoryService,
                feedbackService,
                npcNameResolver
        );
        this.companionPlacementService = new CommandCompanionPlacementService();
        this.stepExecutionService = new CommandStepExecutionService(
                relocationService,
                linkedNpcRecordStore,
                npcNameResolver
        );
        this.canonicalRecordCommitGate = new CommandCanonicalRecordCommitGate();
        this.inventoryRepairService =
                new CommandLinkedNpcInventoryRepairService(registry, profileActionResolver);
        this.recipientService = new CommandRecipientService(
                linkPolicyService,
                linkedNpcRecordStore,
                panelPreferenceService,
                profileActionResolver
        );
        this.relocationDispatchService = new CommandRelocationDispatchService(
                relocationService,
                deathService,
                captureService,
                coopService,
                resolutionService,
                stepExecutionService,
                companionPlacementService,
                persistenceRuntime != null
                        ? new CommandRelocationPersistenceGate(
                        persistenceRuntime.getMutationAvailabilityService(),
                        persistenceRuntime.getPersistenceScopeFactory(),
                        persistenceRuntime.getNpcProfileRepository())
                        : null
        );
        this.respawnService = new CommandRespawnService(
                companionPlacementService,
                linkPolicyService,
                linkMutationService,
                npcNameResolver,
                deathService,
                stepExecutionService
        );
        this.lostRecoveryCoordinator = persistenceRuntime != null && populationIdentityResolver != null
                ? new CommandLostRecoveryCoordinator(
                    npcIdentityService,
                    persistenceRuntime.getLostRepository(),
                    persistenceRuntime.getNpcRecoveryOperationRepository(),
                    persistenceRuntime.getNpcLiveAliasRepairRepository(),
                    inventoryRepairService,
                    companionPlacementService,
                    new PlannedNpcProjectionSpawner(),
                    new PlannedNpcProjectionPostAddService(),
                    stepExecutionService,
                    populationIdentityResolver)
                : null;
        this.ownerReleaseService = new CommandOwnerReleaseService(
                linkPolicyService,
                stepExecutionService,
                feedbackService,
                npcNameResolver
        );
        this.ownerCullService = new CommandOwnerCullService(
                linkPolicyService,
                linkMutationService,
                feedbackService,
                npcNameResolver
        );
        this.menuMoveService = new CommandMenuMoveService(
                resolutionService,
                linkMutationService,
                deathService,
                captureService,
                coopService,
                lostService,
                relocationDispatchService,
                stepExecutionService,
                feedbackService,
                HYBRID_TELEPORT_DISTANCE_THRESHOLD,
                HYBRID_PATH_DISTANCE_BEFORE_TELEPORT,
                HYBRID_TELEPORT_DELAY_MS,
                RECALL_SAFE_SPAWN_DISTANCE,
                RECALL_FORCE_RELOCATE_DISTANCE,
                profileActionResolver
        );
        this.locateService = new CommandLinkedNpcLocateService(
                linkMutationService,
                relocationService,
                feedbackService,
                npcNameResolver,
                toolInventoryService
        );
        this.panelActionService = new CommandPanelActionService(
                linkMutationService,
                toolInventoryService,
                panelPreferenceService,
                feedbackService,
                this.groupService,
                rosterActionAuthority
        );
        this.groupManagerPageService = new CommandGroupManagerPageService(
                panelActionService,
                this.groupService
        );
        this.groupActivationService = new CommandGroupActivationService(
                linkedNpcRecordStore,
                this.groupService
        );
        this.groupAssignPageService = new CommandGroupAssignPageService(
                panelActionService,
                toolInventoryService,
                groupActivationService
        );
    }

    public void queueWorldChangeTravelRelocationsForPlayerUuid(World destinationWorld, UUID playerUuid) {
        if (destinationWorld == null || playerUuid == null) {
            return;
        }
        if (relocationService == null) {
            return;
        }
        dismountPlayerAfterWorldJoin(destinationWorld, playerUuid);
        Store<EntityStore> destinationStore =
                destinationWorld.getEntityStore() != null ? destinationWorld.getEntityStore().getStore() : null;
        if (destinationStore == null) {
            return;
        }
        Ref<EntityStore> playerRef = destinationWorld.getEntityRef(playerUuid);
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }
        Player player = destinationStore.getComponent(playerRef, Player.getComponentType());
        if (player == null) {
            return;
        }
        queueWorldChangeTravelRelocations(player, destinationWorld);
    }

    /**
     * Resolves the live player on their current world thread and lazily repairs every command-item
     * copy in hotbar, storage, and backpack. Callers must queue this method through that world.
     */
    public void canonicalizePlayerCommandInventory(@Nullable World world, @Nullable UUID playerUuid) {
        if (world == null || playerUuid == null) {
            return;
        }
        Store<EntityStore> store =
                world.getEntityStore() != null ? world.getEntityStore().getStore() : null;
        if (store == null) {
            return;
        }
        Ref<EntityStore> playerRef = world.getEntityRef(playerUuid);
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }
        Player player = store.getComponent(playerRef, Player.getComponentType());
        if (player != null && player.getWorld() == world) {
            inventoryRepairService.canonicalize(player);
        }
    }

    void dismountPlayerAfterWorldJoin(World world, UUID playerUuid) {
        if (world == null || playerUuid == null) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore() != null ? world.getEntityStore().getStore() : null;
        if (store == null) {
            return;
        }
        Ref<EntityStore> playerRef = world.getEntityRef(playerUuid);
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }
        Player player = store.getComponent(playerRef, Player.getComponentType());
        if (player == null || player.getMountEntityId() == 0) {
            return;
        }
        MountPlugin.checkDismountNpc(store, playerRef, player);
    }

    private void queueWorldChangeTravelRelocations(Player player, World destinationWorld) {
        if (player == null || destinationWorld == null || relocationService == null) {
            return;
        }
        if (player.getWorld() != destinationWorld) {
            return;
        }
        Inventory inventory = player.getInventory();
        if (inventory == null || inventory.getHotbar() == null) {
            return;
        }
        Store<EntityStore> destinationStore =
                destinationWorld.getEntityStore() != null ? destinationWorld.getEntityStore().getStore() : null;
        Ref<EntityStore> playerRef = player.getReference();
        if (destinationStore == null || playerRef == null || !playerRef.isValid()) {
            return;
        }
        UUID ownerUuid = player.getUuid();
        ItemContainer hotbar = inventory.getHotbar();
        short capacity = hotbar.getCapacity();
        Set<UUID> queuedNpcUuids = new HashSet<>();
        for (short slot = 0; slot < capacity; slot++) {
            ItemStack stack = hotbar.getItemStack(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            TwCommandItemConfig config = resolutionService.resolveConfig(stack.getItemId(), null);
            if (config == null || !config.isEnabled()) {
                continue;
            }
            String toolId = stack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_TOOL_ID, Codec.STRING);
            if (toolId == null || toolId.isBlank()) {
                continue;
            }
            List<LinkedNpcRecord> linkedRecords;
            if (config.usesOwnerCommandFamilyRoster()) {
                CommandRosterActionAuthority.Resolution roster = resolveRoster(player, config, toolId);
                linkedRecords = roster.snapshot() == null || rosterActionAuthority == null
                        ? List.of() : rosterActionAuthority.project(roster.snapshot());
            } else {
                linkedRecords = linkMutationService.readLinkedNpcRecords(stack);
            }
            if (linkedRecords.isEmpty()) {
                continue;
            }
            if (!config.usesOwnerCommandFamilyRoster() && profileActionResolver != null) {
                CommandNpcProfileActionResolver.CanonicalRecords canonical =
                        profileActionResolver.canonicalizeRecords(linkedRecords);
                if (!canonical.safeToPersist()) {
                    continue;
                }
                linkedRecords = canonical.records();
                if (canonical.identityChanged()) {
                    ItemStack canonicalStack =
                            linkMutationService.writeLinkedNpcRecords(stack, linkedRecords);
                    short canonicalSlot = slot;
                    boolean committed = canonicalRecordCommitGate.commitBeforeAction(
                            true,
                            () -> {
                                ItemStackSlotTransaction transaction =
                                        hotbar.setItemStackForSlot(canonicalSlot, canonicalStack);
                                return transaction != null && transaction.succeeded();
                            }
                    );
                    if (!committed) {
                        continue;
                    }
                    stack = canonicalStack;
                }
            }
            for (LinkedNpcRecord cachedRecord : linkedRecords) {
                LinkedNpcRecord record = resolveRelocationRecord(cachedRecord);
                if (record == null || record.npcUuid == null || !record.active
                        || queuedNpcUuids.contains(record.npcUuid)) {
                    continue;
                }
                if (deathService != null
                        && deathService.getDeadSnapshotForTool(record.npcUuid, toolId, ownerUuid) != null) {
                    continue;
                }
                if (captureService != null
                        && captureService.getCapturedSnapshotForToolOrOwner(
                        record.npcUuid,
                        toolId,
                        ownerUuid
                ) != null) {
                    continue;
                }
                if (coopService != null
                        && coopService.getCoopSnapshotForToolOrOwner(
                        record.npcUuid,
                        toolId,
                        ownerUuid
                ) != null) {
                    continue;
                }
                if (lostService != null && lostService.isLost(record.npcUuid)) {
                    continue;
                }
                String roleId = resolveTravelRoleId(record);
                TwCompanionConfig.EffectiveSettings settings = TwCompanionConfig.resolveEffectiveForRole(roleId);
                if (!settings.isFollowMasterOnWorldChange()) {
                    continue;
                }
                if (!CommandWorldChangeEligibility.isEligible(record, settings)) {
                    continue;
                }
                RelocationState travelState = resolveTravelRelocationState(record);
                Vector3d sourceHint = record.lastKnownPosition != null ? record.lastKnownPosition : record.homePosition;
                double safeSpawnDistance = resolvePositiveDouble(
                        settings.getRecallSafeSpawnDistance(),
                        RECALL_SAFE_SPAWN_DISTANCE
                );
                Vector3d safeDestination = companionPlacementService.computeSafeRecallPosition(
                        playerRef,
                        destinationStore,
                        safeSpawnDistance,
                        roleId,
                        sourceHint
                );
                if (safeDestination == null) {
                    continue;
                }
                relocationService.queueRelocation(
                        destinationWorld,
                        record.npcUuid,
                        safeDestination,
                        ownerUuid,
                        true,
                        true,
                        travelState.state,
                        travelState.subState,
                        0L,
                        sourceHint,
                        record.homePosition,
                        true,
                        settings.getOnTransferFailure(),
                        settings.getFollowMasterOnWorldChangeStateFilter()
                );
                queuedNpcUuids.add(record.npcUuid);
            }
        }
    }

    @Nullable
    private LinkedNpcRecord resolveRelocationRecord(@Nullable LinkedNpcRecord record) {
        if (record == null || record.npcUuid == null || profileActionResolver == null) {
            return record;
        }
        CommandNpcProfileActionResolver.ActionTarget target =
                profileActionResolver.resolveRelocation(record);
        return target.isActionable() ? target.resolvedRecord() : null;
    }

    private RelocationState resolveTravelRelocationState(LinkedNpcRecord record) {
        if (record == null || record.cachedCommandState == null || record.cachedCommandState.isBlank()) {
            return new RelocationState(null, null);
        }
        String cachedState = record.cachedCommandState.trim();
        int separator = cachedState.indexOf('.');
        if (separator < 0) {
            return new RelocationState(cachedState, null);
        }
        String state = cachedState.substring(0, separator).trim();
        String subState = separator + 1 < cachedState.length() ? cachedState.substring(separator + 1).trim() : null;
        return new RelocationState(
                state == null || state.isBlank() ? null : state,
                subState == null || subState.isBlank() ? null : subState
        );
    }

    // Handles a single command-item use.
    public boolean handleUse(Player player,
                             ItemStack itemStack,
                             Ref<EntityStore> targetRef,
                             String configIdOverride,
                             String commandIdOverride) {
        if (player == null || itemStack == null || itemStack.isEmpty()) {
            return false;
        }
        World world = player.getWorld();
        if (world == null) {
            return false;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        Ref<EntityStore> playerRef = player.getReference();
        if (store == null || playerRef == null || !playerRef.isValid()) {
            return false;
        }

        TwCommandItemConfig config = resolutionService.resolveConfig(itemStack.getItemId(), configIdOverride);
        if (config == null || !config.isEnabled()) {
            return false;
        }

        CommandToolInventoryService.ToolResolution tool = toolInventoryService.ensureToolId(itemStack);
        ItemStack working = tool.stack;
        boolean updateHeldItem = tool.changed;
        if (tool.toolId == null || tool.toolId.isBlank()) {
            return false;
        }
        if (config.usesOwnerCommandFamilyRoster()) {
            CommandRosterActionAuthority.Resolution roster = resolveRoster(player, config, tool.toolId);
            if (roster.snapshot() == null) {
                if (isOpenSelectionMenuCommand(commandIdOverride)) {
                    if (updateHeldItem) updateHeldItem(player, working);
                    if (queueRosterMenuAfterRefresh(player, config, tool.toolId)) return true;
                }
                feedbackService.showWarningKey(
                        player, "tamework.ui.notifications.persistence.authorityNotReady");
                if (updateHeldItem) updateHeldItem(player, working);
                return false;
            }
            working = linkMutationService.writeLinkedNpcRecords(
                    working, rosterActionAuthority.project(roster.snapshot()));
            updateHeldItem = true;
        }
        ItemStack reconciled = config.usesOwnerCommandFamilyRoster() ? working
                : reconcileStaleLinkedNpcRecords(player, store, config, working, tool.toolId);
        if (reconciled != working) {
            working = reconciled;
            updateHeldItem = true;
        }

        if (isOpenSelectionMenuCommand(commandIdOverride)) {
            if (updateHeldItem) {
                updateHeldItem(player, working);
            }
            boolean opened = openSelectionMenu(player, store, config, working, tool.toolId);
            if (!opened) {
                feedbackService.showWarningKey(player, "tamework.ui.notifications.command.selection.noChoices");
            }
            return opened;
        }

        if (isCycleSelectionCommand(commandIdOverride)) {
            CommandSelectionResult selection = cycleSelectedCommand(config, working);
            if (selection.changed) {
                working = selection.stack;
                updateHeldItem = true;
            }
            if (updateHeldItem) {
                updateHeldItem(player, working);
            }
            if (selection.command == null) {
                feedbackService.showWarningKey(player, "tamework.ui.notifications.command.shared.noConfiguredCommand");
                return false;
            }
            String label = resolveCommandLabel(player, selection.command);
            feedbackService.showDefaultKey(player, "tamework.ui.notifications.command.selection.selected", label);
            return true;
        }

        if (targetRef != null && config.isLinkEnabled() && config.isLinkUseTogglesMembership()
                && !config.usesOwnerCommandFamilyRoster()) {
            LinkToggleResult link = linkMutationService.tryToggleLink(
                    player,
                    store,
                    targetRef,
                    tool.toolId,
                    config,
                    working,
                    (livePlayer, liveStore, liveTarget) -> handleDeferredLink(
                            livePlayer,
                            liveStore,
                            liveTarget,
                            tool.toolId,
                            config
                    )
            );
            if (link.pending) {
                if (updateHeldItem) {
                    updateHeldItem(player, working);
                }
                return true;
            }
            if (link.toggled) {
                if (link.updatedItem != null) {
                    working = link.updatedItem;
                    updateHeldItem = true;
                }
                if (updateHeldItem) {
                    updateHeldItem(player, working);
                }
                if (link.linked && !link.active) {
                    feedbackService.showSuccessKey(player, "tamework.ui.notifications.command.link.successInactive", link.npcName);
                } else {
                    feedbackService.showSuccessKey(
                            player,
                            link.linked
                                    ? "tamework.ui.notifications.command.link.success"
                                    : "tamework.ui.notifications.command.link.unlinked",
                            link.npcName
                    );
                }
                return true;
            }
        }

        int cooldownMs = Math.max(0, config.getCooldownSeconds()) * 1000;
        if (isCooldownActive(working, cooldownMs)) {
            if (updateHeldItem) {
                updateHeldItem(player, working);
            }
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.shared.cooldown");
            return false;
        }

        CommandEntry command = resolutionService.resolveCommand(config, commandIdOverride, working);
        if (command == null) {
            if (updateHeldItem) {
                updateHeldItem(player, working);
            }
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.shared.noConfiguredCommand");
            return false;
        }

        Ref<EntityStore> commandTarget = resolutionService.resolveCommandTarget(playerRef, store, config, command, targetRef);
        Vector3d raycastPosition = resolutionService.resolveRaycastPosition(playerRef, store, config, command);
        TwGlobalConfig globalConfig = TwGlobalConfig.resolveActive();
        TwCompanionConfig.EffectiveSettings defaultCompanionSettings =
                TwCompanionConfig.EffectiveSettings.fromGlobal(globalConfig);
        double returnHomeTeleportDistance = resolvePositiveDouble(
                defaultCompanionSettings.getReturnHomeTeleportDistance(),
                HYBRID_TELEPORT_DISTANCE_THRESHOLD
        );
        double returnHomePathDistanceBeforeTeleport = resolvePositiveDouble(
                defaultCompanionSettings.getReturnHomePathDistanceBeforeTeleport(),
                HYBRID_PATH_DISTANCE_BEFORE_TELEPORT
        );
        long returnHomeTeleportDelayMs = resolvePositiveLong(
                defaultCompanionSettings.getReturnHomeTeleportDelayMs(),
                HYBRID_TELEPORT_DELAY_MS
        );
        double recallSafeSpawnDistance = resolvePositiveDouble(
                defaultCompanionSettings.getRecallSafeSpawnDistance(),
                RECALL_SAFE_SPAWN_DISTANCE
        );
        double recallForceRelocateDistance = resolvePositiveDouble(
                defaultCompanionSettings.getRecallForceRelocateDistance(),
                RECALL_FORCE_RELOCATE_DISTANCE
        );
        Context context = new Context(
                player,
                playerRef,
                store,
                config,
                command,
                working.getItemId(),
                tool.toolId,
                commandTarget,
                raycastPosition,
                working,
                TameworkRuntimeSettings.blockAllPlayerDamageIfOwned(
                        defaultCompanionSettings.isBlockAllPlayerDamageIfOwned()
                ),
                TameworkRuntimeSettings.invulnerableIfOwned(defaultCompanionSettings.isInvulnerableIfOwned()),
                returnHomeTeleportDistance,
                returnHomePathDistanceBeforeTeleport,
                returnHomeTeleportDelayMs,
                recallSafeSpawnDistance,
                recallForceRelocateDistance
        );

        List<Candidate> recipients = recipientService.queryRecipients(context);
        List<LinkedNpcRecord> unloadedLinked = recipientService.queryUnloadedLinkedRecords(context, recipients);
        if (context.itemChanged && context.workingItem != working) {
            ItemStack canonicalStack = context.workingItem;
            if (!canonicalRecordCommitGate.commitBeforeAction(
                    true, () -> updateHeldItem(player, canonicalStack))) {
                feedbackService.showWarningKey(
                        player, "tamework.ui.notifications.command.shared.itemNotFound");
                return false;
            }
            working = canonicalStack;
            updateHeldItem = false;
            context.itemChanged = false;
        }
        if (recipients.isEmpty() && unloadedLinked.isEmpty()) {
            if (updateHeldItem) {
                updateHeldItem(player, working);
            }
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.execution.noRecipients");
            return false;
        }

        int affected = 0;
        Map<UUID, String> appliedCommandStates = new HashMap<>();
        if (!recipients.isEmpty()) {
            for (Candidate candidate : recipients) {
                StepResult stepResult = executeCommand(context, candidate);
                if (stepResult.applied) {
                    affected++;
                }
                if (stepResult.appliedState != null
                        && candidate != null
                        && candidate.npc != null
                        && candidate.npc.getUuid() != null) {
                    String cachedState = stepResult.appliedState.cachedValue();
                    if (cachedState != null) {
                        appliedCommandStates.put(candidate.npc.getUuid(), cachedState);
                    }
                }
                if (stepResult.abortAll) {
                    break;
                }
            }
        }
        ItemStack refreshedLinks = linkMutationService.refreshLinkedNpcPositions(
                context.workingItem, recipients, store, appliedCommandStates
        );
        if (refreshedLinks != context.workingItem) {
            context.workingItem = refreshedLinks;
            context.itemChanged = true;
            working = refreshedLinks;
            updateHeldItem = true;
        }
        CommandRelocationDispatchService.QueueResult relocationResult =
                relocationDispatchService.queueRelocationsForUnloaded(context, unloadedLinked);
        int queued = relocationResult.queued();
        if (context.workingItem != working) {
            working = context.workingItem;
            updateHeldItem = true;
        }
        if (affected <= 0 && queued <= 0) {
            if (updateHeldItem) {
                updateHeldItem(player, working);
            }
            if (relocationResult.firstRejection() != null) {
                feedbackService.showWarning(
                        player,
                        PersistencePlayerFeedback.resolve(
                                player,
                                PersistenceDomain.RECALL_RELOCATION,
                                relocationResult.firstRejection()
                        )
                );
            } else {
                feedbackService.showWarningKey(player, "tamework.ui.notifications.command.execution.none");
            }
            return false;
        }

        if (cooldownMs > 0) {
            working = working.withMetadata(
                    TameworkMetadataKeys.COMMAND_COOLDOWN_UNTIL,
                    Codec.LONG,
                    System.currentTimeMillis() + cooldownMs
            );
            updateHeldItem = true;
            context.workingItem = working;
            context.itemChanged = true;
        }
        if (updateHeldItem) {
            updateHeldItem(player, working);
        }
        feedbackService.emitCommandExecutionFeedback(
                context.player,
                context.playerRef,
                context.store,
                context.command,
                affected,
                queued,
                cmdEntry -> resolveCommandLabel(context.player, cmdEntry)
        );
        return true;
    }

    private boolean isCycleSelectionCommand(String commandIdOverride) {
        if (commandIdOverride == null || commandIdOverride.isBlank()) {
            return false;
        }
        return CYCLE_SELECTION_COMMAND_ID.equalsIgnoreCase(commandIdOverride.trim());
    }

    private boolean isOpenSelectionMenuCommand(String commandIdOverride) {
        if (commandIdOverride == null || commandIdOverride.isBlank()) {
            return false;
        }
        return OPEN_SELECTION_MENU_COMMAND_ID.equalsIgnoreCase(commandIdOverride.trim());
    }

    private boolean openSelectionMenu(Player player,
                                      Store<EntityStore> store,
                                      TwCommandItemConfig config,
                                      ItemStack working,
                                      String toolId) {
        if (player == null || store == null || config == null || toolId == null || toolId.isBlank()) {
            return false;
        }
        CommandEntry[] commands = config.getCommandList();
        if (commands == null || commands.length == 0) {
            return false;
        }
        if (player.getPageManager() == null) {
            return false;
        }
        String selectedId = working != null
                ? working.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_SELECTED_ID, Codec.STRING)
                : null;
        Ref<EntityStore> playerRef = player.getReference();
        if (playerRef == null || !playerRef.isValid()) {
            return false;
        }
        PlayerRef uiPlayerRef = player.getPlayerRef();
        if (uiPlayerRef == null || !uiPlayerRef.isValid()) {
            return false;
        }
        TwGlobalConfig globalConfig = TwGlobalConfig.resolveActive();
        boolean requireUnlinkConfirm = globalConfig == null || globalConfig.isCommandLinkedPanelRequireUnlinkConfirm();
        boolean recallTeleportingEnabled = CommandTravelSettings.isRecallTeleportingEnabled();
        TameworkCommandSelectionPage page = new TameworkCommandSelectionPage(
                uiPlayerRef,
                config,
                selectedId,
                requireUnlinkConfirm,
                () -> toolInventoryService.buildLinkedPanelEntriesForTool(player, toolId, config),
                () -> toolInventoryService.buildLinkedPanelBaseEntriesForTool(player, toolId, config),
                () -> toolInventoryService.resolvePanelModeValueForTool(player, toolId, config),
                () -> toolInventoryService.resolvePanelAutoLinkEnabledForTool(player, toolId),
                () -> toolInventoryService.resolvePanelRadiusLabelForTool(player, toolId, config),
                () -> toolInventoryService.resolvePanelSortValueForTool(player, toolId),
                () -> toolInventoryService.resolvePanelFilterModeValueForTool(player, toolId),
                () -> toolInventoryService.resolvePanelFilterInputForTool(player, toolId),
                () -> groupAssignPageService.resolveGroupActivationDropdownEntries(player, toolId),
                () -> groupAssignPageService.resolveGroupActivationValue(player, toolId),
                () -> groupAssignPageService.resolveGroupDropdownEntries(player, toolId),
                entry -> recallTeleportingEnabled || !resolutionService.isRecallCommand(entry),
                recallTeleportingEnabled,
                npcUuid -> panelActionService.applyLink(player, toolId, config, npcUuid),
                npcUuid -> applyMenuUnlink(player, toolId, config, npcUuid),
                npcUuid -> panelActionService.applyToggleActive(player, toolId, config, npcUuid),
                npcUuid -> panelActionService.applyToggleBreeding(player, toolId, npcUuid),
                npcUuid -> applyMenuRelease(player, toolId, config, npcUuid),
                npcUuid -> applyMenuCull(player, toolId, config, npcUuid),
                npcUuid -> applyMenuRespawn(player, toolId, config, npcUuid),
                npcUuid -> applyMenuLocate(player, toolId, config, npcUuid),
                npcUuid -> applyMenuRecall(player, toolId, config, npcUuid),
                npcUuid -> applyMenuSetHome(player, toolId, config, npcUuid),
                npcUuid -> applyMenuReturnHome(player, toolId, config, npcUuid),
                npcUuid -> talentPageService.openTalentPage(
                        player,
                        toolId,
                        npcUuid,
                        () -> reopenSelectionMenu(player, config, toolId)
                ),
                value -> panelActionService.applySetPanelMode(player, toolId, value),
                enabled -> panelActionService.applySetAutoLinkEnabled(player, toolId, enabled),
                () -> panelActionService.applyAdjustPanelRadius(player, toolId, config, false),
                () -> panelActionService.applyAdjustPanelRadius(player, toolId, config, true),
                () -> openGroupManagerFromSelection(player, config, toolId),
                value -> panelActionService.applySetSort(player, toolId, value),
                value -> panelActionService.applySetFilterMode(player, toolId, value),
                value -> panelActionService.applySetSelectedFilterText(player, toolId, value),
                () -> panelActionService.applyClearFilters(player, toolId),
                value -> groupAssignPageService.applyGroupActivation(player, toolId, value),
                (npcUuid, groupId) -> groupAssignPageService.applyGroupAssignment(
                        player,
                        toolId,
                        config,
                        npcUuid,
                        groupId
                ),
                commandId -> applyMenuSelection(player, toolId, config, commandId)
        );
        try {
            player.getPageManager().openCustomPage(playerRef, store, page);
            return true;
        } catch (Throwable throwable) {
            TameworkTelemetryEvents.recordErrorIfAvailable(
                    "ui_page_open_failed",
                    throwable,
                    TameworkTelemetryContext.uiPage(
                            "TameworkCommandSelectionPage",
                            "command_item",
                            "open",
                            "Failed to open command selection page."
                    ).build()
            );
            return false;
        }
    }

    /**
     * Completes a cold roster-cache menu open without blocking the world thread. Only immutable
     * player identity is carried across the persistence read; the live player is resolved again
     * inside the owning world's executor before inventory or UI access.
     */
    private boolean queueRosterMenuAfterRefresh(@Nonnull Player player,
                                                @Nonnull TwCommandItemConfig config,
                                                @Nonnull String toolId) {
        WorldPlayerResolver.ResolvedPlayer current = WorldPlayerResolver.resolveCurrent(player);
        if (current == null || rosterActionAuthority == null) return false;
        World world = current.world();
        UUID ownerUuid = current.player().getUuid();
        rosterActionAuthority.refreshAsync(ownerUuid, config).whenComplete((refresh, failure) ->
                world.execute(() -> {
                    WorldPlayerResolver.ResolvedPlayer live =
                            WorldPlayerResolver.resolve(world, ownerUuid);
                    if (live == null) return;
                    CommandRosterActionAuthority.Resolution roster =
                            resolveRoster(live.player(), config, toolId);
                    if (failure != null || refresh == null || roster.snapshot() == null) {
                        feedbackService.showWarningKey(live.player(),
                                "tamework.ui.notifications.persistence.authorityNotReady");
                        return;
                    }
                    ItemStack stack = findCommandToolStack(live.player(), toolId);
                    Store<EntityStore> store = live.world().getEntityStore() == null
                            ? null : live.world().getEntityStore().getStore();
                    if (stack == null || stack.isEmpty() || store == null) {
                        feedbackService.showWarningKey(live.player(),
                                "tamework.ui.notifications.command.shared.itemNotFound");
                        return;
                    }
                    ItemStack projected = linkMutationService.writeLinkedNpcRecords(
                            stack, rosterActionAuthority.project(roster.snapshot()));
                    toolInventoryService.mutateToolStack(
                            live.player(), toolId, ignored -> projected);
                    if (!openSelectionMenu(live.player(), store, config, projected, toolId)) {
                        feedbackService.showWarningKey(live.player(),
                                "tamework.ui.notifications.command.selection.reopenFailed");
                    }
                }));
        return true;
    }

    private void openGroupManagerFromSelection(Player player,
                                               TwCommandItemConfig config,
                                               String toolId) {
        groupManagerPageService.openGroupManagerPage(
                player,
                toolId,
                () -> reopenSelectionMenu(player, config, toolId)
        );
    }

    private void reopenSelectionMenu(Player player,
                                     TwCommandItemConfig config,
                                     String toolId) {
        if (player == null || config == null || toolId == null || toolId.isBlank()) {
            return;
        }
        World world = player.getWorld();
        if (world == null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.selection.reopenFailed");
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (store == null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.selection.reopenFailed");
            return;
        }
        ItemStack toolStack = findCommandToolStack(player, toolId);
        if (toolStack == null || toolStack.isEmpty()) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.shared.itemNotFound");
            return;
        }
        boolean opened = openSelectionMenu(player, store, config, toolStack, toolId);
        if (!opened) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.selection.reopenFailed");
        }
    }

    private void applyMenuSelection(Player player,
                                    String toolId,
                                    TwCommandItemConfig config,
                                    String commandId) {
        if (player == null || toolId == null || toolId.isBlank() || config == null
                || commandId == null || commandId.isBlank()) {
            return;
        }
        CommandEntry selected = config.findCommandById(commandId);
        if (selected == null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.selection.unavailable");
            return;
        }
        boolean updated = toolInventoryService.setSelectedCommandOnTool(player, toolId, selected.getId());
        if (!updated) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.selection.applyFailed");
            return;
        }
        String label = resolveCommandLabel(player, selected);
        feedbackService.showDefaultKey(player, "tamework.ui.notifications.command.selection.selected", label);
    }

    private void applyMenuUnlink(Player player,
                                 String toolId,
                                 TwCommandItemConfig config,
                                 UUID npcUuid) {
        if (player == null || toolId == null || toolId.isBlank() || npcUuid == null) {
            return;
        }
        Inventory inventory = player.getInventory();
        if (inventory == null || inventory.getHotbar() == null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.unlink.unavailable");
            return;
        }
        if (config != null && config.usesOwnerCommandFamilyRoster()
                && rosterActionAuthority != null) {
            ItemStack access = toolInventoryService.findToolStack(player, toolId);
            rosterActionAuthority.removeMember(player.getUuid(), config,
                    access == null ? null : access.getItemId(), npcUuid).thenAccept(removed -> {
                if (removed) feedbackService.showSuccessKey(player,
                        "tamework.ui.notifications.command.unlink.success");
                else feedbackService.showWarningKey(player,
                        "tamework.ui.notifications.persistence.authorityNotReady");
            });
            return;
        }
        ItemContainer hotbar = inventory.getHotbar();
        short capacity = hotbar.getCapacity();
        for (short slot = 0; slot < capacity; slot++) {
            ItemStack stack = hotbar.getItemStack(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String stackToolId = stack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_TOOL_ID, Codec.STRING);
            if (stackToolId == null || !stackToolId.equals(toolId)) {
                continue;
            }
            ItemStack updatedStack = linkMutationService.removeLinkedNpcRecord(stack, npcUuid);
            boolean itemChanged = updatedStack != stack;
            boolean componentChanged = linkMutationService.unlinkLoadedNpcFromTool(player, npcUuid, toolId);
            if (!itemChanged && !componentChanged) {
                feedbackService.showWarningKey(player, "tamework.ui.notifications.command.shared.notLinkedToTool");
            } else {
                hotbar.setItemStackForSlot(slot, updatedStack);
                feedbackService.showSuccessKey(player, "tamework.ui.notifications.command.unlink.success");
            }
            return;
        }
        feedbackService.showWarningKey(player, "tamework.ui.notifications.command.shared.itemNotFound");
    }

    private void applyMenuRelease(Player player,
                                  String toolId,
                                  TwCommandItemConfig config,
                                  UUID presentationUuid) {
        if (config == null || !config.usesOwnerCommandFamilyRoster()) {
            ownerReleaseService.release(player, toolId, config, presentationUuid);
            return;
        }
        WorldPlayerResolver.ResolvedPlayer resolved = player == null
                ? null : WorldPlayerResolver.resolveCurrent(player);
        UUID ownerUuid = resolved == null ? null : resolved.player().getUuid();
        if (resolved == null || ownerUuid == null || rosterActionAuthority == null) {
            if (player != null) feedbackService.showWarningKey(
                    player, "tamework.ui.notifications.command.release.unavailable");
            return;
        }
        CommandRosterActionAuthority.Resolution roster = resolveRoster(
                resolved.player(), config, toolId);
        CommandRosterActionAuthority.Member member = roster.snapshot() == null ? null
                : roster.snapshot().findByPresentationUuid(presentationUuid);
        UUID liveNpcUuid = member == null ? null : member.currentNpcUuid();
        if (liveNpcUuid == null
                || !ownerReleaseService.canReleaseNow(resolved.player(), config, liveNpcUuid)) {
            feedbackService.showWarningKey(
                    resolved.player(), "tamework.ui.notifications.command.release.unavailable");
            return;
        }
        ItemStack access = toolInventoryService.findToolStack(resolved.player(), toolId);
        World owningWorld = resolved.world();
        rosterActionAuthority.removeMember(ownerUuid, config,
                access == null ? null : access.getItemId(), presentationUuid).whenComplete(
                (removed, failure) -> owningWorld.execute(() -> {
                    WorldPlayerResolver.ResolvedPlayer live =
                            WorldPlayerResolver.resolve(owningWorld, ownerUuid);
                    if (live == null) return;
                    if (failure != null || !Boolean.TRUE.equals(removed)) {
                        feedbackService.showWarningKey(live.player(),
                                "tamework.ui.notifications.persistence.authorityNotReady");
                        return;
                    }
                    ownerReleaseService.release(live.player(), toolId, config, liveNpcUuid);
                }));
    }

    private void applyMenuCull(Player player,
                               String toolId,
                               TwCommandItemConfig config,
                               UUID presentationUuid) {
        if (config == null || !config.usesOwnerCommandFamilyRoster()) {
            ownerCullService.cull(player, toolId, config, presentationUuid);
            return;
        }
        WorldPlayerResolver.ResolvedPlayer resolved = player == null
                ? null : WorldPlayerResolver.resolveCurrent(player);
        UUID ownerUuid = resolved == null ? null : resolved.player().getUuid();
        if (resolved == null || ownerUuid == null || rosterActionAuthority == null) {
            if (player != null) feedbackService.showWarningKey(
                    player, "tamework.ui.notifications.command.cull.unavailable");
            return;
        }
        CommandRosterActionAuthority.Resolution roster = resolveRoster(
                resolved.player(), config, toolId);
        CommandRosterActionAuthority.Member member = roster.snapshot() == null ? null
                : roster.snapshot().findByPresentationUuid(presentationUuid);
        UUID liveNpcUuid = member == null ? null : member.currentNpcUuid();
        if (liveNpcUuid == null
                || !ownerCullService.canCullNow(resolved.player(), config, liveNpcUuid)) {
            feedbackService.showWarningKey(
                    resolved.player(), "tamework.ui.notifications.command.cull.unavailable");
            return;
        }
        ItemStack access = toolInventoryService.findToolStack(resolved.player(), toolId);
        World owningWorld = resolved.world();
        rosterActionAuthority.removeMember(ownerUuid, config,
                access == null ? null : access.getItemId(), presentationUuid).whenComplete(
                (removed, failure) -> owningWorld.execute(() -> {
                    WorldPlayerResolver.ResolvedPlayer live =
                            WorldPlayerResolver.resolve(owningWorld, ownerUuid);
                    if (live == null) return;
                    if (failure != null || !Boolean.TRUE.equals(removed)) {
                        feedbackService.showWarningKey(live.player(),
                                "tamework.ui.notifications.persistence.authorityNotReady");
                        return;
                    }
                    ownerCullService.cull(live.player(), toolId, config, liveNpcUuid);
                }));
    }

    private void applyMenuRespawn(Player player,
                                  String toolId,
                                  TwCommandItemConfig commandConfig,
                                  UUID npcUuid) {
        if (player == null || toolId == null || toolId.isBlank() || npcUuid == null) {
            return;
        }
        if (deathService == null && lostService == null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.respawn.trackingUnavailable");
            return;
        }
        Inventory inventory = player.getInventory();
        if (inventory == null || inventory.getHotbar() == null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.respawn.unavailable");
            return;
        }
        World world = player.getWorld();
        if (world == null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.respawn.unavailable");
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        Ref<EntityStore> playerRef = player.getReference();
        if (store == null || playerRef == null || !playerRef.isValid()) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.respawn.unavailable");
            return;
        }
        if (commandConfig != null && commandConfig.usesOwnerCommandFamilyRoster()
                && refreshRosterProjection(player, toolId, commandConfig) == null) {
            feedbackService.showWarningKey(player,
                    "tamework.ui.notifications.command.respawn.trackingUnavailable");
            return;
        }
        ItemContainer hotbar = inventory.getHotbar();
        short capacity = hotbar.getCapacity();
        for (short slot = 0; slot < capacity; slot++) {
            ItemStack stack = hotbar.getItemStack(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String stackToolId = stack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_TOOL_ID, Codec.STRING);
            if (stackToolId == null || !stackToolId.equals(toolId)) {
                continue;
            }
            LinkedNpcRecord record = linkMutationService.findLinkedNpcRecord(linkMutationService.readLinkedNpcRecords(stack), npcUuid);
            if (record == null) {
                feedbackService.showWarningKey(player, "tamework.ui.notifications.command.shared.notLinkedToTool");
                return;
            }
            CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot deadSnapshot = deathService == null ? null
                    : commandConfig != null && commandConfig.usesOwnerCommandFamilyRoster()
                    ? deathService.getDeadSnapshot(npcUuid)
                    : deathService.getDeadSnapshotForTool(npcUuid, toolId, player.getUuid());
            if (deadSnapshot != null) {
                String roleId = deadSnapshot.roleId();
                if ((roleId == null || roleId.isBlank()) && record.cachedRoleId != null && !record.cachedRoleId.isBlank()) {
                    roleId = record.cachedRoleId;
                }
                TwCompanionConfig.EffectiveSettings companionSettings = TwCompanionConfig.resolveEffectiveForRole(roleId);
                if (!TameworkRuntimeSettings.reviveSystemEnabled(companionSettings.isDeadRespawnEnabled())) {
                    feedbackService.showWarningKey(player, "tamework.ui.notifications.command.respawn.disabled");
                    return;
                }
                long remainingMs = Math.max(0L, deadSnapshot.respawnAvailableAtMs() - System.currentTimeMillis());
                if (remainingMs > 0L) {
                    feedbackService.showWarningKey(
                            player,
                            "tamework.ui.notifications.command.respawn.cooldownRemaining",
                            formatDuration(player, remainingMs)
                    );
                    return;
                }
                double safeSpawnDistance = resolvePositiveDouble(
                        companionSettings.getRecallSafeSpawnDistance(),
                        RECALL_SAFE_SPAWN_DISTANCE
                );
                long followRetryDelayMs = resolvePositiveLong(
                        companionSettings.getDeadRespawnFollowRetryDelayMs(),
                        RESPAWN_FOLLOW_RETRY_DELAY_MS
                );
                String name = deadSnapshot.displayName();
                if (name == null || name.isBlank()) {
                    name = LocalizedText.resolve(
                            player,
                            "tamework.ui.notifications.command.shared.defaultCompanionName"
                    );
                }
                boolean started = respawnService.respawnDeadLinkedNpc(
                        player,
                        playerRef,
                        store,
                        toolId,
                        stack,
                        record,
                        deadSnapshot,
                        safeSpawnDistance,
                        followRetryDelayMs,
                        deadRespawnCompletion(world, player.getUuid(), slot, stack, name)
                );
                if (!started) {
                    feedbackService.showWarningKey(player, "tamework.ui.notifications.command.respawn.failed");
                }
                return;
            }
            if (lostRecoveryCoordinator == null) {
                feedbackService.showWarningKey(player, "tamework.ui.notifications.command.respawn.notDeadOrLost");
                return;
            }
            String roleId = record.cachedRoleId;
            TwCompanionConfig.EffectiveSettings companionSettings = TwCompanionConfig.resolveEffectiveForRole(roleId);
            double safeSpawnDistance = resolvePositiveDouble(
                    companionSettings.getRecallSafeSpawnDistance(),
                    RECALL_SAFE_SPAWN_DISTANCE
            );
            lostRecoveryCoordinator.request(
                    world,
                    player.getUuid(),
                    toolId,
                    record,
                    safeSpawnDistance,
                    this::onLostRecoveryComplete
            );
            return;
        }
        feedbackService.showWarningKey(player, "tamework.ui.notifications.command.shared.itemNotFound");
    }

    void canonicalizePlayerCommandInventory(@Nullable Holder<EntityStore> holder) {
        if (holder != null) {
            inventoryRepairService.canonicalize(holder);
        }
    }

    @Nonnull
    private CommandRespawnService.Completion deadRespawnCompletion(
            @Nonnull World world,
            @Nonnull UUID playerUuid,
            short slot,
            @Nonnull ItemStack expectedStack,
            @Nonnull String displayName) {
        return new CommandRespawnService.Completion() {
            @Override
            public boolean onApplied(@Nonnull CommandRespawnService.AppliedRespawn result) {
                WorldPlayerResolver.ResolvedPlayer resolved =
                        WorldPlayerResolver.resolve(world, playerUuid);
                Inventory inventory = resolved == null ? null : resolved.player().getInventory();
                ItemContainer hotbar = inventory == null ? null : inventory.getHotbar();
                if (resolved == null || hotbar == null) {
                    return false;
                }
                ItemStack current = hotbar.getItemStack(slot);
                if (!Objects.equals(current, result.updatedStack())) {
                    if (!Objects.equals(current, expectedStack)) {
                        feedbackService.showWarningKey(
                                resolved.player(),
                                "tamework.ui.notifications.command.respawn.failed"
                        );
                        return false;
                    }
                    hotbar.setItemStackForSlot(slot, result.updatedStack());
                }
                feedbackService.showSuccessKey(
                        resolved.player(),
                        "tamework.ui.notifications.command.respawn.success",
                        displayName
                );
                return true;
            }

            @Override
            public void onDenied(@Nonnull String reason) {
                showDeadRespawnFailure(world, playerUuid);
            }

            @Override
            public void onDurabilityDegraded(@Nonnull String reason) {
                showDeadRespawnFailure(world, playerUuid);
            }
        };
    }

    private void showDeadRespawnFailure(@Nonnull World world, @Nonnull UUID playerUuid) {
        WorldPlayerResolver.ResolvedPlayer resolved = WorldPlayerResolver.resolve(world, playerUuid);
        if (resolved != null) {
            feedbackService.showWarningKey(
                    resolved.player(),
                    "tamework.ui.notifications.command.respawn.failed"
            );
        }
    }

    private void onLostRecoveryComplete(
            @Nonnull Player player,
            @Nonnull CommandLostRecoveryCoordinator.Outcome outcome) {
        if (outcome.status() == CommandLostRecoveryCoordinator.OutcomeStatus.RECOVERED) {
            String name = outcome.companionName();
            if (name == null || name.isBlank()) {
                name = LocalizedText.resolve(
                        player, "tamework.ui.notifications.command.shared.defaultCompanionName");
            }
            feedbackService.showSuccessKey(
                    player, "tamework.ui.notifications.command.respawn.recovered", name);
            return;
        }
        if (outcome.status() == CommandLostRecoveryCoordinator.OutcomeStatus.ALREADY_LIVE_REPAIRED) {
            feedbackService.showSuccess(
                    player, "That companion is already live; command records were repaired.");
            return;
        }
        if (outcome.status() == CommandLostRecoveryCoordinator.OutcomeStatus.IN_PROGRESS) {
            feedbackService.showDefault(player, outcome.message());
            return;
        }
        String message = outcome.message();
        if (message == null || message.isBlank()) {
            message = LocalizedText.resolve(
                    player, "tamework.ui.notifications.command.respawn.recoverFailed");
        }
        feedbackService.showWarning(player, message);
    }

    private void applyMenuSetHome(Player player,
                                  String toolId,
                                  TwCommandItemConfig config,
                                  UUID npcUuid) {
        if (player == null || toolId == null || toolId.isBlank() || npcUuid == null) {
            return;
        }
        Inventory inventory = player.getInventory();
        if (inventory == null || inventory.getHotbar() == null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.setHome.unavailable");
            return;
        }
        World world = player.getWorld();
        if (world == null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.setHome.unavailable");
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (store == null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.setHome.unavailable");
            return;
        }
        if (config.usesOwnerCommandFamilyRoster() && rosterActionAuthority != null) {
            LinkedNpcRecord record = resolveRosterRecord(player, config, toolId, npcUuid);
            UUID liveUuid = record == null ? null : record.npcUuid;
            Ref<EntityStore> npcRef = liveUuid == null ? null : world.getEntityRef(liveUuid);
            TransformComponent transform = npcRef == null || !npcRef.isValid() ? null
                    : store.getComponent(npcRef, TransformComponent.getComponentType());
            if (transform == null) {
                feedbackService.showWarningKey(player,
                        "tamework.ui.notifications.command.setHome.mustBeLoaded");
                return;
            }
            Vector3d home = new Vector3d(transform.getPosition());
            ItemStack access = toolInventoryService.findToolStack(player, toolId);
            rosterActionAuthority.updateMember(player.getUuid(), config,
                    access == null ? null : access.getItemId(), npcUuid,
                    CommandRosterActionAuthority.MemberUpdate.home(
                            new Vector3View(home.x, home.y, home.z))).thenAccept(updated -> {
                if (updated) feedbackService.showSuccessKey(player,
                        "tamework.ui.notifications.command.setHome.success",
                        record.cachedDisplayName == null ? record.cachedRoleId : record.cachedDisplayName);
                else feedbackService.showWarningKey(player,
                        "tamework.ui.notifications.persistence.authorityNotReady");
            });
            return;
        }
        ItemContainer hotbar = inventory.getHotbar();
        short capacity = hotbar.getCapacity();
        for (short slot = 0; slot < capacity; slot++) {
            ItemStack stack = hotbar.getItemStack(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String stackToolId = stack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_TOOL_ID, Codec.STRING);
            if (stackToolId == null || !stackToolId.equals(toolId)) {
                continue;
            }
            LinkedNpcRecord record = linkMutationService.findLinkedNpcRecord(linkMutationService.readLinkedNpcRecords(stack), npcUuid);
            if (record == null) {
                feedbackService.showWarningKey(player, "tamework.ui.notifications.command.shared.notLinkedToTool");
                return;
            }
            Ref<EntityStore> npcRef = world.getEntityRef(npcUuid);
            if (npcRef == null || !npcRef.isValid()) {
                feedbackService.showWarningKey(player, "tamework.ui.notifications.command.setHome.mustBeLoaded");
                return;
            }
            NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
            if (npc == null) {
                feedbackService.showWarningKey(player, "tamework.ui.notifications.command.setHome.mustBeLoaded");
                return;
            }
            TameworkCommandLinksComponent links = store.getComponent(npcRef, TameworkCommandLinksComponent.getComponentType());
            if (links == null || !links.containsToolId(toolId)) {
                feedbackService.showWarningKey(player, "tamework.ui.notifications.command.shared.notLinkedToTool");
                return;
            }
            UUID ownerId = links.getOwnerId();
            if (ownerId != null && !ownerId.equals(player.getUuid())) {
                feedbackService.showWarningKey(player, "tamework.ui.notifications.command.setHome.notAllowed");
                return;
            }
            TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
            if (transform == null) {
                feedbackService.showWarningKey(player, "tamework.ui.notifications.command.setHome.positionUnavailable");
                return;
            }
            Vector3d home = new Vector3d(transform.getPosition());
            if (links.getOwnerId() == null && player.getUuid() != null) {
                links.setOwnerId(player.getUuid());
            }
            links.setHomePosition(home);
            store.putComponent(npcRef, TameworkCommandLinksComponent.getComponentType(), links);
            ItemStack updatedStack = linkMutationService.upsertLinkedNpcRecord(
                    stack,
                    npcUuid,
                    home,
                    world.getName(),
                    home,
                    npcNameResolver.resolveNpcDisplayNameFromComponents(npcRef, store),
                    npcNameResolver.resolveNpcNameKey(npc),
                    npcNameResolver.resolveNpcRoleId(npc)
            );
            hotbar.setItemStackForSlot(slot, updatedStack);
            feedbackService.showSuccessKey(
                    player,
                    "tamework.ui.notifications.command.setHome.success",
                    npcNameResolver.resolveNpcDisplayName(npcRef, store, npc)
            );
            return;
        }
        feedbackService.showWarningKey(player, "tamework.ui.notifications.command.shared.itemNotFound");
    }

    private void applyMenuRecall(Player player,
                                 String toolId,
                                 TwCommandItemConfig config,
                                 UUID npcUuid) {
        applyMenuMoveCommand(player, toolId, config, npcUuid, false);
    }

    private void applyMenuLocate(Player player,
                                 String toolId,
                                 TwCommandItemConfig config,
                                 UUID npcUuid) {
        if (config.usesOwnerCommandFamilyRoster()
                && refreshRosterProjection(player, toolId, config) == null) return;
        locateService.locate(player, toolId, npcUuid);
    }

    private void applyMenuReturnHome(Player player,
                                     String toolId,
                                     TwCommandItemConfig config,
                                     UUID npcUuid) {
        applyMenuMoveCommand(player, toolId, config, npcUuid, true);
    }

    private void applyMenuMoveCommand(Player player,
                                      String toolId,
                                      TwCommandItemConfig config,
                                      UUID npcUuid,
                                      boolean returnHome) {
        if (config.usesOwnerCommandFamilyRoster()
                && refreshRosterProjection(player, toolId, config) == null) return;
        menuMoveService.applyMenuMoveCommand(
                player,
                toolId,
                npcUuid,
                returnHome,
                cmdEntry -> resolveCommandLabel(player, cmdEntry)
        );
    }

    @Nullable
    private String resolveWorldName(@Nullable Store<EntityStore> store) {
        World world = store != null && store.getExternalData() != null
                ? store.getExternalData().getWorld()
                : null;
        if (world == null || world.getName() == null || world.getName().isBlank()) {
            return null;
        }
        return world.getName();
    }

    private ItemStack reconcileStaleLinkedNpcRecords(Player player,
                                                     Store<EntityStore> store,
                                                     TwCommandItemConfig config,
                                                     ItemStack stack,
                                                     String toolId) {
        if (player == null || store == null || stack == null || stack.isEmpty() || toolId == null || toolId.isBlank()) {
            return stack;
        }
        World world = player.getWorld();
        UUID ownerUuid = player.getUuid();
        if (world == null || ownerUuid == null) {
            return stack;
        }
        List<LinkedNpcRecord> records = linkedNpcRecordStore.read(stack);
        if (records.isEmpty()) {
            return stack;
        }

        Set<UUID> recordedNpcUuids = new HashSet<>();
        ArrayList<LinkedNpcRecord> staleRecords = new ArrayList<>();
        for (LinkedNpcRecord record : records) {
            if (record == null || record.npcUuid == null) {
                continue;
            }
            recordedNpcUuids.add(record.npcUuid);
            if (!isLikelyStaleLinkedRecord(record, world, store, toolId, ownerUuid)) {
                continue;
            }
            staleRecords.add(record);
        }
        if (staleRecords.isEmpty()) {
            return stack;
        }

        ArrayList<LinkedRecordCandidate> loadedCandidates = collectMissingLoadedLinkedCandidates(
                store,
                toolId,
                ownerUuid,
                recordedNpcUuids,
                config
        );
        if (loadedCandidates.isEmpty()) {
            return stack;
        }

        ArrayList<LinkedNpcRecord> updatedRecords = new ArrayList<>(records);
        Set<UUID> updatedUuids = new HashSet<>(recordedNpcUuids);
        boolean changed = false;
        for (LinkedRecordCandidate candidate : loadedCandidates) {
            if (candidate == null || candidate.npcUuid == null || updatedUuids.contains(candidate.npcUuid)) {
                continue;
            }
            LinkedNpcRecord staleMatch = selectStaleRecordForCandidate(staleRecords, candidate);
            if (staleMatch == null) {
                continue;
            }
            removeRecordByUuid(updatedRecords, staleMatch.npcUuid);
            staleRecords.remove(staleMatch);
            LinkedNpcRecord migrated = candidate.toRecord(staleMatch);
            updatedRecords.add(migrated);
            updatedUuids.add(candidate.npcUuid);
            changed = true;
            debugCoop(
                    "linked record auto-remap old=" + staleMatch.npcUuid
                            + " new=" + candidate.npcUuid
                            + " tool=" + toolId
            );
        }

        return changed ? linkedNpcRecordStore.write(stack, updatedRecords) : stack;
    }

    private boolean isLikelyStaleLinkedRecord(LinkedNpcRecord record,
                                              World world,
                                              Store<EntityStore> store,
                                              String toolId,
                                              UUID ownerUuid) {
        if (record == null || record.npcUuid == null || world == null || store == null) {
            return false;
        }
        if (isKnownUnavailableLinkedRecord(record.npcUuid, toolId, ownerUuid)) {
            return false;
        }
        Ref<EntityStore> entityRef = world.getEntityRef(record.npcUuid);
        if (entityRef == null || !entityRef.isValid()) {
            return true;
        }
        return safeGetNpc(store, entityRef) == null;
    }

    private boolean isKnownUnavailableLinkedRecord(UUID npcUuid, String toolId, UUID ownerUuid) {
        if (npcUuid == null) {
            return false;
        }
        if (deathService != null && deathService.getDeadSnapshotForTool(npcUuid, toolId, ownerUuid) != null) {
            return true;
        }
        if (captureService != null
                && captureService.getCapturedSnapshotForToolOrOwner(npcUuid, toolId, ownerUuid) != null) {
            return true;
        }
        if (coopService != null && coopService.getCoopSnapshotForToolOrOwner(npcUuid, toolId, ownerUuid) != null) {
            return true;
        }
        return lostService != null && lostService.isLost(npcUuid);
    }

    private ArrayList<LinkedRecordCandidate> collectMissingLoadedLinkedCandidates(Store<EntityStore> store,
                                                                                   String toolId,
                                                                                   UUID ownerUuid,
                                                                                   Set<UUID> recordedNpcUuids,
                                                                                   TwCommandItemConfig config) {
        ArrayList<LinkedRecordCandidate> out = new ArrayList<>();
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType = TameworkCommandLinksComponent.getComponentType();
        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        if (linksType == null || npcType == null) {
            return out;
        }
        store.forEachChunk(
                Query.and(linksType, npcType),
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
                    for (int i = 0; i < chunk.size(); i++) {
                        NPCEntity npc = chunk.getComponent(i, npcType);
                        TameworkCommandLinksComponent links = chunk.getComponent(i, linksType);
                        if (npc == null || links == null || npc.getUuid() == null) {
                            continue;
                        }
                        if (!links.containsToolId(toolId)) {
                            continue;
                        }
                        if (links.getOwnerId() != null && !links.getOwnerId().equals(ownerUuid)) {
                            continue;
                        }
                        if (recordedNpcUuids.contains(npc.getUuid())) {
                            continue;
                        }
                        String roleId = resolveRoleIdForCandidate(npc);
                        if (!linkPolicyService.isRoleAllowed(roleId, config)) {
                            continue;
                        }
                        Ref<EntityStore> npcRef = chunk.getReferenceTo(i);
                        if (npcRef == null || !npcRef.isValid()) {
                            continue;
                        }
                        TransformComponent transform = chunk.getComponent(i, TransformComponent.getComponentType());
                        Vector3d position = transform != null ? new Vector3d(transform.getPosition()) : null;
                        Vector3d homePosition = links.hasHome() ? links.getHomePosition() : null;
                        String displayName = npcNameResolver.resolveNpcDisplayNameFromComponents(npcRef, store);
                        String nameKey = npcNameResolver.resolveNpcNameKey(npc);
                        String cachedCommandState = resolveCachedCommandState(npc);
                        out.add(new LinkedRecordCandidate(
                                npc.getUuid(),
                                position,
                                resolveWorldName(store),
                                homePosition,
                                displayName,
                                nameKey,
                                roleId,
                                cachedCommandState
                        ));
                    }
                }
        );
        return out;
    }

    private LinkedNpcRecord selectStaleRecordForCandidate(List<LinkedNpcRecord> staleRecords,
                                                          LinkedRecordCandidate candidate) {
        if (staleRecords == null || staleRecords.isEmpty() || candidate == null || candidate.npcUuid == null) {
            return null;
        }
        String targetRole = normalizeIdentifier(candidate.cachedRoleId);
        LinkedNpcRecord roleMatch = null;
        for (LinkedNpcRecord stale : staleRecords) {
            if (stale == null || stale.npcUuid == null) {
                continue;
            }
            if (targetRole == null) {
                continue;
            }
            if (!targetRole.equals(normalizeIdentifier(stale.cachedRoleId))) {
                continue;
            }
            roleMatch = stale;
            break;
        }
        if (roleMatch != null) {
            return roleMatch;
        }
        for (LinkedNpcRecord stale : staleRecords) {
            if (stale != null && stale.npcUuid != null) {
                return stale;
            }
        }
        return null;
    }

    private void removeRecordByUuid(List<LinkedNpcRecord> records, UUID npcUuid) {
        if (records == null || records.isEmpty() || npcUuid == null) {
            return;
        }
        records.removeIf(record -> record != null && npcUuid.equals(record.npcUuid));
    }

    private NPCEntity safeGetNpc(Store<EntityStore> store, Ref<EntityStore> npcRef) {
        if (store == null || npcRef == null || !npcRef.isValid()) {
            return null;
        }
        try {
            return store.getComponent(npcRef, NPCEntity.getComponentType());
        } catch (IndexOutOfBoundsException | IllegalArgumentException ignored) {
            return null;
        }
    }

    private String resolveRoleIdForCandidate(NPCEntity npc) {
        if (npc == null) {
            return null;
        }
        String roleId = linkPolicyService.resolveRoleId(npc);
        if (roleId != null && !roleId.isBlank()) {
            return roleId;
        }
        return npcNameResolver.resolveNpcRoleId(npc);
    }

    private String resolveCachedCommandState(NPCEntity npc) {
        if (npc == null || npc.getRole() == null || npc.getRole().getStateSupport() == null) {
            return null;
        }
        String stateName = npc.getRole().getStateSupport().getStateName();
        return (stateName != null && !stateName.isBlank()) ? stateName : null;
    }

    private String normalizeIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private void debugCoop(String message) {
        CoopDebugLogger.log(message);
    }

    private String resolveTravelRoleId(LinkedNpcRecord record) {
        if (record != null && record.cachedRoleId != null && !record.cachedRoleId.isBlank()) {
            return record.cachedRoleId;
        }
        return null;
    }

    private String formatDuration(Player player, long remainingMs) {
        long totalSeconds = Math.max(0L, (remainingMs + 999L) / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        if (minutes <= 0L) {
            return LocalizedText.format(player, "tamework.ui.shared.duration.seconds", seconds);
        }
        return LocalizedText.format(
                player,
                "tamework.ui.shared.duration.minutesSeconds",
                minutes,
                seconds
        );
    }

    private ItemStack findCommandToolStack(Player player, String toolId) {
        return toolInventoryService.findToolStack(player, toolId);
    }

    @Nonnull
    private CommandRosterActionAuthority.Resolution resolveRoster(
            @Nonnull Player player, @Nonnull TwCommandItemConfig config, @Nullable String toolId) {
        if (rosterActionAuthority == null || player.getUuid() == null) {
            return CommandRosterActionAuthority.Resolution.denied(
                    "command-roster-authority-unavailable");
        }
        return rosterActionAuthority.resolveCached(player.getUuid(), config, toolId);
    }

    @Nullable
    private LinkedNpcRecord resolveRosterRecord(Player player, TwCommandItemConfig config,
                                                String toolId, UUID presentationUuid) {
        if (player == null || config == null || !config.usesOwnerCommandFamilyRoster()
                || presentationUuid == null) return null;
        CommandRosterActionAuthority.Resolution resolution = resolveRoster(player, config, toolId);
        CommandRosterActionAuthority.Member member = resolution.snapshot() == null ? null
                : resolution.snapshot().findByPresentationUuid(presentationUuid);
        if (member == null) return null;
        return rosterActionAuthority.project(new CommandRosterActionAuthority.Snapshot(
                resolution.snapshot().ownerUuid(), resolution.snapshot().commandFamilyId(),
                resolution.snapshot().rosterRevision(), List.of(member),
                Map.of(member.profileId(), member), Map.of(member.presentationUuid(), member),
                resolution.snapshot().loadedAtMs())).getFirst();
    }

    @Nullable
    private ItemStack refreshRosterProjection(Player player, String toolId,
                                              TwCommandItemConfig config) {
        if (player == null || config == null || !config.usesOwnerCommandFamilyRoster()) {
            return toolInventoryService.findToolStack(player, toolId);
        }
        CommandRosterActionAuthority.Resolution resolution = resolveRoster(player, config, toolId);
        if (resolution.snapshot() == null) return null;
        List<LinkedNpcRecord> records = rosterActionAuthority.project(resolution.snapshot());
        toolInventoryService.mutateToolStack(player, toolId,
                stack -> linkMutationService.writeLinkedNpcRecords(stack, records));
        return toolInventoryService.findToolStack(player, toolId);
    }

    private CommandSelectionResult cycleSelectedCommand(TwCommandItemConfig config, ItemStack stack) {
        if (config == null || stack == null) {
            return CommandSelectionResult.none(stack);
        }
        String selectedId = stack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_SELECTED_ID, Codec.STRING);
        CommandEntry next = config.findNextCommand(selectedId);
        if (next == null || next.getId() == null || next.getId().isBlank()) {
            return CommandSelectionResult.none(stack);
        }
        boolean changed = !commandIdEquals(next.getId(), selectedId);
        if (!changed) {
            return new CommandSelectionResult(stack, next, false);
        }
        ItemStack updated = stack.withMetadata(TameworkMetadataKeys.COMMAND_SELECTED_ID, Codec.STRING, next.getId());
        return new CommandSelectionResult(updated, next, true);
    }

    private boolean commandIdEquals(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) {
            return false;
        }
        return left.trim().equalsIgnoreCase(right.trim());
    }

    private String resolveCommandLabel(Player player, CommandEntry command) {
        if (command == null) {
            return LocalizedText.resolve(player, "tamework.ui.notifications.command.unknown");
        }
        String fallback = command.getId() != null && !command.getId().isBlank()
                ? command.getId()
                : LocalizedText.resolve(player, "tamework.ui.notifications.command.unknown");
        String language = player != null && player.getPlayerRef() != null
                ? player.getPlayerRef().getLanguage()
                : null;
        return LocalizedText.resolveConfigValue(language, command.getDisplayName(), fallback);
    }

    private StepResult executeCommand(Context context, Candidate candidate) {
        relocationDispatchService.maybeRelocateLoadedRecallCandidate(context, candidate);
        return stepExecutionService.executeCommand(context, candidate);
    }

    private Vector3d readStoredHomePosition(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        TameworkCommandLinksComponent links = store.getComponent(npcRef, TameworkCommandLinksComponent.getComponentType());
        if (links == null || !links.hasHome()) {
            return null;
        }
        return links.getHomePosition();
    }
    private boolean isCooldownActive(ItemStack stack, int cooldownMs) {
        if (cooldownMs <= 0) {
            return false;
        }
        Long until = stack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_COOLDOWN_UNTIL, Codec.LONG);
        return until != null && until > System.currentTimeMillis();
    }

    private double resolvePositiveDouble(double configured, double fallback) {
        return configured > 0.0 ? configured : fallback;
    }

    private double resolveFiniteDouble(double configured, double fallback) {
        return Double.isFinite(configured) ? configured : fallback;
    }

    private long resolvePositiveLong(long configured, long fallback) {
        return configured > 0L ? configured : fallback;
    }

    private boolean updateHeldItem(Player player, ItemStack updated) {
        return toolInventoryService.updateHeldItem(player, updated);
    }

    private void handleDeferredLink(Player player,
                                    Store<EntityStore> store,
                                    Ref<EntityStore> targetRef,
                                    String toolId,
                                    TwCommandItemConfig config) {
        if (config != null && config.usesOwnerCommandFamilyRoster()) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.link.unavailable");
            return;
        }
        LinkToggleResult[] resultHolder = new LinkToggleResult[1];
        boolean mutated = toolInventoryService.mutateToolStack(player, toolId, stack -> {
            LinkToggleResult result = linkMutationService.tryToggleLink(
                    player,
                    store,
                    targetRef,
                    toolId,
                    config,
                    stack,
                    null
            );
            resultHolder[0] = result;
            return result != null && result.updatedItem != null ? result.updatedItem : stack;
        });
        LinkToggleResult result = resultHolder[0];
        if (!mutated || result == null || !result.toggled || result.updatedItem == null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.link.failed");
            return;
        }
        if (result.linked && !result.active) {
            feedbackService.showSuccessKey(
                    player,
                    "tamework.ui.notifications.command.link.successInactive",
                    result.npcName
            );
            return;
        }
        feedbackService.showSuccessKey(
                player,
                result.linked
                        ? "tamework.ui.notifications.command.link.success"
                        : "tamework.ui.notifications.command.link.unlinked",
                result.npcName
        );
    }

    private record LoadedCompanionTalentContext(@Nonnull Ref<EntityStore> npcRef,
                                                @Nonnull Store<EntityStore> store,
                                                @Nonnull NPCEntity npc,
                                                @Nonnull String displayName,
                                                @Nullable String roleId) {
    }

    private record LinkedRecordCandidate(UUID npcUuid,
                                         Vector3d lastKnownPosition,
                                         String lastKnownWorldName,
                                         Vector3d homePosition,
                                         String cachedDisplayName,
                                         String cachedNameKey,
                                         String cachedRoleId,
                                         String cachedCommandState) {
        private LinkedNpcRecord toRecord(LinkedNpcRecord previous) {
            if (previous == null) {
                return new LinkedNpcRecord(
                        npcUuid,
                        lastKnownPosition,
                        lastKnownWorldName,
                        homePosition,
                        cachedDisplayName,
                        cachedNameKey,
                        cachedRoleId,
                        cachedCommandState,
                        true,
                        false,
                        null
                );
            }
            return new LinkedNpcRecord(
                    npcUuid,
                    previous.profileId,
                    lastKnownPosition != null ? lastKnownPosition : previous.lastKnownPosition,
                    firstNonBlank(lastKnownWorldName, previous.lastKnownWorldName),
                    homePosition != null ? homePosition : previous.homePosition,
                    firstNonBlank(cachedDisplayName, previous.cachedDisplayName),
                    firstNonBlank(cachedNameKey, previous.cachedNameKey),
                    firstNonBlank(cachedRoleId, previous.cachedRoleId),
                    firstNonBlank(cachedCommandState, previous.cachedCommandState),
                    previous.active,
                    previous.breedingEnabled,
                    previous.groupId
            );
        }

        private static String firstNonBlank(String first, String second) {
            if (first != null && !first.isBlank()) {
                return first;
            }
            if (second != null && !second.isBlank()) {
                return second;
            }
            return null;
        }
    }

}

