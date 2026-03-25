package com.alechilles.alecstamework.items;

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
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkHookComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.ui.TameworkCommandSelectionPage;
import com.alechilles.alecstamework.ui.TameworkUiMessageService;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
    private static final float RELEASE_DESPAWN_DELAY_SECONDS = 4.0F;
    private static final float CULL_DAMAGE_AMOUNT = 2.1474836E9F;
    private static final String[] RELEASE_STATE_CANDIDATES = new String[] { "Flee", "Wander", "Idle" };

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
    private final CommandRelocationDispatchService relocationDispatchService;
    private final CommandRespawnService respawnService;
    private final CommandLostRecoveryService lostRecoveryService;
    private final CommandMenuMoveService menuMoveService;
    private final CommandPanelPreferenceService panelPreferenceService;
    private final CommandPanelActionService panelActionService;
    private final CommandGroupManagerPageService groupManagerPageService;
    private final CommandGroupAssignPageService groupAssignPageService;

    public CommandItemFeatureHandler(CommandItemRegistry registry,
                                     CommandNpcRelocationService relocationService,
                                     CommandLinkedNpcDeathService deathService,
                                     CommandLinkedNpcCaptureService captureService,
                                     CommandLinkedNpcCoopService coopService,
                                     CommandLinkedNpcLostService lostService,
                                     CommandLinkedNpcStateSnapshotService stateSnapshotService) {
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
        this.panelEntryService = new CommandLinkedPanelEntryService(
                linkedNpcRecordStore,
                deathService,
                captureService,
                coopService,
                lostService,
                npcNameResolver,
                linkPolicyService,
                this.groupService
        );
        this.resolutionService = new CommandResolutionService(registry, DEFAULT_RAYCAST_DISTANCE);
        this.panelPreferenceService = new CommandPanelPreferenceService();
        this.panelEntrySourceService = new CommandPanelEntrySourceService(
                panelEntryService,
                panelPreferenceService,
                linkPolicyService,
                npcNameResolver
        );
        this.toolInventoryService = new CommandToolInventoryService(
                panelEntryService,
                panelEntrySourceService,
                panelPreferenceService,
                this.groupService
        );
        this.companionPlacementService = new CommandCompanionPlacementService();
        this.recipientService = new CommandRecipientService(
                linkPolicyService,
                linkedNpcRecordStore,
                panelPreferenceService
        );
        this.stepExecutionService = new CommandStepExecutionService(
                relocationService,
                linkedNpcRecordStore,
                npcNameResolver
        );
        this.linkMutationService = new CommandLinkMutationService(
                linkedNpcRecordStore,
                linkPolicyService,
                npcNameResolver,
                stateSnapshotService
        );
        this.npcExistenceService = new CommandNpcExistenceService();
        this.relocationDispatchService = new CommandRelocationDispatchService(
                relocationService,
                deathService,
                captureService,
                coopService,
                resolutionService,
                stepExecutionService,
                companionPlacementService
        );
        this.respawnService = new CommandRespawnService(
                companionPlacementService,
                linkPolicyService,
                linkMutationService,
                npcNameResolver,
                deathService,
                stepExecutionService
        );
        this.lostRecoveryService = new CommandLostRecoveryService(
                companionPlacementService,
                npcExistenceService,
                linkPolicyService,
                linkMutationService,
                npcNameResolver,
                respawnService,
                stepExecutionService,
                lostService
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
                RECALL_FORCE_RELOCATE_DISTANCE
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
        this.groupAssignPageService = new CommandGroupAssignPageService(
                panelActionService,
                toolInventoryService
        );
    }

    public void onAddPlayerToWorld(AddPlayerToWorldEvent event) {
        if (event == null || event.getWorld() == null || event.getHolder() == null || relocationService == null) {
            return;
        }
        Player player = event.getHolder().getComponent(Player.getComponentType());
        if (player == null) {
            return;
        }
        World world = event.getWorld();
        CompletableFuture.runAsync(
                () -> world.execute(() -> queueWorldChangeTravelRelocations(player, world)),
                CompletableFuture.delayedExecutor(250L, TimeUnit.MILLISECONDS)
        );
    }

    public void queueWorldChangeTravelRelocationsForPlayerUuid(World destinationWorld, UUID playerUuid) {
        if (destinationWorld == null || playerUuid == null || relocationService == null) {
            return;
        }
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
            List<LinkedNpcRecord> linkedRecords = linkMutationService.readLinkedNpcRecords(stack);
            if (linkedRecords.isEmpty()) {
                continue;
            }
            for (LinkedNpcRecord record : linkedRecords) {
                if (record == null || record.npcUuid == null || !record.active || queuedNpcUuids.contains(record.npcUuid)) {
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
                if (!isEligibleForWorldChangeTravel(record, settings)) {
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
                        null
                );
                queuedNpcUuids.add(record.npcUuid);
            }
        }
    }

    private boolean isEligibleForWorldChangeTravel(LinkedNpcRecord record,
                                                   TwCompanionConfig.EffectiveSettings settings) {
        if (record == null || settings == null) {
            return false;
        }
        String[] requiredStates = settings.getFollowMasterOnWorldChangeStateFilter();
        if (requiredStates == null || requiredStates.length == 0) {
            return true;
        }
        if (record.cachedCommandState == null || record.cachedCommandState.isBlank()) {
            // Preserve backward compatibility for older linked-record metadata that does not cache state yet.
            return true;
        }
        return settings.isWorldChangeStateAllowed(record.cachedCommandState);
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

        if (isOpenSelectionMenuCommand(commandIdOverride)) {
            if (updateHeldItem) {
                updateHeldItem(player, working);
            }
            boolean opened = openSelectionMenu(player, store, config, working, tool.toolId);
            if (!opened) {
                feedbackService.showWarning(player, "No command choices are available for this item.");
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
                feedbackService.showWarning(player, "No command is configured for this item.");
                return false;
            }
            String label = resolveCommandLabel(selection.command);
            feedbackService.showDefault(player, "Selected: " + label);
            return true;
        }

        if (targetRef != null && config.isLinkEnabled() && config.isLinkUseTogglesMembership()) {
            LinkToggleResult link = linkMutationService.tryToggleLink(player, store, targetRef, tool.toolId, config, working);
            if (link.toggled) {
                if (link.updatedItem != null) {
                    working = link.updatedItem;
                    updateHeldItem = true;
                }
                if (updateHeldItem) {
                    updateHeldItem(player, working);
                }
                if (link.linked && !link.active) {
                    feedbackService.showSuccess(player, "Linked " + link.npcName + " as inactive.");
                } else {
                    feedbackService.showSuccess(player, (link.linked ? "Linked " : "Unlinked ") + link.npcName + ".");
                }
                return true;
            }
        }

        int cooldownMs = Math.max(0, config.getCooldownSeconds()) * 1000;
        if (isCooldownActive(working, cooldownMs)) {
            if (updateHeldItem) {
                updateHeldItem(player, working);
            }
            feedbackService.showWarning(player, "That command item is on cooldown.");
            return false;
        }

        CommandEntry command = resolutionService.resolveCommand(config, commandIdOverride, working);
        if (command == null) {
            if (updateHeldItem) {
                updateHeldItem(player, working);
            }
            feedbackService.showWarning(player, "No command is configured for this item.");
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
                defaultCompanionSettings.isBlockAllPlayerDamageIfOwned(),
                defaultCompanionSettings.isInvulnerableIfOwned(),
                returnHomeTeleportDistance,
                returnHomePathDistanceBeforeTeleport,
                returnHomeTeleportDelayMs,
                recallSafeSpawnDistance,
                recallForceRelocateDistance
        );

        List<Candidate> recipients = recipientService.queryRecipients(context);
        List<LinkedNpcRecord> unloadedLinked = recipientService.queryUnloadedLinkedRecords(context, recipients);
        if (recipients.isEmpty() && unloadedLinked.isEmpty()) {
            if (updateHeldItem) {
                updateHeldItem(player, working);
            }
            feedbackService.showWarning(player, "No companions matched this command.");
            return false;
        }

        int affected = 0;
        if (!recipients.isEmpty()) {
            for (Candidate candidate : recipients) {
                StepResult stepResult = executeCommand(context, candidate);
                if (stepResult.applied) {
                    affected++;
                }
                if (stepResult.abortAll) {
                    break;
                }
            }
        }
        ItemStack refreshedLinks = linkMutationService.refreshLinkedNpcPositions(context.workingItem, recipients, store);
        if (refreshedLinks != context.workingItem) {
            context.workingItem = refreshedLinks;
            context.itemChanged = true;
            working = refreshedLinks;
            updateHeldItem = true;
        }
        int queued = relocationDispatchService.queueRelocationsForUnloaded(context, unloadedLinked);
        if (context.workingItem != working) {
            working = context.workingItem;
            updateHeldItem = true;
        }
        if (affected <= 0 && queued <= 0) {
            if (updateHeldItem) {
                updateHeldItem(player, working);
            }
            feedbackService.showWarning(player, "No NPCs could execute that command.");
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
        feedbackService.emitCommandExecutionFeedback(context.player, context.playerRef, context.store, context.command, affected, queued, this::resolveCommandLabel);
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
        TameworkCommandSelectionPage page = new TameworkCommandSelectionPage(
                uiPlayerRef,
                config,
                selectedId,
                requireUnlinkConfirm,
                () -> toolInventoryService.buildLinkedPanelEntriesForTool(player, toolId, config),
                () -> toolInventoryService.resolvePanelModeValueForTool(player, toolId, config),
                () -> toolInventoryService.resolvePanelRadiusLabelForTool(player, toolId, config),
                () -> toolInventoryService.resolvePanelSortValueForTool(player, toolId),
                () -> toolInventoryService.resolvePanelFilterModeValueForTool(player, toolId),
                () -> toolInventoryService.resolvePanelFilterInputForTool(player, toolId),
                () -> groupAssignPageService.resolveGroupDropdownEntries(player, toolId),
                npcUuid -> panelActionService.applyLink(player, toolId, config, npcUuid),
                npcUuid -> applyMenuUnlink(player, toolId, npcUuid),
                npcUuid -> panelActionService.applyToggleActive(player, toolId, config, npcUuid),
                npcUuid -> panelActionService.applyToggleBreeding(player, toolId, npcUuid),
                npcUuid -> applyMenuRelease(player, toolId, config, npcUuid),
                npcUuid -> applyMenuCull(player, toolId, config, npcUuid),
                npcUuid -> applyMenuRespawn(player, toolId, npcUuid),
                npcUuid -> applyMenuRecall(player, toolId, npcUuid),
                npcUuid -> applyMenuSetHome(player, toolId, npcUuid),
                npcUuid -> applyMenuReturnHome(player, toolId, npcUuid),
                value -> panelActionService.applySetPanelMode(player, toolId, value),
                () -> panelActionService.applyAdjustPanelRadius(player, toolId, config, false),
                () -> panelActionService.applyAdjustPanelRadius(player, toolId, config, true),
                () -> openGroupManagerFromSelection(player, config, toolId),
                value -> panelActionService.applySetSort(player, toolId, value),
                value -> panelActionService.applySetFilterMode(player, toolId, value),
                value -> panelActionService.applySetSelectedFilterText(player, toolId, value),
                () -> panelActionService.applyClearFilters(player, toolId),
                (npcUuid, groupId) -> groupAssignPageService.applyGroupAssignment(
                        player,
                        toolId,
                        config,
                        npcUuid,
                        groupId
                ),
                commandId -> applyMenuSelection(player, toolId, config, commandId)
        );
        player.getPageManager().openCustomPage(playerRef, store, page);
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
            feedbackService.showWarning(player, "Unable to reopen the command panel.");
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (store == null) {
            feedbackService.showWarning(player, "Unable to reopen the command panel.");
            return;
        }
        ItemStack toolStack = findCommandToolStack(player, toolId);
        if (toolStack == null || toolStack.isEmpty()) {
            feedbackService.showWarning(player, "Unable to find that command item.");
            return;
        }
        boolean opened = openSelectionMenu(player, store, config, toolStack, toolId);
        if (!opened) {
            feedbackService.showWarning(player, "Unable to reopen the command panel.");
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
            feedbackService.showWarning(player, "That command is no longer available.");
            return;
        }
        boolean updated = toolInventoryService.setSelectedCommandOnTool(player, toolId, selected.getId());
        if (!updated) {
            feedbackService.showWarning(player, "Unable to apply the selected command.");
            return;
        }
        String label = resolveCommandLabel(selected);
        feedbackService.showDefault(player, "Selected: " + label);
    }

    private void applyMenuUnlink(Player player,
                                 String toolId,
                                 UUID npcUuid) {
        if (player == null || toolId == null || toolId.isBlank() || npcUuid == null) {
            return;
        }
        Inventory inventory = player.getInventory();
        if (inventory == null || inventory.getHotbar() == null) {
            feedbackService.showWarning(player, "Unable to unlink right now.");
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
                feedbackService.showWarning(player, "That NPC is not linked to this tool.");
            } else {
                hotbar.setItemStackForSlot(slot, updatedStack);
                inventory.markChanged();
                player.sendInventory();
                feedbackService.showSuccess(player, "Removed linked NPC.");
            }
            return;
        }
        feedbackService.showWarning(player, "Unable to find that command item.");
    }

    private void applyMenuRelease(Player player,
                                  String toolId,
                                  TwCommandItemConfig config,
                                  UUID npcUuid) {
        if (player == null || toolId == null || toolId.isBlank() || npcUuid == null) {
            return;
        }
        World world = player.getWorld();
        if (world == null) {
            feedbackService.showWarning(player, "Unable to release right now.");
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (store == null) {
            feedbackService.showWarning(player, "Unable to release right now.");
            return;
        }
        Ref<EntityStore> npcRef = world.getEntityRef(npcUuid);
        if (npcRef == null || !npcRef.isValid()) {
            feedbackService.showWarning(player, "That mob must be loaded to release.");
            return;
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null) {
            feedbackService.showWarning(player, "That mob must be loaded to release.");
            return;
        }
        if (!canApplyNearbyReleaseCull(player, config, npcRef, store)) {
            feedbackService.showWarning(player, "You can only release owned nearby companions.");
            return;
        }
        clearNpcTamedOwnershipAndLinks(npcRef, store);
        trySetReleaseState(npcRef, npc, store);
        npc.setToDespawn();
        npc.setDespawnTime(RELEASE_DESPAWN_DELAY_SECONDS);
        String displayName = npcNameResolver.resolveNpcDisplayName(npcRef, store, npc);
        if (displayName == null || displayName.isBlank()) {
            displayName = "mob";
        }
        feedbackService.showSuccess(player, "Released " + displayName + ". It will despawn shortly.");
    }

    private void applyMenuCull(Player player,
                               String toolId,
                               TwCommandItemConfig config,
                               UUID npcUuid) {
        if (player == null || toolId == null || toolId.isBlank() || npcUuid == null) {
            return;
        }
        World world = player.getWorld();
        if (world == null) {
            feedbackService.showWarning(player, "Unable to cull right now.");
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (store == null) {
            feedbackService.showWarning(player, "Unable to cull right now.");
            return;
        }
        Ref<EntityStore> npcRef = world.getEntityRef(npcUuid);
        if (npcRef == null || !npcRef.isValid()) {
            feedbackService.showWarning(player, "That mob must be loaded to cull.");
            return;
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null) {
            feedbackService.showWarning(player, "That mob must be loaded to cull.");
            return;
        }
        if (!canApplyNearbyReleaseCull(player, config, npcRef, store)) {
            feedbackService.showWarning(player, "You can only cull owned nearby companions.");
            return;
        }
        DamageCause cause = DamageCause.COMMAND != null ? DamageCause.COMMAND : DamageCause.PHYSICAL;
        if (cause == null) {
            feedbackService.showWarning(player, "Unable to cull right now.");
            return;
        }
        clearNpcCommandLinks(npcRef, store);
        removeNpcFromAllCommandToolRecords(player, npcUuid);
        DeathComponent.tryAddComponent(store, npcRef, new Damage(Damage.NULL_SOURCE, cause, CULL_DAMAGE_AMOUNT));
        String displayName = npcNameResolver.resolveNpcDisplayName(npcRef, store, npc);
        if (displayName == null || displayName.isBlank()) {
            displayName = "mob";
        }
        feedbackService.showSuccess(player, "Culled " + displayName + ".");
    }

    private boolean canApplyNearbyReleaseCull(Player player,
                                              TwCommandItemConfig config,
                                              Ref<EntityStore> npcRef,
                                              Store<EntityStore> store) {
        if (player == null || npcRef == null || !npcRef.isValid() || store == null) {
            return false;
        }
        UUID ownerUuid = player.getUuid();
        if (ownerUuid == null) {
            return false;
        }
        boolean requireTamed = config != null && config.isRequireTamed();
        return linkPolicyService.passesOwnerAndTamed(true, requireTamed, npcRef, ownerUuid, store);
    }

    private void clearNpcTamedOwnershipAndLinks(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return;
        }
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        if (ownerType != null) {
            TameworkOwnerComponent owner = store.getComponent(npcRef, ownerType);
            if (owner != null && (owner.getOwnerId() != null || owner.getOwnerName() != null)) {
                owner.setOwnerId(null);
                owner.setOwnerName(null);
                store.putComponent(npcRef, ownerType, owner);
            }
        }

        ComponentType<EntityStore, TameworkTamedComponent> tamedType = TameworkTamedComponent.getComponentType();
        if (tamedType != null) {
            TameworkTamedComponent tamed = store.getComponent(npcRef, tamedType);
            if (tamed != null && tamed.isTamed()) {
                tamed.setTamed(false);
                store.putComponent(npcRef, tamedType, tamed);
            }
        }

        clearNpcCommandLinks(npcRef, store);
    }

    private void clearNpcCommandLinks(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return;
        }
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType = TameworkCommandLinksComponent.getComponentType();
        if (linksType == null) {
            return;
        }
        TameworkCommandLinksComponent links = store.getComponent(npcRef, linksType);
        if (links == null) {
            return;
        }
        links.setOwnerId(null);
        links.setToolIds(new String[0]);
        links.setHomePosition(null);
        store.putComponent(npcRef, linksType, links);
    }

    private void removeNpcFromAllCommandToolRecords(Player player, UUID npcUuid) {
        if (player == null || npcUuid == null) {
            return;
        }
        Inventory inventory = player.getInventory();
        if (inventory == null || inventory.getHotbar() == null) {
            return;
        }
        ItemContainer hotbar = inventory.getHotbar();
        short capacity = hotbar.getCapacity();
        boolean changed = false;
        for (short slot = 0; slot < capacity; slot++) {
            ItemStack stack = hotbar.getItemStack(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String stackToolId = stack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_TOOL_ID, Codec.STRING);
            if (stackToolId == null || stackToolId.isBlank()) {
                continue;
            }
            ItemStack updated = linkMutationService.removeLinkedNpcRecord(stack, npcUuid);
            if (updated == stack) {
                continue;
            }
            hotbar.setItemStackForSlot(slot, updated);
            changed = true;
        }
        if (!changed) {
            return;
        }
        inventory.markChanged();
        player.sendInventory();
    }

    private void trySetReleaseState(Ref<EntityStore> npcRef,
                                    NPCEntity npc,
                                    Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || npc == null || store == null) {
            return;
        }
        for (String state : RELEASE_STATE_CANDIDATES) {
            if (state == null || state.isBlank()) {
                continue;
            }
            if (stepExecutionService.applyState(npcRef, npc, store, state, null)) {
                return;
            }
            if (!state.startsWith("$") && stepExecutionService.applyState(npcRef, npc, store, "$" + state, null)) {
                return;
            }
        }
    }

    private void applyMenuRespawn(Player player,
                                  String toolId,
                                  UUID npcUuid) {
        if (player == null || toolId == null || toolId.isBlank() || npcUuid == null) {
            return;
        }
        if (deathService == null && lostService == null) {
            feedbackService.showWarning(player, "Companion recovery tracking is unavailable.");
            return;
        }
        Inventory inventory = player.getInventory();
        if (inventory == null || inventory.getHotbar() == null) {
            feedbackService.showWarning(player, "Unable to respawn right now.");
            return;
        }
        World world = player.getWorld();
        if (world == null) {
            feedbackService.showWarning(player, "Unable to respawn right now.");
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        Ref<EntityStore> playerRef = player.getReference();
        if (store == null || playerRef == null || !playerRef.isValid()) {
            feedbackService.showWarning(player, "Unable to respawn right now.");
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
                feedbackService.showWarning(player, "That NPC is not linked to this tool.");
                return;
            }
            CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot deadSnapshot =
                    deathService != null ? deathService.getDeadSnapshotForTool(npcUuid, toolId, player.getUuid()) : null;
            if (deadSnapshot != null) {
                String roleId = deadSnapshot.roleId();
                if ((roleId == null || roleId.isBlank()) && record.cachedRoleId != null && !record.cachedRoleId.isBlank()) {
                    roleId = record.cachedRoleId;
                }
                TwCompanionConfig.EffectiveSettings companionSettings = TwCompanionConfig.resolveEffectiveForRole(roleId);
                if (!companionSettings.isDeadRespawnEnabled()) {
                    feedbackService.showWarning(player, "Dead companion respawn is disabled.");
                    return;
                }
                long remainingMs = Math.max(0L, deadSnapshot.respawnAvailableAtMs() - System.currentTimeMillis());
                if (remainingMs > 0L) {
                    feedbackService.showWarning(player, "Respawn cooldown remaining: " + formatDuration(remainingMs) + ".");
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
                ItemStack updatedStack = respawnService.respawnDeadLinkedNpc(
                        player,
                        playerRef,
                        store,
                        toolId,
                        stack,
                        record,
                        deadSnapshot,
                        safeSpawnDistance,
                        followRetryDelayMs
                );
                if (updatedStack == null) {
                    feedbackService.showWarning(player, "Failed to respawn that companion.");
                    return;
                }
                hotbar.setItemStackForSlot(slot, updatedStack);
                inventory.markChanged();
                player.sendInventory();
                String name = deadSnapshot.displayName();
                if (name == null || name.isBlank()) {
                    name = "companion";
                }
                feedbackService.showSuccess(player, "Respawned " + name + ".");
                return;
            }
            if (lostService == null || !lostService.isLost(npcUuid)) {
                feedbackService.showWarning(player, "That companion is not marked as dead or lost.");
                return;
            }
            String roleId = record.cachedRoleId;
            TwCompanionConfig.EffectiveSettings companionSettings = TwCompanionConfig.resolveEffectiveForRole(roleId);
            double safeSpawnDistance = resolvePositiveDouble(
                    companionSettings.getRecallSafeSpawnDistance(),
                    RECALL_SAFE_SPAWN_DISTANCE
            );
            CommandLostRecoveryService.Result recoveryResult = lostRecoveryService.recoverLostLinkedNpc(
                    player,
                    playerRef,
                    store,
                    toolId,
                    stack,
                    record,
                    safeSpawnDistance
            );
            if (!recoveryResult.isSuccess()) {
                String errorMessage = recoveryResult.errorMessage();
                if (errorMessage == null || errorMessage.isBlank()) {
                    errorMessage = "Failed to recover that companion.";
                }
                feedbackService.showWarning(player, errorMessage);
                return;
            }
            hotbar.setItemStackForSlot(slot, recoveryResult.updatedStack());
            inventory.markChanged();
            player.sendInventory();
            String recoveredName = recoveryResult.recoveredName();
            if (recoveredName == null || recoveredName.isBlank()) {
                recoveredName = record.cachedDisplayName;
            }
            if (recoveredName == null || recoveredName.isBlank()) {
                recoveredName = "companion";
            }
            feedbackService.showSuccess(player, "Recovered " + recoveredName + ".");
            return;
        }
        feedbackService.showWarning(player, "Unable to find that command item.");
    }

    private void applyMenuSetHome(Player player,
                                  String toolId,
                                  UUID npcUuid) {
        if (player == null || toolId == null || toolId.isBlank() || npcUuid == null) {
            return;
        }
        Inventory inventory = player.getInventory();
        if (inventory == null || inventory.getHotbar() == null) {
            feedbackService.showWarning(player, "Unable to set home right now.");
            return;
        }
        World world = player.getWorld();
        if (world == null) {
            feedbackService.showWarning(player, "Unable to set home right now.");
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (store == null) {
            feedbackService.showWarning(player, "Unable to set home right now.");
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
                feedbackService.showWarning(player, "That NPC is not linked to this tool.");
                return;
            }
            Ref<EntityStore> npcRef = world.getEntityRef(npcUuid);
            if (npcRef == null || !npcRef.isValid()) {
                feedbackService.showWarning(player, "That companion must be loaded to set home.");
                return;
            }
            NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
            if (npc == null) {
                feedbackService.showWarning(player, "That companion must be loaded to set home.");
                return;
            }
            TameworkCommandLinksComponent links = store.getComponent(npcRef, TameworkCommandLinksComponent.getComponentType());
            if (links == null || !links.containsToolId(toolId)) {
                feedbackService.showWarning(player, "That NPC is not linked to this tool.");
                return;
            }
            UUID ownerId = links.getOwnerId();
            if (ownerId != null && !ownerId.equals(player.getUuid())) {
                feedbackService.showWarning(player, "You cannot set home for that companion.");
                return;
            }
            TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
            if (transform == null) {
                feedbackService.showWarning(player, "Unable to read that companion's position.");
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
                    home,
                    npcNameResolver.resolveNpcDisplayNameFromComponents(npcRef, store),
                    npcNameResolver.resolveNpcNameKey(npc),
                    npcNameResolver.resolveNpcRoleId(npc)
            );
            hotbar.setItemStackForSlot(slot, updatedStack);
            inventory.markChanged();
            player.sendInventory();
            feedbackService.showSuccess(player, "Set home for " + npcNameResolver.resolveNpcDisplayName(npcRef, store, npc) + ".");
            return;
        }
        feedbackService.showWarning(player, "Unable to find that command item.");
    }

    private void applyMenuRecall(Player player,
                                 String toolId,
                                 UUID npcUuid) {
        applyMenuMoveCommand(player, toolId, npcUuid, false);
    }

    private void applyMenuReturnHome(Player player,
                                     String toolId,
                                     UUID npcUuid) {
        applyMenuMoveCommand(player, toolId, npcUuid, true);
    }

    private void applyMenuMoveCommand(Player player,
                                      String toolId,
                                      UUID npcUuid,
                                      boolean returnHome) {
        menuMoveService.applyMenuMoveCommand(player, toolId, npcUuid, returnHome, this::resolveCommandLabel);
    }

    private String resolveTravelRoleId(LinkedNpcRecord record) {
        if (record != null && record.cachedRoleId != null && !record.cachedRoleId.isBlank()) {
            return record.cachedRoleId;
        }
        return null;
    }

    private String formatDuration(long remainingMs) {
        long totalSeconds = Math.max(0L, (remainingMs + 999L) / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        if (minutes <= 0L) {
            return seconds + "s";
        }
        return minutes + "m " + seconds + "s";
    }

    private ItemStack findCommandToolStack(Player player, String toolId) {
        if (player == null || toolId == null || toolId.isBlank()) {
            return null;
        }
        Inventory inventory = player.getInventory();
        if (inventory == null || inventory.getHotbar() == null) {
            return null;
        }
        ItemContainer hotbar = inventory.getHotbar();
        short capacity = hotbar.getCapacity();
        for (short slot = 0; slot < capacity; slot++) {
            ItemStack stack = hotbar.getItemStack(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String stackToolId = stack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_TOOL_ID, Codec.STRING);
            if (stackToolId != null && stackToolId.equals(toolId)) {
                return stack;
            }
        }
        return null;
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

    private String resolveCommandLabel(CommandEntry command) {
        if (command == null) {
            return "Unknown";
        }
        if (command.getDisplayName() != null && !command.getDisplayName().isBlank()) {
            return command.getDisplayName();
        }
        if (command.getId() != null && !command.getId().isBlank()) {
            return command.getId();
        }
        return "Unknown";
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

}

