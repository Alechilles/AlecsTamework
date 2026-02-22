package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
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
    private static final String MASTER_TARGET_SLOT = "MasterTarget";
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
    private final CommandNpcRelocationService relocationService;
    private final CommandLinkedNpcDeathService deathService;
    private final CommandLinkedNpcCaptureService captureService;
    private final CommandLinkedNpcRecordStore linkedNpcRecordStore;
    private final CommandFeedbackService feedbackService;
    private final CommandNpcNameResolver npcNameResolver;
    private final CommandLinkedPanelEntryService panelEntryService;
    private final CommandToolInventoryService toolInventoryService;
    private final CommandResolutionService resolutionService;
    private final CommandLinkPolicyService linkPolicyService;
    private final CommandCompanionPlacementService companionPlacementService;
    private final CommandRecipientService recipientService;
    private final CommandStepExecutionService stepExecutionService;
    private final CommandLinkMutationService linkMutationService;

    public CommandItemFeatureHandler(CommandItemRegistry registry,
                                     CommandNpcRelocationService relocationService,
                                     CommandLinkedNpcDeathService deathService,
                                     CommandLinkedNpcCaptureService captureService) {
        this.registry = registry;
        this.relocationService = relocationService;
        this.deathService = deathService;
        this.captureService = captureService;
        this.linkedNpcRecordStore = new CommandLinkedNpcRecordStore();
        this.feedbackService = new CommandFeedbackService(new TameworkUiMessageService());
        this.npcNameResolver = new CommandNpcNameResolver();
        this.panelEntryService = new CommandLinkedPanelEntryService(
                linkedNpcRecordStore,
                deathService,
                captureService,
                npcNameResolver
        );
        this.toolInventoryService = new CommandToolInventoryService(panelEntryService);
        this.resolutionService = new CommandResolutionService(registry, DEFAULT_RAYCAST_DISTANCE);
        this.linkPolicyService = new CommandLinkPolicyService();
        this.companionPlacementService = new CommandCompanionPlacementService();
        this.recipientService = new CommandRecipientService(linkPolicyService, linkedNpcRecordStore);
        this.stepExecutionService = new CommandStepExecutionService(
                relocationService,
                linkedNpcRecordStore,
                npcNameResolver
        );
        this.linkMutationService = new CommandLinkMutationService(
                linkedNpcRecordStore,
                linkPolicyService,
                npcNameResolver
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
                feedbackService.showSuccess(player, (link.linked ? "Linked " : "Unlinked ") + link.npcName + ".");
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
        double returnHomeTeleportDistance = resolvePositiveDouble(
                globalConfig != null ? globalConfig.getCommandReturnHomeTeleportDistance() : 0.0,
                HYBRID_TELEPORT_DISTANCE_THRESHOLD
        );
        double returnHomePathDistanceBeforeTeleport = resolvePositiveDouble(
                globalConfig != null ? globalConfig.getCommandReturnHomePathDistanceBeforeTeleport() : 0.0,
                HYBRID_PATH_DISTANCE_BEFORE_TELEPORT
        );
        long returnHomeTeleportDelayMs = resolvePositiveLong(
                globalConfig != null ? globalConfig.getCommandReturnHomeTeleportDelayMs() : 0L,
                HYBRID_TELEPORT_DELAY_MS
        );
        double recallSafeSpawnDistance = resolvePositiveDouble(
                globalConfig != null ? globalConfig.getCommandRecallSafeSpawnDistance() : 0.0,
                RECALL_SAFE_SPAWN_DISTANCE
        );
        double recallForceRelocateDistance = resolvePositiveDouble(
                globalConfig != null ? globalConfig.getCommandRecallForceRelocateDistance() : 0.0,
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
                globalConfig != null && globalConfig.isBlockAllPlayerDamageIfOwned(),
                globalConfig != null && globalConfig.isInvulnerableIfOwned(),
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
            feedbackService.showWarning(player, "No linked NPCs matched this command.");
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
        int queued = queueRelocationsForUnloaded(context, unloadedLinked);
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
                () -> toolInventoryService.buildLinkedPanelEntriesForTool(player, toolId),
                npcUuid -> applyMenuUnlink(player, toolId, npcUuid),
                npcUuid -> applyMenuRespawn(player, toolId, npcUuid),
                npcUuid -> applyMenuRecall(player, toolId, npcUuid),
                npcUuid -> applyMenuSetHome(player, toolId, npcUuid),
                npcUuid -> applyMenuReturnHome(player, toolId, npcUuid),
                commandId -> applyMenuSelection(player, toolId, config, commandId)
        );
        player.getPageManager().openCustomPage(playerRef, store, page);
        return true;
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

    private void applyMenuRespawn(Player player,
                                  String toolId,
                                  UUID npcUuid) {
        if (player == null || toolId == null || toolId.isBlank() || npcUuid == null) {
            return;
        }
        TwGlobalConfig globalConfig = TwGlobalConfig.resolveActive();
        if (globalConfig == null || !globalConfig.isCommandDeadRespawnEnabled()) {
            feedbackService.showWarning(player, "Dead companion respawn is disabled.");
            return;
        }
        if (deathService == null) {
            feedbackService.showWarning(player, "Dead companion tracking is unavailable.");
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
                    deathService.getDeadSnapshotForTool(npcUuid, toolId, player.getUuid());
            if (deadSnapshot == null) {
                feedbackService.showWarning(player, "That companion is not marked as dead.");
                return;
            }
            long remainingMs = Math.max(0L, deadSnapshot.respawnAvailableAtMs() - System.currentTimeMillis());
            if (remainingMs > 0L) {
                feedbackService.showWarning(player, "Respawn cooldown remaining: " + formatDuration(remainingMs) + ".");
                return;
            }
            ItemStack updatedStack = respawnDeadLinkedNpcForMenu(player, playerRef, store, toolId, stack, record, deadSnapshot);
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
        if (player == null || toolId == null || toolId.isBlank() || npcUuid == null) {
            return;
        }
        String actionLabel = returnHome ? "send home" : "recall";
        Inventory inventory = player.getInventory();
        if (inventory == null || inventory.getHotbar() == null) {
            feedbackService.showWarning(player, "Unable to " + actionLabel + " right now.");
            return;
        }
        World world = player.getWorld();
        if (world == null) {
            feedbackService.showWarning(player, "Unable to " + actionLabel + " right now.");
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        Ref<EntityStore> playerRef = player.getReference();
        if (store == null || playerRef == null || !playerRef.isValid()) {
            feedbackService.showWarning(player, "Unable to " + actionLabel + " right now.");
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
            if (deathService != null
                    && deathService.getDeadSnapshotForTool(npcUuid, toolId, player.getUuid()) != null) {
                feedbackService.showWarning(player, "That companion is dead. Use Respawn when it is ready.");
                return;
            }
            TwCommandItemConfig config = resolutionService.resolveConfig(stack.getItemId(), null);
            if (config == null || !config.isEnabled()) {
                feedbackService.showWarning(player, "That command item is not configured.");
                return;
            }
            CommandEntry panelCommand = returnHome
                    ? resolutionService.resolvePanelReturnHomeCommand(config, stack)
                    : resolutionService.resolvePanelRecallCommand(config, stack);
            if (panelCommand == null) {
                feedbackService.showWarning(
                        player,
                        returnHome
                                ? "No return-home command is configured for this item."
                                : "No recall command is configured for this item."
                );
                return;
            }
            TwGlobalConfig globalConfig = TwGlobalConfig.resolveActive();
            double returnHomeTeleportDistance = resolvePositiveDouble(
                    globalConfig != null ? globalConfig.getCommandReturnHomeTeleportDistance() : 0.0,
                    HYBRID_TELEPORT_DISTANCE_THRESHOLD
            );
            double returnHomePathDistanceBeforeTeleport = resolvePositiveDouble(
                    globalConfig != null ? globalConfig.getCommandReturnHomePathDistanceBeforeTeleport() : 0.0,
                    HYBRID_PATH_DISTANCE_BEFORE_TELEPORT
            );
            long returnHomeTeleportDelayMs = resolvePositiveLong(
                    globalConfig != null ? globalConfig.getCommandReturnHomeTeleportDelayMs() : 0L,
                    HYBRID_TELEPORT_DELAY_MS
            );
            double recallSafeSpawnDistance = resolvePositiveDouble(
                    globalConfig != null ? globalConfig.getCommandRecallSafeSpawnDistance() : 0.0,
                    RECALL_SAFE_SPAWN_DISTANCE
            );
            double recallForceRelocateDistance = resolvePositiveDouble(
                    globalConfig != null ? globalConfig.getCommandRecallForceRelocateDistance() : 0.0,
                    RECALL_FORCE_RELOCATE_DISTANCE
            );
            Ref<EntityStore> npcRef = world.getEntityRef(npcUuid);
            NPCEntity npc = (npcRef != null && npcRef.isValid())
                    ? store.getComponent(npcRef, NPCEntity.getComponentType())
                    : null;
            if (returnHome) {
                boolean hasHome = record.homePosition != null;
                if (!hasHome && npcRef != null && npcRef.isValid()) {
                    TameworkCommandLinksComponent links =
                            store.getComponent(npcRef, TameworkCommandLinksComponent.getComponentType());
                    hasHome = links != null && links.hasHome();
                }
                if (!hasHome) {
                    feedbackService.showWarning(player, "No home is set for that companion.");
                    return;
                }
            }
            Ref<EntityStore> explicitTarget = npcRef != null && npcRef.isValid() ? npcRef : null;
            Ref<EntityStore> commandTarget = resolutionService.resolveCommandTarget(playerRef, store, config, panelCommand, explicitTarget);
            Vector3d raycastPosition = resolutionService.resolveRaycastPosition(playerRef, store, config, panelCommand);
            Context context = new Context(
                    player,
                    playerRef,
                    store,
                    config,
                    panelCommand,
                    stack.getItemId(),
                    toolId,
                    commandTarget,
                    raycastPosition,
                    stack,
                    globalConfig != null && globalConfig.isBlockAllPlayerDamageIfOwned(),
                    globalConfig != null && globalConfig.isInvulnerableIfOwned(),
                    returnHomeTeleportDistance,
                    returnHomePathDistanceBeforeTeleport,
                    returnHomeTeleportDelayMs,
                    recallSafeSpawnDistance,
                    recallForceRelocateDistance
            );

            ArrayList<Candidate> loadedRecipients = new ArrayList<>(1);
            if (npc != null && npcRef != null && npcRef.isValid()) {
                double distSq = 0.0;
                TransformComponent npcTransform = store.getComponent(npcRef, TransformComponent.getComponentType());
                TransformComponent playerTransform = store.getComponent(playerRef, TransformComponent.getComponentType());
                if (npcTransform != null && playerTransform != null) {
                    Vector3d npcPos = npcTransform.getPosition();
                    Vector3d playerPos = playerTransform.getPosition();
                    double dx = npcPos.x - playerPos.x;
                    double dy = npcPos.y - playerPos.y;
                    double dz = npcPos.z - playerPos.z;
                    distSq = dx * dx + dy * dy + dz * dz;
                }
                loadedRecipients.add(new Candidate(npcRef, npc, distSq));
            }
            List<LinkedNpcRecord> unloadedRecipients = loadedRecipients.isEmpty()
                    ? List.of(record)
                    : List.of();

            int affected = 0;
            for (Candidate candidate : loadedRecipients) {
                StepResult stepResult = executeCommand(context, candidate);
                if (stepResult.applied) {
                    affected++;
                }
                if (stepResult.abortAll) {
                    break;
                }
            }
            ItemStack refreshedLinks = linkMutationService.refreshLinkedNpcPositions(context.workingItem, loadedRecipients, store);
            if (refreshedLinks != context.workingItem) {
                context.workingItem = refreshedLinks;
                context.itemChanged = true;
            }
            int queued = queueRelocationsForUnloaded(context, unloadedRecipients);
            if (context.itemChanged) {
                hotbar.setItemStackForSlot(slot, context.workingItem);
                inventory.markChanged();
                player.sendInventory();
            }
            if (affected <= 0 && queued <= 0) {
                feedbackService.showWarning(player, returnHome ? "No companions could return home." : "No NPCs could execute that command.");
                return;
            }
            feedbackService.emitCommandExecutionFeedback(context.player, context.playerRef, context.store, context.command, affected, queued, this::resolveCommandLabel);
            return;
        }
        feedbackService.showWarning(player, "Unable to find that command item.");
    }

    private ItemStack respawnDeadLinkedNpcForMenu(Player player,
                                                  Ref<EntityStore> playerRef,
                                                  Store<EntityStore> store,
                                                  String toolId,
                                                  ItemStack stack,
                                                  LinkedNpcRecord record,
                                                  CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot deadSnapshot) {
        if (player == null || playerRef == null || !playerRef.isValid() || store == null || stack == null
                || stack.isEmpty() || record == null || deadSnapshot == null) {
            return null;
        }
        String roleId = deadSnapshot.roleId();
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin == null) {
            return null;
        }
        int roleIndex = npcPlugin.getIndex(roleId);
        if (roleIndex < 0) {
            return null;
        }
        TwGlobalConfig globalConfig = TwGlobalConfig.resolveActive();
        double safeSpawnDistance = resolvePositiveDouble(
                globalConfig != null ? globalConfig.getCommandRecallSafeSpawnDistance() : 0.0,
                RECALL_SAFE_SPAWN_DISTANCE
        );
        Vector3d sourceHint = record.lastKnownPosition != null ? record.lastKnownPosition : deadSnapshot.lastKnownPosition();
        Vector3d destination = companionPlacementService.computeSafeRespawnPosition(
                playerRef,
                store,
                safeSpawnDistance,
                sourceHint
        );
        if (destination == null) {
            return null;
        }
        Vector3f rotation = resolveRespawnRotation(store, playerRef, destination);
        Pair<Ref<EntityStore>, NPCEntity> spawned = npcPlugin.spawnEntity(store, roleIndex, destination, rotation, null, null);
        if (spawned == null || spawned.first() == null || spawned.second() == null) {
            return null;
        }
        Ref<EntityStore> spawnedRef = spawned.first();
        NPCEntity spawnedNpc = spawned.second();
        UUID ownerId = deadSnapshot.ownerId() != null ? deadSnapshot.ownerId() : player.getUuid();
        Vector3d homePosition = record.homePosition != null ? record.homePosition : deadSnapshot.homePosition();
        String[] toolIds = linkPolicyService.mergeToolIds(deadSnapshot.toolIds(), toolId);
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType = TameworkCommandLinksComponent.getComponentType();
        if (linksType != null) {
            store.putComponent(
                    spawnedRef,
                    linksType,
                    new TameworkCommandLinksComponent(ownerId, toolIds, homePosition)
            );
        }
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        if (ownerType != null) {
            store.putComponent(
                    spawnedRef,
                    ownerType,
                    new TameworkOwnerComponent(ownerId, deadSnapshot.ownerName())
            );
        }
        ComponentType<EntityStore, TameworkTamedComponent> tamedType = TameworkTamedComponent.getComponentType();
        if (tamedType != null) {
            store.putComponent(
                    spawnedRef,
                    tamedType,
                    new TameworkTamedComponent(deadSnapshot.tamed())
            );
        }
        if (deadSnapshot.customName() != null && !deadSnapshot.customName().isBlank()) {
            ComponentType<EntityStore, TameworkNpcNameComponent> nameType = TameworkNpcNameComponent.getComponentType();
            if (nameType != null) {
                store.putComponent(
                        spawnedRef,
                        nameType,
                        new TameworkNpcNameComponent(
                                deadSnapshot.customName(),
                                ownerId,
                                System.currentTimeMillis(),
                                TameworkNpcNameComponent.NameSource.System
                        )
                );
            }
            EntitySupport.setDisplayName(spawnedRef, deadSnapshot.customName(), store);
        }
        applyRespawnFollowBootstrap(spawnedRef, spawnedNpc, playerRef, store);
        long followRetryDelayMs = resolvePositiveLong(
                globalConfig != null ? globalConfig.getCommandDeadRespawnFollowRetryDelayMs() : 0L,
                RESPAWN_FOLLOW_RETRY_DELAY_MS
        );
        scheduleRespawnFollowRetry(player.getWorld(), spawnedNpc.getUuid(), playerRef, followRetryDelayMs);
        ItemStack updated = linkMutationService.removeLinkedNpcRecord(stack, record.npcUuid);
        updated = linkMutationService.upsertLinkedNpcRecord(
                updated,
                spawnedNpc.getUuid(),
                destination,
                homePosition,
                npcNameResolver.resolveNpcDisplayNameFromComponents(spawnedRef, store),
                npcNameResolver.resolveNpcNameKey(spawnedNpc),
                npcNameResolver.resolveNpcRoleId(spawnedNpc)
        );
        if (deathService != null) {
            deathService.clearDeadSnapshot(deadSnapshot.npcUuid());
        }
        return updated;
    }

    private void applyRespawnFollowBootstrap(Ref<EntityStore> npcRef,
                                             NPCEntity npc,
                                             Ref<EntityStore> playerRef,
                                             Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || npc == null || store == null) {
            return;
        }
        Role role = npc.getRole();
        if (role != null && role.getMarkedEntitySupport() != null) {
            role.getMarkedEntitySupport().setMarkedEntity("LockedTarget", null);
            if (playerRef != null && playerRef.isValid()) {
                role.getMarkedEntitySupport().setMarkedEntity(MASTER_TARGET_SLOT, playerRef);
            }
        }
        // Match default Follow behavior after respawn.
        if (!stepExecutionService.applyState(npcRef, npc, store, "Follow", null)) {
            stepExecutionService.applyState(npcRef, npc, store, "Idle", null);
        }
    }

    private void scheduleRespawnFollowRetry(World world,
                                            UUID npcUuid,
                                            Ref<EntityStore> playerRef,
                                            long delayMs) {
        if (world == null || npcUuid == null) {
            return;
        }
        long safeDelayMs = Math.max(0L, delayMs);
        CompletableFuture.runAsync(
                () -> world.execute(() -> {
                    Ref<EntityStore> npcRef = world.getEntityRef(npcUuid);
                    if (npcRef == null || !npcRef.isValid()) {
                        return;
                    }
                    Store<EntityStore> store = world.getEntityStore().getStore();
                    if (store == null) {
                        return;
                    }
                    NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
                    if (npc == null) {
                        return;
                    }
                    applyRespawnFollowBootstrap(npcRef, npc, playerRef, store);
                }),
                CompletableFuture.delayedExecutor(safeDelayMs, TimeUnit.MILLISECONDS)
        );
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

    private int queueRelocationsForUnloaded(Context context, List<LinkedNpcRecord> unloadedLinked) {
        if (context == null || unloadedLinked == null || unloadedLinked.isEmpty() || relocationService == null) {
            return 0;
        }
        boolean returnHome = resolutionService.isReturnHomeCommand(context.command);
        boolean recall = resolutionService.isRecallCommand(context.command);
        if (!returnHome && !recall) {
            return 0;
        }
        RelocationState postRelocationState = stepExecutionService.resolveRelocationState(context.command, returnHome, recall);
        World world = context.player != null ? context.player.getWorld() : null;
        UUID ownerUuid = context.player != null ? context.player.getUuid() : null;
        if (world == null) {
            return 0;
        }
        int queued = 0;
        for (LinkedNpcRecord record : unloadedLinked) {
            if (record == null || record.npcUuid == null) {
                continue;
            }
            if (deathService != null
                    && deathService.getDeadSnapshotForTool(record.npcUuid, context.toolId, ownerUuid) != null) {
                continue;
            }
            if (returnHome) {
                if (record.homePosition == null) {
                    continue;
                }
                relocationService.queueRelocation(
                        world,
                        record.npcUuid,
                        record.homePosition,
                        ownerUuid,
                        false,
                        true,
                        postRelocationState.state,
                        postRelocationState.subState,
                        0L,
                        record.lastKnownPosition,
                        record.homePosition
                );
                queued++;
                continue;
            }
            Vector3d sourceHint = record.lastKnownPosition != null ? record.lastKnownPosition : record.homePosition;
            Vector3d safeDestination = companionPlacementService.computeSafeRecallPosition(context, sourceHint);
            if (safeDestination == null) {
                continue;
            }
            relocationService.queueRelocation(
                    world,
                    record.npcUuid,
                    safeDestination,
                    ownerUuid,
                    true,
                    true,
                    postRelocationState.state,
                    postRelocationState.subState,
                    0L,
                    sourceHint,
                    record.homePosition
            );
            queued++;
        }
        return queued;
    }

    private Vector3f resolveRespawnRotation(Store<EntityStore> store,
                                            Ref<EntityStore> playerRef,
                                            Vector3d spawnPosition) {
        if (store == null || playerRef == null || !playerRef.isValid()) {
            return new Vector3f();
        }
        TransformComponent transform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (transform == null) {
            return new Vector3f();
        }
        Vector3d playerPos = new Vector3d(transform.getPosition());
        if (spawnPosition != null) {
            Vector3d relative = new Vector3d(
                    playerPos.x - spawnPosition.x,
                    0.0,
                    playerPos.z - spawnPosition.z
            );
            if (relative.squaredLength() > 0.0001) {
                return Vector3f.lookAt(relative);
            }
        }
        return new Vector3f(transform.getRotation());
    }

    private StepResult executeCommand(Context context, Candidate candidate) {
        maybeRelocateLoadedRecallCandidate(context, candidate);
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


    private void maybeRelocateLoadedRecallCandidate(Context context, Candidate candidate) {
        if (context == null || candidate == null || candidate.ref == null || candidate.npc == null) {
            return;
        }
        if (!resolutionService.isRecallCommand(context.command)) {
            return;
        }
        TransformComponent npcTransform = context.store.getComponent(candidate.ref, TransformComponent.getComponentType());
        TransformComponent playerTransform = context.store.getComponent(context.playerRef, TransformComponent.getComponentType());
        if (npcTransform == null || playerTransform == null) {
            return;
        }
        Vector3d npcPos = npcTransform.getPosition();
        Vector3d playerPos = playerTransform.getPosition();
        double dx = npcPos.x - playerPos.x;
        double dy = npcPos.y - playerPos.y;
        double dz = npcPos.z - playerPos.z;
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq < context.recallForceRelocateDistance * context.recallForceRelocateDistance) {
            return;
        }
        Vector3d safePosition = companionPlacementService.computeSafeRecallPosition(
                context,
                new Vector3d(npcPos)
        );
        if (safePosition == null) {
            return;
        }
        candidate.npc.moveTo(candidate.ref, safePosition.x, safePosition.y, safePosition.z, context.store);
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

