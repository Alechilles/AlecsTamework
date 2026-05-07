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
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkHookComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.npc.progression.CompanionTalentService;
import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.ui.TameworkCompanionTalentsPage;
import com.alechilles.alecstamework.ui.TameworkCommandSelectionPage;
import com.alechilles.alecstamework.ui.TameworkUiMessageService;
import com.hypixel.hytale.builtin.mounts.MountPlugin;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import org.joml.Vector3d;
import org.joml.Vector3f;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
    private final CommandLinkedNpcLocateService locateService;
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
        UUID playerUuid = player.getUuid();
        if (playerUuid == null) {
            return;
        }
        world.execute(() -> dismountPlayerAfterWorldJoin(world, playerUuid));
        CompletableFuture.runAsync(
                () -> world.execute(() -> queueWorldChangeTravelRelocationsForPlayerUuid(world, playerUuid)),
                CompletableFuture.delayedExecutor(250L, TimeUnit.MILLISECONDS)
        );
    }

    public void queueWorldChangeTravelRelocationsForPlayerUuid(World destinationWorld, UUID playerUuid) {
        if (destinationWorld == null || playerUuid == null || relocationService == null) {
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

    private void dismountPlayerAfterWorldJoin(World world, UUID playerUuid) {
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
        ItemStack reconciled = reconcileStaleLinkedNpcRecords(player, store, config, working, tool.toolId);
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
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.execution.noRecipients");
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
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.execution.none");
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
                () -> groupAssignPageService.resolveGroupDropdownEntries(player, toolId),
                entry -> recallTeleportingEnabled || !resolutionService.isRecallCommand(entry),
                recallTeleportingEnabled,
                npcUuid -> panelActionService.applyLink(player, toolId, config, npcUuid),
                npcUuid -> applyMenuUnlink(player, toolId, npcUuid),
                npcUuid -> panelActionService.applyToggleActive(player, toolId, config, npcUuid),
                npcUuid -> panelActionService.applyToggleBreeding(player, toolId, npcUuid),
                npcUuid -> applyMenuRelease(player, toolId, config, npcUuid),
                npcUuid -> applyMenuCull(player, toolId, config, npcUuid),
                npcUuid -> applyMenuRespawn(player, toolId, npcUuid),
                npcUuid -> applyMenuLocate(player, toolId, npcUuid),
                npcUuid -> applyMenuRecall(player, toolId, npcUuid),
                npcUuid -> applyMenuSetHome(player, toolId, npcUuid),
                npcUuid -> applyMenuReturnHome(player, toolId, npcUuid),
                npcUuid -> openTalentPageFromSelection(player, config, toolId, npcUuid),
                value -> panelActionService.applySetPanelMode(player, toolId, value),
                enabled -> panelActionService.applySetAutoLinkEnabled(player, toolId, enabled),
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
        try {
            player.getPageManager().openCustomPage(playerRef, store, page);
            return true;
        } catch (Throwable throwable) {
            TameworkTelemetryEvents.recordErrorIfAvailable(
                    "ui_page_open_failed",
                    throwable,
                    "page=TameworkCommandSelectionPage toolId=" + toolId
            );
            return false;
        }
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

    private void openTalentPageFromSelection(Player player,
                                             TwCommandItemConfig config,
                                             String toolId,
                                             UUID npcUuid) {
        if (player == null || config == null || toolId == null || toolId.isBlank() || npcUuid == null) {
            return;
        }
        World world = player.getWorld();
        if (world == null || player.getPageManager() == null) {
            feedbackService.showWarning(player, "Talent page is unavailable right now.");
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        Ref<EntityStore> playerRef = player.getReference();
        PlayerRef uiPlayerRef = player.getPlayerRef();
        if (store == null || playerRef == null || !playerRef.isValid() || uiPlayerRef == null || !uiPlayerRef.isValid()) {
            feedbackService.showWarning(player, "Talent page is unavailable right now.");
            return;
        }
        TameworkCompanionTalentsPage page = new TameworkCompanionTalentsPage(
                uiPlayerRef,
                () -> buildTalentPageData(player, toolId, npcUuid),
                talentId -> applyTalentPurchase(player, toolId, npcUuid, talentId),
                () -> reopenSelectionMenu(player, config, toolId)
        );
        try {
            player.getPageManager().openCustomPage(playerRef, store, page);
        } catch (Throwable throwable) {
            TameworkTelemetryEvents.recordErrorIfAvailable(
                    "ui_page_open_failed",
                    throwable,
                    "page=TameworkCompanionTalentsPage npc=" + npcUuid
            );
            feedbackService.showWarning(player, "Talent page is unavailable right now.");
        }
    }

    @Nonnull
    private TameworkCompanionTalentsPage.PageData buildTalentPageData(@Nonnull Player player,
                                                                      @Nonnull String toolId,
                                                                      @Nonnull UUID npcUuid) {
        LoadedCompanionTalentContext context = resolveLoadedCompanionTalentContext(player, toolId, npcUuid);
        if (context == null) {
            return TameworkCompanionTalentsPage.PageData.empty();
        }
        CompanionLevelingService.LevelingSnapshot leveling = CompanionLevelingService.resolveSnapshot(
                context.npcRef(),
                context.store(),
                context.roleId()
        );
        int availablePoints = CompanionTalentService.resolveAvailablePoints(context.npcRef(), context.store());
        String levelSummary;
        if (leveling == null) {
            levelSummary = "Level data unavailable";
        } else if (leveling.atMaxLevel()) {
            levelSummary = "Level " + leveling.level() + " (MAX)";
        } else {
            levelSummary = "Level "
                    + leveling.level()
                    + " - XP "
                    + Math.max(0, Math.round(leveling.currentXp()))
                    + "/"
                    + Math.max(1, Math.round(leveling.nextLevelDeltaXp()));
        }
        String pointsSummary = "Talent Points: " + availablePoints + " available";
        TwTalentConfig talentConfig = CompanionTalentService.resolveTalentConfig(context.npcRef(), context.store());
        if (talentConfig == null || !talentConfig.isEnabled() || talentConfig.getTalents().length == 0) {
            return new TameworkCompanionTalentsPage.PageData(
                    context.displayName(),
                    levelSummary,
                    pointsSummary,
                    "No talent tree is configured for this companion.",
                    List.of()
            );
        }
        ComponentType<EntityStore, TameworkTalentsComponent> talentsType = TameworkTalentsComponent.getComponentType();
        TameworkTalentsComponent talents = talentsType != null ? context.store().getComponent(context.npcRef(), talentsType) : null;
        ArrayList<TameworkCompanionTalentsPage.TalentEntry> entries = new ArrayList<>();
        for (TwTalentConfig.TalentDefinition talent : talentConfig.getTalents()) {
            if (talent == null || talent.getId() == null) {
                continue;
            }
            boolean purchased = talents != null && talents.hasPurchasedTalent(talent.getId());
            boolean levelMet = leveling != null && leveling.level() >= talent.getMinLevel();
            String missingPrerequisite = resolveMissingPrerequisiteName(talents, talentConfig, talent);
            boolean prerequisitesMet = missingPrerequisite == null;
            boolean canPurchase = !purchased && levelMet && prerequisitesMet && availablePoints >= talent.getPointCost();
            String status;
            if (purchased) {
                status = "Unlocked";
            } else if (!levelMet) {
                status = "Requires Level " + talent.getMinLevel();
            } else if (!prerequisitesMet) {
                status = "Requires " + missingPrerequisite;
            } else if (availablePoints < talent.getPointCost()) {
                status = "Costs " + talent.getPointCost() + " points";
            } else {
                status = "Cost " + talent.getPointCost() + " points";
            }
            entries.add(new TameworkCompanionTalentsPage.TalentEntry(
                    talent.getId(),
                    "Tier " + talent.getTier() + " - " + talent.getDisplayName(),
                    talent.getDescription() != null ? talent.getDescription() : "Passive talent",
                    status,
                    canPurchase
            ));
        }
        entries.sort((left, right) -> {
            if (left == null && right == null) {
                return 0;
            }
            if (left == null) {
                return 1;
            }
            if (right == null) {
                return -1;
            }
            return left.displayName().compareToIgnoreCase(right.displayName());
        });
        return new TameworkCompanionTalentsPage.PageData(
                context.displayName(),
                levelSummary,
                pointsSummary,
                entries.isEmpty() ? "No talents are configured for this companion." : "Choose a talent to inspect or unlock.",
                entries
        );
    }

    @Nonnull
    private String applyTalentPurchase(@Nonnull Player player,
                                       @Nonnull String toolId,
                                       @Nonnull UUID npcUuid,
                                       @Nullable String talentId) {
        LoadedCompanionTalentContext context = resolveLoadedCompanionTalentContext(player, toolId, npcUuid);
        if (context == null) {
            String message = "Companion is no longer loaded.";
            feedbackService.showWarning(player, message);
            return message;
        }
        CompanionTalentService.PurchaseResult result = CompanionTalentService.purchaseTalent(
                context.npcRef(),
                context.store(),
                talentId
        );
        if (result.applied()) {
            feedbackService.showSuccess(player, result.message());
        } else {
            feedbackService.showWarning(player, result.message());
        }
        return result.message();
    }

    @Nullable
    private String resolveMissingPrerequisiteName(@Nullable TameworkTalentsComponent talents,
                                                  @Nonnull TwTalentConfig talentConfig,
                                                  @Nonnull TwTalentConfig.TalentDefinition talent) {
        for (String requiredTalentId : talent.getRequiresTalentIds()) {
            if (requiredTalentId == null || requiredTalentId.isBlank()) {
                continue;
            }
            if (talents != null && talents.hasPurchasedTalent(requiredTalentId)) {
                continue;
            }
            TwTalentConfig.TalentDefinition prerequisite = talentConfig.findTalent(requiredTalentId);
            return prerequisite != null ? prerequisite.getDisplayName() : requiredTalentId;
        }
        return null;
    }

    @Nullable
    private LoadedCompanionTalentContext resolveLoadedCompanionTalentContext(@Nonnull Player player,
                                                                             @Nonnull String toolId,
                                                                             @Nonnull UUID npcUuid) {
        if (toolId == null || toolId.isBlank()) {
            return null;
        }
        World world = player.getWorld();
        if (world == null) {
            return null;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (store == null) {
            return null;
        }
        Ref<EntityStore> npcRef = world.getEntityRef(npcUuid);
        if (npcRef == null || !npcRef.isValid()) {
            return null;
        }
        ItemStack toolStack = findCommandToolStack(player, toolId);
        if (toolStack == null || toolStack.isEmpty()) {
            return null;
        }
        LinkedNpcRecord record = linkMutationService.findLinkedNpcRecord(
                linkMutationService.readLinkedNpcRecords(toolStack),
                npcUuid
        );
        if (record == null) {
            return null;
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null) {
            return null;
        }
        TameworkCommandLinksComponent links = store.getComponent(npcRef, TameworkCommandLinksComponent.getComponentType());
        if (links == null || !links.containsToolId(toolId)) {
            return null;
        }
        UUID ownerId = links.getOwnerId();
        if (ownerId != null && !ownerId.equals(player.getUuid())) {
            return null;
        }
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        if (roleId == null || roleId.isBlank()) {
            roleId = npcNameResolver.resolveNpcRoleId(npc);
        }
        String displayName = npcNameResolver.resolveNpcDisplayName(npcRef, store, npc);
        if (displayName == null || displayName.isBlank()) {
            displayName = "Companion";
        }
        return new LoadedCompanionTalentContext(npcRef, store, npc, displayName, roleId);
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
                                  UUID npcUuid) {
        if (player == null || toolId == null || toolId.isBlank() || npcUuid == null) {
            return;
        }
        World world = player.getWorld();
        if (world == null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.release.unavailable");
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (store == null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.release.unavailable");
            return;
        }
        Ref<EntityStore> npcRef = world.getEntityRef(npcUuid);
        if (npcRef == null || !npcRef.isValid()) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.release.mustBeLoaded");
            return;
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.release.mustBeLoaded");
            return;
        }
        if (!canApplyNearbyReleaseCull(player, config, npcRef, store)) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.release.ownedNearbyOnly");
            return;
        }
        clearNpcTamedOwnershipAndLinks(npcRef, store);
        trySetReleaseState(npcRef, npc, store);
        npc.setToDespawn();
        npc.setDespawnTime(RELEASE_DESPAWN_DELAY_SECONDS);
        String displayName = npcNameResolver.resolveNpcDisplayName(npcRef, store, npc);
        if (displayName == null || displayName.isBlank()) {
            displayName = LocalizedText.resolve(player, "tamework.ui.notifications.command.shared.defaultMobName");
        }
        feedbackService.showSuccessKey(player, "tamework.ui.notifications.command.release.success", displayName);
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
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.cull.unavailable");
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (store == null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.cull.unavailable");
            return;
        }
        Ref<EntityStore> npcRef = world.getEntityRef(npcUuid);
        if (npcRef == null || !npcRef.isValid()) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.cull.mustBeLoaded");
            return;
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.cull.mustBeLoaded");
            return;
        }
        if (!canApplyNearbyReleaseCull(player, config, npcRef, store)) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.cull.ownedNearbyOnly");
            return;
        }
        DamageCause cause = DamageCause.COMMAND != null ? DamageCause.COMMAND : DamageCause.PHYSICAL;
        if (cause == null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.cull.unavailable");
            return;
        }
        clearNpcCommandLinks(npcRef, store);
        removeNpcFromAllCommandToolRecords(player, npcUuid);
        DeathComponent.tryAddComponent(store, npcRef, new Damage(Damage.NULL_SOURCE, cause, CULL_DAMAGE_AMOUNT));
        String displayName = npcNameResolver.resolveNpcDisplayName(npcRef, store, npc);
        if (displayName == null || displayName.isBlank()) {
            displayName = LocalizedText.resolve(player, "tamework.ui.notifications.command.shared.defaultMobName");
        }
        feedbackService.showSuccessKey(player, "tamework.ui.notifications.command.cull.success", displayName);
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
        return linkPolicyService.passesOwnerAndTamed(
                resolveLinkingRequireOwner(),
                requireTamed,
                npcRef,
                ownerUuid,
                store
        );
    }

    private boolean resolveLinkingRequireOwner() {
        TwGlobalConfig global = TwGlobalConfig.resolveActive();
        return global != null
                ? global.isOwnershipLinkingRequiresOwner()
                : TwGlobalConfig.defaultConfig().isOwnershipLinkingRequiresOwner();
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
            CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot deadSnapshot =
                    deathService != null ? deathService.getDeadSnapshotForTool(npcUuid, toolId, player.getUuid()) : null;
            if (deadSnapshot != null) {
                String roleId = deadSnapshot.roleId();
                if ((roleId == null || roleId.isBlank()) && record.cachedRoleId != null && !record.cachedRoleId.isBlank()) {
                    roleId = record.cachedRoleId;
                }
                TwCompanionConfig.EffectiveSettings companionSettings = TwCompanionConfig.resolveEffectiveForRole(roleId);
                if (!companionSettings.isDeadRespawnEnabled()) {
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
                    feedbackService.showWarningKey(player, "tamework.ui.notifications.command.respawn.failed");
                    return;
                }
                hotbar.setItemStackForSlot(slot, updatedStack);
                String name = deadSnapshot.displayName();
                if (name == null || name.isBlank()) {
                    name = LocalizedText.resolve(player, "tamework.ui.notifications.command.shared.defaultCompanionName");
                }
                feedbackService.showSuccessKey(player, "tamework.ui.notifications.command.respawn.success", name);
                return;
            }
            if (lostService == null || !lostService.isLost(npcUuid)) {
                feedbackService.showWarningKey(player, "tamework.ui.notifications.command.respawn.notDeadOrLost");
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
                    errorMessage = LocalizedText.resolve(player, "tamework.ui.notifications.command.respawn.recoverFailed");
                }
                feedbackService.showWarning(player, errorMessage);
                return;
            }
            hotbar.setItemStackForSlot(slot, recoveryResult.updatedStack());
            String recoveredName = recoveryResult.recoveredName();
            if (recoveredName == null || recoveredName.isBlank()) {
                recoveredName = record.cachedDisplayName;
            }
            if (recoveredName == null || recoveredName.isBlank()) {
                recoveredName = LocalizedText.resolve(player, "tamework.ui.notifications.command.shared.defaultCompanionName");
            }
            feedbackService.showSuccessKey(player, "tamework.ui.notifications.command.respawn.recovered", recoveredName);
            return;
        }
        feedbackService.showWarningKey(player, "tamework.ui.notifications.command.shared.itemNotFound");
    }

    private void applyMenuSetHome(Player player,
                                  String toolId,
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
                                 UUID npcUuid) {
        applyMenuMoveCommand(player, toolId, npcUuid, false);
    }

    private void applyMenuLocate(Player player,
                                 String toolId,
                                 UUID npcUuid) {
        locateService.locate(player, toolId, npcUuid);
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
        return LocalizedText.format(player, "tamework.ui.shared.duration.minutesSeconds", minutes, seconds);
    }

    private ItemStack findCommandToolStack(Player player, String toolId) {
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
        if (command.getDisplayName() != null && !command.getDisplayName().isBlank()) {
            return command.getDisplayName();
        }
        if (command.getId() != null && !command.getId().isBlank()) {
            return command.getId();
        }
        return LocalizedText.resolve(player, "tamework.ui.notifications.command.unknown");
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

