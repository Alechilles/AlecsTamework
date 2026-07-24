package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
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
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkHookComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.npc.progression.CompanionTalentService;
import com.alechilles.alecstamework.items.persistence.FreeCompanionRestorationAuthor;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDomainFacades;
import com.alechilles.alecstamework.ui.TameworkCompanionTalentsPage;
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
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import it.unimi.dsi.fastutil.Pair;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
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

    private final CommandItemRegistry registry;
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
    @Nullable
    private final CommandCompanionRestorationService restorationService;
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
    private final CommandSelectionPageService selectionPageService;
    private final CommandItemUseOrchestrator itemUseOrchestrator;

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
                                     @Nullable PersistenceDomainFacades persistence,
                                     @Nullable FreeCompanionRestorationAuthor restorationAuthor) {
        this.registry = registry;
        this.relocationService = relocationService;
        this.deathService = deathService;
        this.captureService = captureService;
        this.coopService = coopService;
        this.lostService = lostService;
        this.stateSnapshotService = stateSnapshotService;
        this.linkedNpcRecordStore = new CommandLinkedNpcRecordStore();
        this.groupService = new CommandGroupService();
        this.feedbackService = new CommandFeedbackService(new TameworkUiMessageService());
        this.npcNameResolver = new CommandNpcNameResolver();
        this.linkPolicyService = new CommandLinkPolicyService();
        this.npcExistenceService = stateSnapshotService != null
                ? new CommandNpcExistenceService(stateSnapshotService.getLoadedNpcIdentityIndex())
                : new CommandNpcExistenceService();
        CommandPersistenceView persistenceView = persistence != null
                ? new CommandPersistenceView(persistence)
                : null;
        CommandNpcIdentityService npcIdentityService = persistenceView != null
                ? new CommandNpcIdentityService(
                        persistenceView, npcExistenceService)
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
                persistenceView,
                linkPolicyService,
                this.groupService,
                profileActionResolver
        );
        this.resolutionService = new CommandResolutionService(registry, DEFAULT_RAYCAST_DISTANCE);
        this.panelPreferenceService = new CommandPanelPreferenceService();
        this.panelEntrySourceService = new CommandPanelEntrySourceService(
                panelEntryService,
                panelPreferenceService,
                linkPolicyService,
                npcNameResolver
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
                companionPlacementService
        );
        this.restorationService =
                persistenceView != null && restorationAuthor != null
                ? new CommandCompanionRestorationService(
                        companionPlacementService,
                        persistenceView,
                        restorationAuthor
                )
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
                this.groupService
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
        this.selectionPageService = new CommandSelectionPageService(
                toolInventoryService,
                groupAssignPageService,
                resolutionService,
                panelActionService,
                talentPageService
        );
        this.itemUseOrchestrator = new CommandItemUseOrchestrator(
                resolutionService,
                toolInventoryService,
                linkMutationService,
                feedbackService,
                recipientService,
                canonicalRecordCommitGate,
                relocationDispatchService,
                stepExecutionService,
                this::reconcileStaleLinkedNpcRecords,
                this::openSelectionMenu,
                this::handleDeferredLink,
                this::resolveCommandLabel,
                new CommandItemUseOrchestrator.CommandTuning(
                        HYBRID_TELEPORT_DISTANCE_THRESHOLD,
                        HYBRID_PATH_DISTANCE_BEFORE_TELEPORT,
                        HYBRID_TELEPORT_DELAY_MS,
                        RECALL_SAFE_SPAWN_DISTANCE,
                        RECALL_FORCE_RELOCATE_DISTANCE
                )
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
            List<LinkedNpcRecord> linkedRecords =
                    linkMutationService.readLinkedNpcRecords(stack);
            if (linkedRecords.isEmpty()) {
                continue;
            }
            if (profileActionResolver != null) {
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
        return itemUseOrchestrator.handleUse(
                player, itemStack, targetRef, configIdOverride, commandIdOverride
        );
    }

    private boolean openSelectionMenu(Player player,
                                      Store<EntityStore> store,
                                      TwCommandItemConfig config,
                                      ItemStack working,
                                      String toolId) {
        CommandSelectionPageService.Actions actions = new CommandSelectionPageService.Actions(
                npcUuid -> applyMenuUnlink(player, toolId, config, npcUuid),
                npcUuid -> applyMenuRelease(player, toolId, config, npcUuid),
                npcUuid -> applyMenuCull(player, toolId, config, npcUuid),
                npcUuid -> applyMenuRespawn(player, toolId, config, npcUuid),
                npcUuid -> applyMenuLocate(player, toolId, config, npcUuid),
                npcUuid -> applyMenuRecall(player, toolId, config, npcUuid),
                npcUuid -> applyMenuSetHome(player, toolId, config, npcUuid),
                npcUuid -> applyMenuReturnHome(player, toolId, config, npcUuid),
                () -> openGroupManagerFromSelection(player, config, toolId),
                () -> reopenSelectionMenu(player, config, toolId),
                commandId -> applyMenuSelection(player, toolId, config, commandId)
        );
        return selectionPageService.open(player, store, config, working, toolId, actions);
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
        ownerReleaseService.release(
                player, toolId, config, presentationUuid
        );
    }

    private void applyMenuCull(Player player,
                               String toolId,
                               TwCommandItemConfig config,
                               UUID presentationUuid) {
        ownerCullService.cull(
                player, toolId, config, presentationUuid
        );
    }

    private void applyMenuRespawn(Player player,
                                  String toolId,
                                  TwCommandItemConfig commandConfig,
                                  UUID npcUuid) {
        if (player == null || toolId == null || toolId.isBlank() || npcUuid == null) {
            return;
        }
        if (restorationService == null) {
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
            TwCompanionConfig.EffectiveSettings companionSettings =
                    TwCompanionConfig.resolveEffectiveForRole(
                            record.cachedRoleId
                    );
            double safeSpawnDistance = resolvePositiveDouble(
                    companionSettings.getRecallSafeSpawnDistance(),
                    RECALL_SAFE_SPAWN_DISTANCE
            );
            CommandCompanionRestorationService.RequestStatus status =
                    restorationService.request(
                            player,
                            playerRef,
                            store,
                            toolId,
                            record,
                            safeSpawnDistance
                    );
            if (status
                    == CommandCompanionRestorationService.RequestStatus
                    .NOT_DORMANT) {
                feedbackService.showWarningKey(
                        player,
                        "tamework.ui.notifications.command.respawn.notDeadOrLost"
                );
            } else if (status
                    != CommandCompanionRestorationService.RequestStatus
                    .STARTED) {
                feedbackService.showWarningKey(
                        player,
                        "tamework.ui.notifications.command.respawn.unavailable"
                );
            }
            return;
        }
        feedbackService.showWarningKey(player, "tamework.ui.notifications.command.shared.itemNotFound");
    }

    void canonicalizePlayerCommandInventory(@Nullable Holder<EntityStore> holder) {
        if (holder != null) {
            inventoryRepairService.canonicalize(holder);
        }
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

    private ItemStack findCommandToolStack(Player player, String toolId) {
        return toolInventoryService.findToolStack(player, toolId);
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
    private double resolvePositiveDouble(double configured, double fallback) {
        return configured > 0.0 ? configured : fallback;
    }

    private double resolveFiniteDouble(double configured, double fallback) {
        return Double.isFinite(configured) ? configured : fallback;
    }

    private void handleDeferredLink(Player player,
                                    Store<EntityStore> store,
                                    Ref<EntityStore> targetRef,
                                    String toolId,
                                    TwCommandItemConfig config) {
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

