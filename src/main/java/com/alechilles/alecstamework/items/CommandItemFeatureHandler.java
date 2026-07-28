package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.BondedCompanionApi;
import com.alechilles.alecstamework.api.CommandTimedSummoningApi;
import com.alechilles.alecstamework.api.PaidCommandRevivalApi;
import com.alechilles.alecstamework.api.PopulationGroupApi;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
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
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.items.persistence.FreeCompanionRestorationAuthor;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDomainFacades;
import com.alechilles.alecstamework.ui.TameworkUiMessageService;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.List;
import java.util.UUID;
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
    private final CommandLinkedNpcStateSnapshotService stateSnapshotService;
    private final CommandLinkedNpcRecordStore linkedNpcRecordStore;
    private final CommandGroupService groupService;
    private final CommandFeedbackService feedbackService;
    private final CommandNpcNameResolver npcNameResolver;
    private final CommandLinkedPanelEntryService panelEntryService;
    private final CommandPanelEntrySourceService panelEntrySourceService;
    private final BondedCompanionPanelLifecycle bondedPanelLifecycle;
    private final CommandToolInventoryService toolInventoryService;
    private final CommandPanelCallbackAuthority callbackAuthority;
    private final CommandResolutionService resolutionService;
    private final CommandLinkPolicyService linkPolicyService;
    private final CommandCompanionPlacementService companionPlacementService;
    private final CommandRecipientService recipientService;
    private final CommandStepExecutionService stepExecutionService;
    private final CommandLinkMutationService linkMutationService;
    private final CommandNpcExistenceService npcExistenceService;
    private final CommandCanonicalRecordCommitGate canonicalRecordCommitGate;
    private final CommandLinkedNpcInventoryRepairService inventoryRepairService;
    private final CommandPlayerInventoryCanonicalizer inventoryCanonicalizer;
    @Nullable
    private final CommandNpcProfileActionResolver profileActionResolver;
    private final CommandLinkedRecordCanonicalizer recordCanonicalizer;
    private final CommandRelocationDispatchService relocationDispatchService;
    private final CommandFreeRestorationActionService freeRestorationActions;
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
    private final CommandWorldChangeTravelCoordinator worldChangeTravel;

    public CommandItemFeatureHandler(CommandItemRegistry registry,
                                     CommandNpcRelocationService relocationService,
                                     CommandLinkedNpcStateSnapshotService stateSnapshotService) {
        this(registry, relocationService, stateSnapshotService, null, null);
    }

    public CommandItemFeatureHandler(CommandItemRegistry registry,
                                     CommandNpcRelocationService relocationService,
                                     CommandLinkedNpcStateSnapshotService stateSnapshotService,
                                     @Nullable PersistenceDomainFacades persistence,
                                     @Nullable FreeCompanionRestorationAuthor restorationAuthor) {
        this(
                registry,
                relocationService,
                stateSnapshotService,
                persistence,
                restorationAuthor,
                (CommandTimedSummoningApi) null,
                (PaidCommandRevivalApi) null,
                (PopulationGroupApi) null
        );
    }

    public CommandItemFeatureHandler(
            CommandItemRegistry registry,
            CommandNpcRelocationService relocationService,
            CommandLinkedNpcStateSnapshotService stateSnapshotService,
            @Nullable PersistenceDomainFacades persistence,
            @Nullable FreeCompanionRestorationAuthor restorationAuthor,
            @Nullable CommandTimedSummoningApi timedSummoning,
            @Nullable PaidCommandRevivalApi paidRevival,
            @Nullable PopulationGroupApi populationGroups
    ) {
        this(
                registry,
                relocationService,
                stateSnapshotService,
                persistence,
                restorationAuthor,
                constant(timedSummoning),
                constant(paidRevival),
                constant(populationGroups)
        );
    }

    public CommandItemFeatureHandler(
            CommandItemRegistry registry,
            CommandNpcRelocationService relocationService,
            CommandLinkedNpcStateSnapshotService stateSnapshotService,
            @Nullable PersistenceDomainFacades persistence,
            @Nullable FreeCompanionRestorationAuthor restorationAuthor,
            @Nullable Supplier<CommandTimedSummoningApi> timedSummoning,
            @Nullable Supplier<PaidCommandRevivalApi> paidRevival,
            @Nullable Supplier<PopulationGroupApi> populationGroups
    ) {
        this(
                registry, relocationService, stateSnapshotService, persistence,
                restorationAuthor, timedSummoning, paidRevival,
                populationGroups, null
        );
    }

    public CommandItemFeatureHandler(
            CommandItemRegistry registry,
            CommandNpcRelocationService relocationService,
            CommandLinkedNpcStateSnapshotService stateSnapshotService,
            @Nullable PersistenceDomainFacades persistence,
            @Nullable FreeCompanionRestorationAuthor restorationAuthor,
            @Nullable Supplier<CommandTimedSummoningApi> timedSummoning,
            @Nullable Supplier<PaidCommandRevivalApi> paidRevival,
            @Nullable Supplier<PopulationGroupApi> populationGroups,
            @Nullable Supplier<BondedCompanionApi> bondedCompanions
    ) {
        this.registry = registry;
        this.relocationService = relocationService;
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
        CommandRosterPanelRecordSource rosterPanelRecordSource =
                persistence != null
                        ? new CommandRosterPanelRecordSource(
                                persistence.queries()
                        )
                        : null;
        CommandPanelFeaturePresentationSource featurePresentations =
                rosterPanelRecordSource != null
                        && timedSummoning != null
                        && paidRevival != null
                        && populationGroups != null
                        ? new CommandPanelFeaturePresentationSource(
                                rosterPanelRecordSource,
                                timedSummoning,
                                paidRevival,
                                populationGroups,
                                System::currentTimeMillis
                        )
                        : null;
        CommandNpcIdentityService npcIdentityService = persistenceView != null
                ? new CommandNpcIdentityService(
                        persistenceView, npcExistenceService)
                : null;
        this.profileActionResolver = npcIdentityService != null
                ? new CommandNpcProfileActionResolver(npcIdentityService)
                : null;
        this.recordCanonicalizer = new CommandLinkedRecordCanonicalizer(
                linkedNpcRecordStore, profileActionResolver);
        this.panelEntryService = new CommandLinkedPanelEntryService(
                linkedNpcRecordStore,
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
                npcNameResolver,
                rosterPanelRecordSource,
                featurePresentations,
                BondedCompanionPanelEntrySourceService.production(bondedCompanions)
        );
        this.bondedPanelLifecycle = new BondedCompanionPanelLifecycle(
                registry, panelEntrySourceService.bondedReadModel());
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
        this.callbackAuthority = new CommandPanelCallbackAuthority(
                registry, toolInventoryService);
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
        this.worldChangeTravel = new CommandWorldChangeTravelCoordinator(
                relocationService,
                resolutionService,
                linkMutationService,
                canonicalRecordCommitGate,
                companionPlacementService,
                profileActionResolver,
                RECALL_SAFE_SPAWN_DISTANCE
        );
        this.inventoryRepairService =
                new CommandLinkedNpcInventoryRepairService(registry, profileActionResolver);
        this.inventoryCanonicalizer = new CommandPlayerInventoryCanonicalizer(
                inventoryRepairService);
        this.recipientService = new CommandRecipientService(
                linkPolicyService,
                linkedNpcRecordStore,
                panelPreferenceService,
                profileActionResolver,
                BondedCompanionCommandRecipientSource.production(
                        bondedCompanions, linkPolicyService)
        );
        this.relocationDispatchService = new CommandRelocationDispatchService(
                relocationService,
                resolutionService,
                stepExecutionService,
                companionPlacementService
        );
        CommandCompanionRestorationService restorationService =
                persistenceView != null && restorationAuthor != null
                ? new CommandCompanionRestorationService(
                        companionPlacementService,
                        persistenceView,
                        restorationAuthor
                )
                : null;
        this.freeRestorationActions =
                new CommandFreeRestorationActionService(
                        restorationService,
                        linkMutationService,
                        feedbackService,
                        RECALL_SAFE_SPAWN_DISTANCE
                );
        this.ownerReleaseService = new CommandOwnerReleaseService(
                linkPolicyService,
                stepExecutionService,
                feedbackService,
                npcNameResolver
        );
        this.ownerCullService = new CommandOwnerCullService(
                linkPolicyService,
                registry,
                linkMutationService,
                feedbackService,
                npcNameResolver
        );
        this.menuMoveService = new CommandMenuMoveService(
                resolutionService,
                linkMutationService,
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
        CommandDeferredLinkService deferredLinks =
                new CommandDeferredLinkService(
                        toolInventoryService,
                        linkMutationService,
                        feedbackService
                );
        CommandPanelFeatureActionService featureActions =
                featurePresentations == null
                        ? null
                        : new CommandPanelFeatureActionService(
                                featurePresentations,
                                timedSummoning,
                                paidRevival,
                                feedbackService
                        );
        this.selectionPageService = new CommandSelectionPageService(
                toolInventoryService,
                groupAssignPageService,
                resolutionService,
                panelActionService,
                talentPageService,
                featurePresentations,
                featureActions,
                BondedCompanionPanelActionRouter.production(
                        feedbackService, bondedCompanions,
                        panelEntrySourceService.bondedReadModel())
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
                recordCanonicalizer::canonicalize,
                this::openSelectionMenu,
                deferredLinks::handle,
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
        worldChangeTravel.queueForPlayerUuid(destinationWorld, playerUuid);
    }
    /** Resolves the live player before repairing their command-item copies. */
    public void canonicalizePlayerCommandInventory(@Nullable World world, @Nullable UUID playerUuid) {
        inventoryCanonicalizer.canonicalize(world, playerUuid);
    }
    void dismountPlayerAfterWorldJoin(World world, UUID playerUuid) {
        worldChangeTravel.dismountAfterWorldJoin(world, playerUuid);
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
    /** Clears only presentation snapshots when the owner disconnects. */
    public void onPlayerDisconnect(@Nullable UUID ownerUuid) {
        bondedPanelLifecycle.evictOwner(ownerUuid);
    }
    /** Schedules all configured bonded rosters before the owner opens a Horn. */
    public void onPlayerConnect(@Nullable UUID ownerUuid) {
        bondedPanelLifecycle.warmForOwner(ownerUuid);
    }
    /** Stops the owned bonded panel loader before durable persistence closes. */
    public void close() {
        bondedPanelLifecycle.close();
    }
    private boolean openSelectionMenu(Player player,
                                      Store<EntityStore> store,
                                      TwCommandItemConfig config,
                                      ItemStack working,
                                      String toolId) {
        if (player != null && config != null
                && config.usesBondedCompanionRoster()) {
            bondedPanelLifecycle.warm(
                    player.getUuid(), config.getBondedRosterId());
        }
        CommandPanelCallbackAuthority.GenericBinding genericBinding =
                callbackAuthority.bindGeneric(working, config);
        if (CommandRosterStorageBoundary.allowsGenericRosterActions(config)
                && genericBinding == null) {
            return false;
        }
        long openedRegistryRevision = callbackAuthority.revision();
        CommandSelectionPageService.Actions actions = new CommandSelectionPageService.Actions(
                npcUuid -> applyMenuUnlink(player, toolId, config, npcUuid),
                npcUuid -> applyMenuRelease(player, toolId, config, npcUuid),
                npcUuid -> applyMenuCull(player, toolId, config, npcUuid),
                npcUuid -> applyMenuRespawn(player, toolId, config, npcUuid),
                npcUuid -> applyMenuLocate(player, toolId, config, npcUuid),
                npcUuid -> applyMenuRecall(player, toolId, config, npcUuid),
                npcUuid -> applyMenuSetHome(player, toolId, config, npcUuid),
                npcUuid -> applyMenuReturnHome(player, toolId, config, npcUuid),
                () -> openGroupManagerFromSelection(
                        player, config, toolId, genericBinding),
                () -> reopenSelectionMenu(
                        player, config, toolId, genericBinding),
                commandId -> applyMenuSelection(player, toolId, config, commandId)
        );
        return selectionPageService.open(
                player, store, config, working, toolId, actions,
                () -> callbackAuthority.allowsGeneric(
                        player, toolId, genericBinding),
                currentPlayer -> callbackAuthority.allowsBonded(
                        currentPlayer, toolId,
                        working == null ? null : working.getItemId(),
                        config, openedRegistryRevision)
        );
    }

    private void openGroupManagerFromSelection(Player player,
                                               TwCommandItemConfig config,
                                               String toolId,
                                               CommandPanelCallbackAuthority
                                                       .GenericBinding binding) {
        if (!callbackAuthority.allowsGeneric(
                player, toolId, binding)) {
            return;
        }
        groupManagerPageService.openGroupManagerPage(
                player,
                toolId,
                () -> reopenSelectionMenu(
                        player, config, toolId, binding),
                () -> callbackAuthority.allowsGeneric(
                        player, toolId, binding)
        );
    }

    private void reopenSelectionMenu(Player player,
                                     TwCommandItemConfig config,
                                     String toolId,
                                     CommandPanelCallbackAuthority
                                             .GenericBinding binding) {
        if (!callbackAuthority.allowsGeneric(
                player, toolId, binding)) {
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
        ItemStack toolStack = toolInventoryService.findUniqueToolStack(
                player, toolId);
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
        if (!callbackAuthority.allowsGeneric(player, toolId, config)
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
        if (!callbackAuthority.allowsGeneric(player, toolId, config)
                || player == null || toolId == null || toolId.isBlank()
                || npcUuid == null) {
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
        if (!callbackAuthority.allowsGeneric(player, toolId, config)) {
            return;
        }
        ownerReleaseService.release(
                player, toolId, config, presentationUuid
        );
    }

    private void applyMenuCull(Player player,
                               String toolId,
                               TwCommandItemConfig config,
                               UUID presentationUuid) {
        if (!callbackAuthority.allowsGeneric(player, toolId, config)) {
            return;
        }
        ownerCullService.cull(
                player, toolId, config, presentationUuid
        );
    }

    private void applyMenuRespawn(Player player,
                                  String toolId,
                                  TwCommandItemConfig config,
                                  UUID npcUuid) {
        if (!callbackAuthority.allowsGeneric(player, toolId, config)) {
            return;
        }
        freeRestorationActions.request(player, toolId, npcUuid);
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
        if (!callbackAuthority.allowsGeneric(player, toolId, config)
                || player == null || toolId == null || toolId.isBlank()
                || npcUuid == null) {
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
        if (!callbackAuthority.allowsGeneric(player, toolId, config)) {
            return;
        }
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
        if (!callbackAuthority.allowsGeneric(player, toolId, config)) {
            return;
        }
        menuMoveService.applyMenuMoveCommand(
                player,
                toolId,
                npcUuid,
                returnHome,
                cmdEntry -> resolveCommandLabel(player, cmdEntry)
        );
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
    private double resolveFiniteDouble(double configured, double fallback) {
        return Double.isFinite(configured) ? configured : fallback;
    }

    @Nullable
    private static <T> Supplier<T> constant(@Nullable T value) {
        return value == null ? null : () -> value;
    }

}

