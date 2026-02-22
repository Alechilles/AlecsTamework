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
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.MembershipMode;
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
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
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
import java.util.Comparator;
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
                sendWarningMessage(player, "No command choices are available for this item.");
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
                sendWarningMessage(player, "No command is configured for this item.");
                return false;
            }
            String label = resolveCommandLabel(selection.command);
            sendDefaultMessage(player, "Selected: " + label);
            return true;
        }

        if (targetRef != null && config.isLinkEnabled() && config.isLinkUseTogglesMembership()) {
            LinkToggleResult link = tryToggleLink(player, store, targetRef, tool.toolId, config, working);
            if (link.toggled) {
                if (link.updatedItem != null) {
                    working = link.updatedItem;
                    updateHeldItem = true;
                }
                if (updateHeldItem) {
                    updateHeldItem(player, working);
                }
                sendSuccessMessage(player, (link.linked ? "Linked " : "Unlinked ") + link.npcName + ".");
                return true;
            }
        }

        int cooldownMs = Math.max(0, config.getCooldownSeconds()) * 1000;
        if (isCooldownActive(working, cooldownMs)) {
            if (updateHeldItem) {
                updateHeldItem(player, working);
            }
            sendWarningMessage(player, "That command item is on cooldown.");
            return false;
        }

        CommandEntry command = resolutionService.resolveCommand(config, commandIdOverride, working);
        if (command == null) {
            if (updateHeldItem) {
                updateHeldItem(player, working);
            }
            sendWarningMessage(player, "No command is configured for this item.");
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

        List<Candidate> recipients = queryRecipients(context);
        List<LinkedNpcRecord> unloadedLinked = queryUnloadedLinkedRecords(context, recipients);
        if (recipients.isEmpty() && unloadedLinked.isEmpty()) {
            if (updateHeldItem) {
                updateHeldItem(player, working);
            }
            sendWarningMessage(player, "No linked NPCs matched this command.");
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
        ItemStack refreshedLinks = refreshLinkedNpcPositions(context.workingItem, recipients, store);
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
            sendWarningMessage(player, "No NPCs could execute that command.");
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
        emitCommandExecutionFeedback(context, affected, queued);
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
            sendWarningMessage(player, "That command is no longer available.");
            return;
        }
        boolean updated = toolInventoryService.setSelectedCommandOnTool(player, toolId, selected.getId());
        if (!updated) {
            sendWarningMessage(player, "Unable to apply the selected command.");
            return;
        }
        String label = resolveCommandLabel(selected);
        sendDefaultMessage(player, "Selected: " + label);
    }

    private void applyMenuUnlink(Player player,
                                 String toolId,
                                 UUID npcUuid) {
        if (player == null || toolId == null || toolId.isBlank() || npcUuid == null) {
            return;
        }
        Inventory inventory = player.getInventory();
        if (inventory == null || inventory.getHotbar() == null) {
            sendWarningMessage(player, "Unable to unlink right now.");
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
            ItemStack updatedStack = removeLinkedNpcRecord(stack, npcUuid);
            boolean itemChanged = updatedStack != stack;
            boolean componentChanged = unlinkLoadedNpcFromTool(player, npcUuid, toolId);
            if (!itemChanged && !componentChanged) {
                sendWarningMessage(player, "That NPC is not linked to this tool.");
            } else {
                hotbar.setItemStackForSlot(slot, updatedStack);
                inventory.markChanged();
                player.sendInventory();
                sendSuccessMessage(player, "Removed linked NPC.");
            }
            return;
        }
        sendWarningMessage(player, "Unable to find that command item.");
    }

    private void applyMenuRespawn(Player player,
                                  String toolId,
                                  UUID npcUuid) {
        if (player == null || toolId == null || toolId.isBlank() || npcUuid == null) {
            return;
        }
        TwGlobalConfig globalConfig = TwGlobalConfig.resolveActive();
        if (globalConfig == null || !globalConfig.isCommandDeadRespawnEnabled()) {
            sendWarningMessage(player, "Dead companion respawn is disabled.");
            return;
        }
        if (deathService == null) {
            sendWarningMessage(player, "Dead companion tracking is unavailable.");
            return;
        }
        Inventory inventory = player.getInventory();
        if (inventory == null || inventory.getHotbar() == null) {
            sendWarningMessage(player, "Unable to respawn right now.");
            return;
        }
        World world = player.getWorld();
        if (world == null) {
            sendWarningMessage(player, "Unable to respawn right now.");
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        Ref<EntityStore> playerRef = player.getReference();
        if (store == null || playerRef == null || !playerRef.isValid()) {
            sendWarningMessage(player, "Unable to respawn right now.");
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
            LinkedNpcRecord record = findLinkedNpcRecord(readLinkedNpcRecords(stack), npcUuid);
            if (record == null) {
                sendWarningMessage(player, "That NPC is not linked to this tool.");
                return;
            }
            CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot deadSnapshot =
                    deathService.getDeadSnapshotForTool(npcUuid, toolId, player.getUuid());
            if (deadSnapshot == null) {
                sendWarningMessage(player, "That companion is not marked as dead.");
                return;
            }
            long remainingMs = Math.max(0L, deadSnapshot.respawnAvailableAtMs() - System.currentTimeMillis());
            if (remainingMs > 0L) {
                sendWarningMessage(player, "Respawn cooldown remaining: " + formatDuration(remainingMs) + ".");
                return;
            }
            ItemStack updatedStack = respawnDeadLinkedNpcForMenu(player, playerRef, store, toolId, stack, record, deadSnapshot);
            if (updatedStack == null) {
                sendWarningMessage(player, "Failed to respawn that companion.");
                return;
            }
            hotbar.setItemStackForSlot(slot, updatedStack);
            inventory.markChanged();
            player.sendInventory();
            String name = deadSnapshot.displayName();
            if (name == null || name.isBlank()) {
                name = "companion";
            }
            sendSuccessMessage(player, "Respawned " + name + ".");
            return;
        }
        sendWarningMessage(player, "Unable to find that command item.");
    }

    private void applyMenuSetHome(Player player,
                                  String toolId,
                                  UUID npcUuid) {
        if (player == null || toolId == null || toolId.isBlank() || npcUuid == null) {
            return;
        }
        Inventory inventory = player.getInventory();
        if (inventory == null || inventory.getHotbar() == null) {
            sendWarningMessage(player, "Unable to set home right now.");
            return;
        }
        World world = player.getWorld();
        if (world == null) {
            sendWarningMessage(player, "Unable to set home right now.");
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (store == null) {
            sendWarningMessage(player, "Unable to set home right now.");
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
            LinkedNpcRecord record = findLinkedNpcRecord(readLinkedNpcRecords(stack), npcUuid);
            if (record == null) {
                sendWarningMessage(player, "That NPC is not linked to this tool.");
                return;
            }
            Ref<EntityStore> npcRef = world.getEntityRef(npcUuid);
            if (npcRef == null || !npcRef.isValid()) {
                sendWarningMessage(player, "That companion must be loaded to set home.");
                return;
            }
            NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
            if (npc == null) {
                sendWarningMessage(player, "That companion must be loaded to set home.");
                return;
            }
            TameworkCommandLinksComponent links = store.getComponent(npcRef, TameworkCommandLinksComponent.getComponentType());
            if (links == null || !links.containsToolId(toolId)) {
                sendWarningMessage(player, "That NPC is not linked to this tool.");
                return;
            }
            UUID ownerId = links.getOwnerId();
            if (ownerId != null && !ownerId.equals(player.getUuid())) {
                sendWarningMessage(player, "You cannot set home for that companion.");
                return;
            }
            TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
            if (transform == null) {
                sendWarningMessage(player, "Unable to read that companion's position.");
                return;
            }
            Vector3d home = new Vector3d(transform.getPosition());
            if (links.getOwnerId() == null && player.getUuid() != null) {
                links.setOwnerId(player.getUuid());
            }
            links.setHomePosition(home);
            store.putComponent(npcRef, TameworkCommandLinksComponent.getComponentType(), links);
            ItemStack updatedStack = upsertLinkedNpcRecord(
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
            sendSuccessMessage(player, "Set home for " + npcNameResolver.resolveNpcDisplayName(npcRef, store, npc) + ".");
            return;
        }
        sendWarningMessage(player, "Unable to find that command item.");
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
            sendWarningMessage(player, "Unable to " + actionLabel + " right now.");
            return;
        }
        World world = player.getWorld();
        if (world == null) {
            sendWarningMessage(player, "Unable to " + actionLabel + " right now.");
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        Ref<EntityStore> playerRef = player.getReference();
        if (store == null || playerRef == null || !playerRef.isValid()) {
            sendWarningMessage(player, "Unable to " + actionLabel + " right now.");
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
            LinkedNpcRecord record = findLinkedNpcRecord(readLinkedNpcRecords(stack), npcUuid);
            if (record == null) {
                sendWarningMessage(player, "That NPC is not linked to this tool.");
                return;
            }
            if (deathService != null
                    && deathService.getDeadSnapshotForTool(npcUuid, toolId, player.getUuid()) != null) {
                sendWarningMessage(player, "That companion is dead. Use Respawn when it is ready.");
                return;
            }
            TwCommandItemConfig config = resolutionService.resolveConfig(stack.getItemId(), null);
            if (config == null || !config.isEnabled()) {
                sendWarningMessage(player, "That command item is not configured.");
                return;
            }
            CommandEntry panelCommand = returnHome
                    ? resolutionService.resolvePanelReturnHomeCommand(config, stack)
                    : resolutionService.resolvePanelRecallCommand(config, stack);
            if (panelCommand == null) {
                sendWarningMessage(
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
                    sendWarningMessage(player, "No home is set for that companion.");
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
            ItemStack refreshedLinks = refreshLinkedNpcPositions(context.workingItem, loadedRecipients, store);
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
                sendWarningMessage(player, returnHome ? "No companions could return home." : "No NPCs could execute that command.");
                return;
            }
            emitCommandExecutionFeedback(context, affected, queued);
            return;
        }
        sendWarningMessage(player, "Unable to find that command item.");
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
        ItemStack updated = removeLinkedNpcRecord(stack, record.npcUuid);
        updated = upsertLinkedNpcRecord(
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
        if (!applyState(npcRef, npc, store, "Follow", null)) {
            applyState(npcRef, npc, store, "Idle", null);
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

    private LinkedNpcRecord findLinkedNpcRecord(List<LinkedNpcRecord> records, UUID npcUuid) {
        return linkedNpcRecordStore.find(records, npcUuid);
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

    private boolean unlinkLoadedNpcFromTool(Player player, UUID npcUuid, String toolId) {
        if (player == null || npcUuid == null || toolId == null || toolId.isBlank()) {
            return false;
        }
        World world = player.getWorld();
        if (world == null) {
            return false;
        }
        Ref<EntityStore> npcRef = world.getEntityRef(npcUuid);
        if (npcRef == null || !npcRef.isValid()) {
            return false;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (store == null) {
            return false;
        }
        TameworkCommandLinksComponent links = store.getComponent(npcRef, TameworkCommandLinksComponent.getComponentType());
        if (links == null || !links.containsToolId(toolId)) {
            return false;
        }
        UUID owner = links.getOwnerId();
        if (owner != null && !owner.equals(player.getUuid())) {
            return false;
        }
        store.putComponent(npcRef, TameworkCommandLinksComponent.getComponentType(), links.withToolIdRemoved(toolId));
        return true;
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

    private List<Candidate> queryRecipients(Context context) {
        ArrayList<Candidate> out = new ArrayList<>();
        TransformComponent playerTransform = context.store.getComponent(context.playerRef, TransformComponent.getComponentType());
        Vector3d playerPos = playerTransform != null ? new Vector3d(playerTransform.getPosition()) : null;
        double radiusSq = context.config.getRadius() >= 0 ? context.config.getRadius() * context.config.getRadius() : -1;
        int maxTargets = Math.max(1, context.config.getMaxTargets());
        UUID playerUuid = context.player.getUuid();

        context.store.forEachChunk(Query.any(), (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
            for (int i = 0; i < chunk.size(); i++) {
                NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                if (npc == null) {
                    continue;
                }
                Ref<EntityStore> npcRef = chunk.getReferenceTo(i);
                if (npcRef == null || !npcRef.isValid()) {
                    continue;
                }
                if (!linkPolicyService.matchesMembership(
                        context.config.getMembershipMode(),
                        npcRef,
                        npc,
                        context.playerRef,
                        playerUuid,
                        context.toolId,
                        context.store
                )) {
                    continue;
                }
                if (!linkPolicyService.passesOwnerAndTamed(
                        context.config.isRequireOwner(),
                        context.config.isRequireTamed(),
                        npcRef,
                        playerUuid,
                        context.store
                )) {
                    continue;
                }
                if (!linkPolicyService.isRoleAllowed(linkPolicyService.resolveRoleId(npc), context.config)) {
                    continue;
                }
                TransformComponent npcTransform = chunk.getComponent(i, TransformComponent.getComponentType());
                double distSq = 0;
                if (playerPos != null && npcTransform != null) {
                    Vector3d p = npcTransform.getPosition();
                    double dx = p.x - playerPos.x;
                    double dy = p.y - playerPos.y;
                    double dz = p.z - playerPos.z;
                    distSq = dx * dx + dy * dy + dz * dz;
                    if (radiusSq >= 0 && distSq > radiusSq) {
                        continue;
                    }
                } else if (radiusSq >= 0) {
                    continue;
                }
                out.add(new Candidate(npcRef, npc, distSq));
            }
        });
        out.sort(Comparator.comparingDouble(value -> value.distSq));
        if (out.size() > maxTargets) {
            return new ArrayList<>(out.subList(0, maxTargets));
        }
        return out;
    }

    private List<LinkedNpcRecord> queryUnloadedLinkedRecords(Context context, List<Candidate> loadedRecipients) {
        MembershipMode mode = context.config.getMembershipMode() != null
                ? context.config.getMembershipMode()
                : MembershipMode.LinkedOnly;
        if (mode != MembershipMode.LinkedOnly && mode != MembershipMode.LinkedOrMasterTarget) {
            return List.of();
        }
        List<LinkedNpcRecord> linkedRecords = readLinkedNpcRecords(context.workingItem);
        if (linkedRecords.isEmpty()) {
            return List.of();
        }
        Set<UUID> loadedUuids = new HashSet<>();
        if (loadedRecipients != null) {
            for (Candidate recipient : loadedRecipients) {
                if (recipient == null || recipient.npc == null || recipient.npc.getUuid() == null) {
                    continue;
                }
                loadedUuids.add(recipient.npc.getUuid());
            }
        }
        int remaining = Math.max(0, Math.max(1, context.config.getMaxTargets()) - loadedUuids.size());
        if (remaining <= 0) {
            return List.of();
        }
        ArrayList<LinkedNpcRecord> unloaded = new ArrayList<>();
        World world = context.player != null ? context.player.getWorld() : null;
        if (world == null) {
            return List.of();
        }
        for (LinkedNpcRecord record : linkedRecords) {
            if (record == null || record.npcUuid == null || loadedUuids.contains(record.npcUuid)) {
                continue;
            }
            Ref<EntityStore> ref = world.getEntityRef(record.npcUuid);
            if (ref != null && ref.isValid()) {
                // A valid world ref does not guarantee the NPC is currently loaded in chunk store.
                // If the component is absent, treat this record as unloaded so relocation can be queued.
                NPCEntity npc = context.store.getComponent(ref, NPCEntity.getComponentType());
                if (npc != null) {
                    continue;
                }
            }
            unloaded.add(record);
            if (unloaded.size() >= remaining) {
                break;
            }
        }
        return unloaded;
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
        RelocationState postRelocationState = resolveRelocationState(context.command, returnHome, recall);
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
        CommandStep[] steps = context.command.getSteps();
        if (steps == null || steps.length == 0) {
            return executeModeMapping(context, candidate);
        }
        boolean applied = false;
        for (CommandStep step : steps) {
            if (step == null) {
                continue;
            }
            boolean ok = applyStep(step, context, candidate);
            if (ok) {
                applied = true;
                continue;
            }
            if (step.isOptional()) {
                continue;
            }
            FailurePolicy policy = step.getFailurePolicy() != null ? step.getFailurePolicy() : FailurePolicy.Continue;
            if (policy == FailurePolicy.AbortAll) {
                return new StepResult(applied, true);
            }
            if (policy == FailurePolicy.AbortCommandForNpc) {
                return new StepResult(applied, false);
            }
        }
        return new StepResult(applied, false);
    }

    private StepResult executeModeMapping(Context context, Candidate candidate) {
        ModeMapping mode = context.command.getModeMapping();
        if (mode == null || mode.getState() == null || mode.getState().isBlank()) {
            return new StepResult(false, false);
        }
        boolean ok = applyState(candidate.ref, candidate.npc, context.store, mode.getState(), mode.getSubState());
        return new StepResult(ok, false);
    }

    private boolean applyStep(CommandStep step, Context context, Candidate candidate) {
        if (step instanceof SetStateStep stateStep) {
            return applyState(candidate.ref, candidate.npc, context.store, stateStep.getState(), stateStep.getSubState());
        }
        if (step instanceof SetTargetStep targetStep) {
            return applySetTarget(targetStep, context, candidate);
        }
        if (step instanceof ClearTargetStep clearStep) {
            String slot = clearStep.getTargetSlot();
            if (slot == null || slot.isBlank()) {
                slot = MASTER_TARGET_SLOT;
            }
            Role role = candidate.npc.getRole();
            if (role == null || role.getMarkedEntitySupport() == null) {
                return false;
            }
            role.getMarkedEntitySupport().setMarkedEntity(slot, null);
            return true;
        }
        if (step instanceof ClearCombatStep clearCombatStep) {
            return applyClearCombat(clearCombatStep, context, candidate);
        }
        if (step instanceof MoveToPositionStep moveStep) {
            return applyMove(moveStep, context, candidate);
        }
        if (step instanceof StoreHomeStep storeHomeStep) {
            return applyStoreHome(storeHomeStep, context, candidate);
        }
        if (step instanceof TriggerHookStep hookStep) {
            return applyHook(hookStep.getHookId(), context, candidate.ref);
        }
        return false;
    }

    private boolean applyState(Ref<EntityStore> npcRef,
                               NPCEntity npc,
                               Store<EntityStore> store,
                               String state,
                               String subState) {
        if (npc == null || npc.getRole() == null || npc.getRole().getStateSupport() == null) {
            return false;
        }
        if (state == null || state.isBlank()) {
            return false;
        }
        StateSupport support = npc.getRole().getStateSupport();
        String resolvedSub = subState;
        if (support.getStateHelper() != null) {
            int stateIndex = support.getStateHelper().getStateIndex(state);
            if (stateIndex == StateSupport.NO_STATE) {
                return false;
            }
            if (resolvedSub == null || resolvedSub.isBlank()) {
                resolvedSub = support.getStateHelper().getDefaultSubState();
            } else if (support.getStateHelper().getSubStateIndex(stateIndex, resolvedSub) == StateSupport.NO_STATE) {
                return false;
            }
        }
        support.setState(npcRef, state, resolvedSub == null ? "" : resolvedSub, store);
        return true;
    }

    private boolean applySetTarget(SetTargetStep targetStep, Context context, Candidate candidate) {
        Role role = candidate.npc.getRole();
        if (role == null || role.getMarkedEntitySupport() == null) {
            return false;
        }
        String slot = targetStep.getTargetSlot();
        if (slot == null || slot.isBlank()) {
            slot = MASTER_TARGET_SLOT;
        }
        TargetSource source = targetStep.getSource() != null ? targetStep.getSource() : TargetSource.CrosshairTarget;
        Ref<EntityStore> target = switch (source) {
            case OwnerPlayer -> context.playerRef;
            case StoredTarget -> readMarkedEntity(role, slot);
            case LastAttackTarget, CrosshairTarget -> context.commandTarget;
        };
        if (target == null || !target.isValid()) {
            return false;
        }
        if ((source == TargetSource.CrosshairTarget || source == TargetSource.LastAttackTarget)
                && !isHostileTargetAllowed(target, context, candidate)) {
            return false;
        }
        role.getMarkedEntitySupport().setMarkedEntity(slot, target);
        return true;
    }

    private boolean isHostileTargetAllowed(Ref<EntityStore> target, Context context, Candidate candidate) {
        if (target == null || !target.isValid()) {
            return false;
        }
        UUID commanderId = context.player != null ? context.player.getUuid() : null;
        TameworkOwnerComponent targetOwner = context.store.getComponent(target, TameworkOwnerComponent.getComponentType());
        UUID targetOwnerId = targetOwner != null ? targetOwner.getOwnerId() : null;
        boolean targetOwnedByCommander = commanderId != null && targetOwnerId != null && commanderId.equals(targetOwnerId);
        Player targetPlayer = context.store.getComponent(target, Player.getComponentType());
        boolean targetIsPlayer = targetPlayer != null;
        boolean playerTargetingAllowed = !targetIsPlayer || isPlayerTargetAllowed(context.player.getWorld());
        boolean targetPlayerSpawnProtected = targetIsPlayer && targetPlayer.hasSpawnProtection();
        return CommandTargetPermission.isAllowed(
                target.equals(context.playerRef),
                target.equals(candidate.ref),
                targetOwnedByCommander,
                targetIsPlayer,
                playerTargetingAllowed,
                targetPlayerSpawnProtected,
                targetOwnerId != null,
                context.blockAllPlayerDamageIfOwned,
                context.invulnerableIfOwned
        );
    }

    private boolean isPlayerTargetAllowed(World world) {
        if (world == null || world.getWorldConfig() == null) {
            return true;
        }
        return world.getWorldConfig().isPvpEnabled();
    }

    @SuppressWarnings("unchecked")
    private Ref<EntityStore> readMarkedEntity(Role role, String slot) {
        if (role == null || role.getMarkedEntitySupport() == null) {
            return null;
        }
        try {
            Method method = role.getMarkedEntitySupport().getClass().getMethod("getMarkedEntity", String.class);
            Object value = method.invoke(role.getMarkedEntitySupport(), slot);
            if (value instanceof Ref<?>) {
                return (Ref<EntityStore>) value;
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private boolean applyMove(MoveToPositionStep moveStep, Context context, Candidate candidate) {
        MoveSource source = moveStep.getSource() != null ? moveStep.getSource() : MoveSource.RaycastHit;
        Vector3d targetPosition = null;
        if (source == MoveSource.RaycastHit) {
            targetPosition = context.raycastPosition;
            if (targetPosition == null) {
                return false;
            }
        } else if (source == MoveSource.StoredHome) {
            targetPosition = readStoredHomePosition(candidate.ref, context.store);
            if (targetPosition == null) {
                return false;
            }
        } else if (source == MoveSource.OwnerPosition && candidate.npc.getRole() != null
                && candidate.npc.getRole().getMarkedEntitySupport() != null
                && context.playerRef != null
                && context.playerRef.isValid()) {
            candidate.npc.getRole().getMarkedEntitySupport().setMarkedEntity(MASTER_TARGET_SLOT, context.playerRef);
        }
        if (source == MoveSource.StoredHome && targetPosition != null) {
            TransformComponent npcTransform = context.store.getComponent(candidate.ref, TransformComponent.getComponentType());
            Vector3d start = npcTransform != null ? new Vector3d(npcTransform.getPosition()) : null;
            if (start != null && start.distanceTo(targetPosition) > context.returnHomeTeleportDistance) {
                Vector3d intermediate = computeIntermediatePoint(start, targetPosition, context.returnHomePathDistanceBeforeTeleport);
                RelocationState postRelocationState = resolveRelocationState(context.command, true, false);
                World world = context.player != null ? context.player.getWorld() : null;
                UUID ownerUuid = context.player != null ? context.player.getUuid() : null;
                UUID npcUuid = candidate.npc != null ? candidate.npc.getUuid() : null;
                if (world != null && npcUuid != null && relocationService != null) {
                    relocationService.queueRelocation(
                            world,
                            npcUuid,
                            targetPosition,
                            ownerUuid,
                            false,
                            true,
                            postRelocationState.state,
                            postRelocationState.subState,
                            context.returnHomeTeleportDelayMs,
                            start,
                            targetPosition
                    );
                }
                return applyHook("Tamework.Command.MoveToPosition." + source.name(), context, candidate.ref, intermediate);
            }
        }
        return applyHook("Tamework.Command.MoveToPosition." + source.name(), context, candidate.ref, targetPosition);
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

    private boolean applyStoreHome(StoreHomeStep step, Context context, Candidate candidate) {
        if (step == null || context == null || candidate == null || candidate.ref == null || candidate.npc == null) {
            return false;
        }
        Vector3d home = null;
        StoreSource source = step.getSource() != null ? step.getSource() : StoreSource.RaycastHit;
        if (source == StoreSource.RaycastHit) {
            home = context.raycastPosition;
        } else if (source == StoreSource.OwnerPosition) {
            TransformComponent playerTransform = context.store.getComponent(context.playerRef, TransformComponent.getComponentType());
            if (playerTransform != null) {
                home = new Vector3d(playerTransform.getPosition());
            }
        }
        if (home == null) {
            return false;
        }
        TameworkCommandLinksComponent links = context.store.getComponent(
                candidate.ref,
                TameworkCommandLinksComponent.getComponentType()
        );
        if (links == null) {
            links = new TameworkCommandLinksComponent();
        }
        if (links.getOwnerId() == null && context.player != null && context.player.getUuid() != null) {
            links.setOwnerId(context.player.getUuid());
        }
        links.setHomePosition(home);
        context.store.putComponent(candidate.ref, TameworkCommandLinksComponent.getComponentType(), links);

        if (context.workingItem != null && !context.workingItem.isEmpty() && candidate.npc.getUuid() != null) {
            TransformComponent transform = context.store.getComponent(candidate.ref, TransformComponent.getComponentType());
            Vector3d currentPosition = transform != null ? new Vector3d(transform.getPosition()) : null;
            ItemStack updated = upsertLinkedNpcRecord(
                    context.workingItem,
                    candidate.npc.getUuid(),
                    currentPosition,
                    home,
                    npcNameResolver.resolveNpcDisplayNameFromComponents(candidate.ref, context.store),
                    npcNameResolver.resolveNpcNameKey(candidate.npc),
                    npcNameResolver.resolveNpcRoleId(candidate.npc)
            );
            if (updated != context.workingItem) {
                context.workingItem = updated;
                context.itemChanged = true;
            }
        }
        return true;
    }

    private boolean applyClearCombat(ClearCombatStep step, Context context, Candidate candidate) {
        if (step == null || candidate == null || candidate.npc == null) {
            return false;
        }
        boolean applied = false;
        Role role = candidate.npc.getRole();
        if (role != null && role.getMarkedEntitySupport() != null) {
            String[] slots = step.getTargetSlots();
            if (slots == null || slots.length == 0) {
                slots = new String[] { "LockedTarget" };
            }
            for (String slot : slots) {
                if (slot == null || slot.isBlank()) {
                    continue;
                }
                role.getMarkedEntitySupport().setMarkedEntity(slot, null);
                applied = true;
            }
            if (step.isAssignOwnerAsMasterTarget()
                    && context.playerRef != null
                    && context.playerRef.isValid()) {
                role.getMarkedEntitySupport().setMarkedEntity(MASTER_TARGET_SLOT, context.playerRef);
                applied = true;
            }
        }
        String state = step.getState();
        if (state == null || state.isBlank()) {
            return applied;
        }
        boolean stateApplied = applyState(candidate.ref, candidate.npc, context.store, state, step.getSubState());
        return applied || stateApplied;
    }

    private boolean applyHook(String hookId, Context context, Ref<EntityStore> npcRef) {
        return applyHook(hookId, context, npcRef, null);
    }

    private boolean applyHook(String hookId,
                              Context context,
                              Ref<EntityStore> npcRef,
                              Vector3d targetPosition) {
        if (hookId == null || hookId.isBlank() || npcRef == null || !npcRef.isValid()) {
            return false;
        }
        UUID playerId = context.player.getUuid();
        String playerName = context.player.getPlayerRef() != null ? context.player.getPlayerRef().getUsername() : null;
        context.store.putComponent(
                npcRef,
                TameworkHookComponent.getComponentType(),
                new TameworkHookComponent(
                        hookId,
                        playerId,
                        playerName,
                        context.itemId,
                        System.currentTimeMillis(),
                        true,
                        targetPosition
                )
        );
        return true;
    }

    private LinkToggleResult tryToggleLink(Player player,
                                           Store<EntityStore> store,
                                           Ref<EntityStore> targetRef,
                                           String toolId,
                                           TwCommandItemConfig config,
                                           ItemStack workingItem) {
        NPCEntity npc = store.getComponent(targetRef, NPCEntity.getComponentType());
        if (npc == null) {
            return LinkToggleResult.notToggled();
        }
        UUID playerId = player.getUuid();
        if (playerId == null) {
            return LinkToggleResult.notToggled();
        }
        UUID ownerId = linkPolicyService.resolveOwnerId(targetRef, store);
        if (ownerId != null && !ownerId.equals(playerId)) {
            return LinkToggleResult.notToggled();
        }
        if (config.isRequireOwner() && ownerId == null) {
            return LinkToggleResult.notToggled();
        }
        if (config.isRequireTamed() && !TamedStateResolver.isTamed(targetRef, store)) {
            return LinkToggleResult.notToggled();
        }
        if (!linkPolicyService.isRoleAllowed(linkPolicyService.resolveRoleId(npc), config)) {
            return LinkToggleResult.notToggled();
        }
        TameworkCommandLinksComponent current = store.getComponent(targetRef, TameworkCommandLinksComponent.getComponentType());
        if (current == null) {
            current = new TameworkCommandLinksComponent(playerId, new String[0]);
        }
        UUID linksOwner = current.getOwnerId();
        if (linksOwner != null && !linksOwner.equals(playerId)) {
            return LinkToggleResult.notToggled();
        }
        current.setOwnerId(playerId);
        boolean linked;
        TameworkCommandLinksComponent updated;
        if (current.containsToolId(toolId)) {
            updated = current.withToolIdRemoved(toolId);
            linked = false;
        } else {
            updated = current.withToolIdAdded(toolId);
            linked = true;
        }
        store.putComponent(targetRef, TameworkCommandLinksComponent.getComponentType(), updated);
        ItemStack updatedItem = workingItem;
        UUID npcUuid = npc.getUuid();
        if (npcUuid != null && updatedItem != null && !updatedItem.isEmpty()) {
            if (linked) {
                TransformComponent transform = store.getComponent(targetRef, TransformComponent.getComponentType());
                Vector3d lastKnown = transform != null ? new Vector3d(transform.getPosition()) : null;
                Vector3d homePosition = updated != null && updated.hasHome() ? updated.getHomePosition() : null;
                updatedItem = upsertLinkedNpcRecord(
                        updatedItem,
                        npcUuid,
                        lastKnown,
                        homePosition,
                        npcNameResolver.resolveNpcDisplayNameFromComponents(targetRef, store),
                        npcNameResolver.resolveNpcNameKey(npc),
                        npcNameResolver.resolveNpcRoleId(npc)
                );
            } else {
                updatedItem = removeLinkedNpcRecord(updatedItem, npcUuid);
            }
        }
        String name = npcNameResolver.resolveNpcDisplayName(targetRef, store, npc);
        return new LinkToggleResult(true, linked, name, updatedItem);
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

    private Vector3d computeIntermediatePoint(Vector3d from, Vector3d to, double distance) {
        if (from == null || to == null) {
            return to;
        }
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length <= distance || length <= 0.001) {
            return new Vector3d(to);
        }
        double scale = distance / length;
        return new Vector3d(
                from.x + dx * scale,
                from.y + dy * scale,
                from.z + dz * scale
        );
    }

    private RelocationState resolveRelocationState(CommandEntry command, boolean returnHome, boolean recall) {
        String state = null;
        String subState = null;
        if (command != null && command.getSteps() != null) {
            for (CommandStep step : command.getSteps()) {
                if (step instanceof SetStateStep setStateStep) {
                    if (setStateStep.getState() == null || setStateStep.getState().isBlank()) {
                        continue;
                    }
                    state = setStateStep.getState();
                    subState = setStateStep.getSubState();
                    continue;
                }
                if (step instanceof ClearCombatStep clearCombatStep) {
                    if (clearCombatStep.getState() == null || clearCombatStep.getState().isBlank()) {
                        continue;
                    }
                    state = clearCombatStep.getState();
                    subState = clearCombatStep.getSubState();
                }
            }
        }
        if ((state == null || state.isBlank()) && returnHome) {
            state = "Hold";
        }
        if ((state == null || state.isBlank()) && recall) {
            state = "Idle";
        }
        return new RelocationState(state, subState);
    }

    private ItemStack upsertLinkedNpcRecord(ItemStack stack, UUID npcUuid, Vector3d position, Vector3d homePosition) {
        return linkedNpcRecordStore.upsert(stack, npcUuid, position, homePosition, null, null, null);
    }

    private ItemStack upsertLinkedNpcRecord(ItemStack stack,
                                            UUID npcUuid,
                                            Vector3d position,
                                            Vector3d homePosition,
                                            String cachedDisplayName,
                                            String cachedNameKey,
                                            String cachedRoleId) {
        return linkedNpcRecordStore.upsert(
                stack,
                npcUuid,
                position,
                homePosition,
                cachedDisplayName,
                cachedNameKey,
                cachedRoleId
        );
    }

    private ItemStack refreshLinkedNpcPositions(ItemStack stack, List<Candidate> recipients, Store<EntityStore> store) {
        if (stack == null || stack.isEmpty() || recipients == null || recipients.isEmpty() || store == null) {
            return stack;
        }
        ItemStack updated = stack;
        for (Candidate candidate : recipients) {
            if (candidate == null || candidate.ref == null || candidate.npc == null || candidate.npc.getUuid() == null) {
                continue;
            }
            TransformComponent transform = store.getComponent(candidate.ref, TransformComponent.getComponentType());
            Vector3d position = transform != null ? new Vector3d(transform.getPosition()) : null;
            Vector3d homePosition = readStoredHomePosition(candidate.ref, store);
            updated = upsertLinkedNpcRecord(
                    updated,
                    candidate.npc.getUuid(),
                    position,
                    homePosition,
                    npcNameResolver.resolveNpcDisplayNameFromComponents(candidate.ref, store),
                    npcNameResolver.resolveNpcNameKey(candidate.npc),
                    npcNameResolver.resolveNpcRoleId(candidate.npc)
            );
        }
        return updated;
    }

    private ItemStack removeLinkedNpcRecord(ItemStack stack, UUID npcUuid) {
        return linkedNpcRecordStore.remove(stack, npcUuid);
    }

    private List<LinkedNpcRecord> readLinkedNpcRecords(ItemStack stack) {
        return linkedNpcRecordStore.read(stack);
    }

    private ItemStack writeLinkedNpcRecords(ItemStack stack, List<LinkedNpcRecord> records) {
        return linkedNpcRecordStore.write(stack, records);
    }

    private void emitCommandExecutionFeedback(Context context, int affected, int queued) {
        if (context == null) {
            return;
        }
        feedbackService.emitCommandExecutionFeedback(
                context.player,
                context.playerRef,
                context.store,
                context.command,
                affected,
                queued,
                this::resolveCommandLabel
        );
    }

    private boolean isCooldownActive(ItemStack stack, int cooldownMs) {
        if (cooldownMs <= 0) {
            return false;
        }
        Long until = stack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_COOLDOWN_UNTIL, Codec.LONG);
        return until != null && until > System.currentTimeMillis();
    }

    private void sendDefaultMessage(Player player, String text) {
        feedbackService.showDefault(player, text);
    }

    private void sendSuccessMessage(Player player, String text) {
        feedbackService.showSuccess(player, text);
    }

    private void sendWarningMessage(Player player, String text) {
        feedbackService.showWarning(player, text);
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
