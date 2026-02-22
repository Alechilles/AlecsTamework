package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.CommandFeedback;
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
import com.alechilles.alecstamework.localization.TranslationRegistry;
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
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.collision.WorldUtil;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.modules.entity.component.DisplayNameComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
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
    private static final String LINK_RECORD_SEPARATOR = "\n";
    private static final String LINK_RECORD_PARTS_SEPARATOR = "\\|";
    private static final long RESPAWN_FOLLOW_RETRY_DELAY_MS = 1250L;
    private static final double RESPAWN_DISTANCE_CLOSE = 5.0;
    private static final double RESPAWN_DISTANCE_NEAR = 8.0;
    private static final double RESPAWN_DISTANCE_MID = 12.0;
    private static final double RESPAWN_DISTANCE_FAR = 16.0;
    private static final double OUT_OF_VIEW_MIN_ANGLE_DEGREES = 70.0;
    private static final double[] PLACEMENT_ANGLE_OFFSETS = {
            180.0, 165.0, -165.0, 150.0, -150.0, 135.0, -135.0, 120.0, -120.0, 105.0, -105.0, 90.0, -90.0,
            75.0, -75.0, 60.0, -60.0, 45.0, -45.0, 30.0, -30.0, 15.0, -15.0, 0.0
    };
    private static final double COMMAND_PLACEMENT_MIN_RELATIVE_Y = -2.0;
    private static final double COMMAND_PLACEMENT_MAX_RELATIVE_Y = 4.0;

    private final CommandItemRegistry registry;
    private final CommandNpcRelocationService relocationService;
    private final CommandLinkedNpcDeathService deathService;
    private final CommandLinkedNpcCaptureService captureService;
    private final TameworkUiMessageService uiMessageService = new TameworkUiMessageService();

    public CommandItemFeatureHandler(CommandItemRegistry registry,
                                     CommandNpcRelocationService relocationService,
                                     CommandLinkedNpcDeathService deathService,
                                     CommandLinkedNpcCaptureService captureService) {
        this.registry = registry;
        this.relocationService = relocationService;
        this.deathService = deathService;
        this.captureService = captureService;
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

        TwCommandItemConfig config = resolveConfig(itemStack.getItemId(), configIdOverride);
        if (config == null || !config.isEnabled()) {
            return false;
        }

        ToolResolution tool = ensureToolId(itemStack);
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

        CommandEntry command = resolveCommand(config, commandIdOverride, working);
        if (command == null) {
            if (updateHeldItem) {
                updateHeldItem(player, working);
            }
            sendWarningMessage(player, "No command is configured for this item.");
            return false;
        }

        Ref<EntityStore> commandTarget = resolveCommandTarget(playerRef, store, config, command, targetRef);
        Vector3d raycastPosition = resolveRaycastPosition(playerRef, store, config, command);
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
                () -> buildLinkedPanelEntriesForTool(player, toolId),
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
        boolean updated = setSelectedCommandOnTool(player, toolId, selected.getId());
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
                    resolveNpcDisplayNameFromComponents(npcRef, store),
                    resolveNpcNameKey(npc),
                    resolveNpcRoleId(npc)
            );
            hotbar.setItemStackForSlot(slot, updatedStack);
            inventory.markChanged();
            player.sendInventory();
            sendSuccessMessage(player, "Set home for " + resolveNpcDisplayName(npcRef, store, npc) + ".");
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
            TwCommandItemConfig config = resolveConfig(stack.getItemId(), null);
            if (config == null || !config.isEnabled()) {
                sendWarningMessage(player, "That command item is not configured.");
                return;
            }
            CommandEntry panelCommand = returnHome
                    ? resolvePanelReturnHomeCommand(config, stack)
                    : resolvePanelRecallCommand(config, stack);
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
            Ref<EntityStore> commandTarget = resolveCommandTarget(playerRef, store, config, panelCommand, explicitTarget);
            Vector3d raycastPosition = resolveRaycastPosition(playerRef, store, config, panelCommand);
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
        Vector3d destination = computeSafeRespawnPosition(playerRef, store, safeSpawnDistance, sourceHint);
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
        String[] toolIds = mergeToolIds(deadSnapshot.toolIds(), toolId);
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
                resolveNpcDisplayNameFromComponents(spawnedRef, store),
                resolveNpcNameKey(spawnedNpc),
                resolveNpcRoleId(spawnedNpc)
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
        if (records == null || records.isEmpty() || npcUuid == null) {
            return null;
        }
        String key = npcUuid.toString().toLowerCase(Locale.ROOT);
        for (LinkedNpcRecord record : records) {
            if (record == null || record.npcUuid == null) {
                continue;
            }
            if (key.equals(record.npcUuid.toString().toLowerCase(Locale.ROOT))) {
                return record;
            }
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

    private List<TameworkCommandSelectionPage.LinkedNpcEntry> buildLinkedPanelEntriesForTool(Player player,
                                                                                              String toolId) {
        if (player == null || toolId == null || toolId.isBlank()) {
            return List.of();
        }
        Inventory inventory = player.getInventory();
        if (inventory == null || inventory.getHotbar() == null) {
            return List.of();
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
            World world = player.getWorld();
            if (world == null) {
                return List.of();
            }
            Store<EntityStore> store = world.getEntityStore().getStore();
            if (store == null) {
                return List.of();
            }
            return buildLinkedPanelEntries(player, store, stack, toolId);
        }
        return List.of();
    }

    private List<TameworkCommandSelectionPage.LinkedNpcEntry> buildLinkedPanelEntries(Player player,
                                                                                       Store<EntityStore> store,
                                                                                       ItemStack stack,
                                                                                       String toolId) {
        if (player == null || store == null || stack == null || stack.isEmpty()) {
            return List.of();
        }
        List<LinkedNpcRecord> records = readLinkedNpcRecords(stack);
        if (records.isEmpty()) {
            return List.of();
        }
        TwGlobalConfig globalConfig = TwGlobalConfig.resolveActive();
        boolean deadRespawnEnabled = globalConfig != null && globalConfig.isCommandDeadRespawnEnabled();
        World world = player.getWorld();
        ArrayList<TameworkCommandSelectionPage.LinkedNpcEntry> entries = new ArrayList<>(records.size());
        for (LinkedNpcRecord record : records) {
            if (record == null || record.npcUuid == null) {
                continue;
            }
            boolean loaded = false;
            boolean dead = false;
            boolean captured = false;
            long deadRespawnRemainingMs = 0L;
            boolean hasHome = record.homePosition != null;
            String displayName = resolveCachedUnloadedDisplayName(record);
            if (displayName == null || displayName.isBlank()) {
                displayName = "Unloaded companion (" + abbreviateUuid(record.npcUuid) + ")";
            }
            int health = 0;
            int maxHealth = 0;
            if (world != null) {
                Ref<EntityStore> npcRef = world.getEntityRef(record.npcUuid);
                if (npcRef != null && npcRef.isValid()) {
                    NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
                    if (npc != null) {
                        loaded = true;
                        displayName = resolveNpcDisplayName(npcRef, store, npc);
                        TameworkCommandLinksComponent links =
                                store.getComponent(npcRef, TameworkCommandLinksComponent.getComponentType());
                        if (links != null && links.hasHome()) {
                            hasHome = true;
                        }
                        HealthSnapshot snapshot = readNpcHealthSnapshot(npcRef, store);
                        if (snapshot != null) {
                            health = snapshot.current;
                            maxHealth = snapshot.max;
                        }
                    }
                }
            }
            if (!loaded && deathService != null) {
                CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot deadSnapshot = deathService.getDeadSnapshotForTool(
                        record.npcUuid,
                        toolId,
                        player.getUuid()
                );
                if (deadSnapshot != null) {
                    dead = true;
                    String deadName = deadSnapshot.displayName();
                    if (deadName != null && !deadName.isBlank()) {
                        displayName = deadName;
                    }
                    if (deadRespawnEnabled) {
                        deadRespawnRemainingMs = Math.max(0L, deadSnapshot.respawnAvailableAtMs() - System.currentTimeMillis());
                    } else {
                        deadRespawnRemainingMs = -1L;
                    }
                }
            }
            if (!loaded && !dead && captureService != null) {
                CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot capturedSnapshot =
                        captureService.getCapturedSnapshotForTool(record.npcUuid, toolId, player.getUuid());
                if (capturedSnapshot != null) {
                    captured = true;
                    String capturedName = capturedSnapshot.displayName();
                    if (capturedName != null && !capturedName.isBlank()) {
                        displayName = capturedName;
                    }
                }
            }
            entries.add(new TameworkCommandSelectionPage.LinkedNpcEntry(
                    record.npcUuid,
                    displayName,
                    health,
                    maxHealth,
                    loaded,
                    hasHome,
                    dead,
                    captured,
                    deadRespawnRemainingMs
            ));
        }
        return entries;
    }

    private String abbreviateUuid(UUID uuid) {
        if (uuid == null) {
            return "unknown";
        }
        String raw = uuid.toString();
        return raw.length() >= 8 ? raw.substring(0, 8) : raw;
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

    private boolean setSelectedCommandOnTool(Player player, String toolId, String commandId) {
        Inventory inventory = player != null ? player.getInventory() : null;
        if (inventory == null) {
            return false;
        }
        ItemContainer hotbar = inventory.getHotbar();
        if (hotbar == null) {
            return false;
        }
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
            ItemStack updated = stack.withMetadata(TameworkMetadataKeys.COMMAND_SELECTED_ID, Codec.STRING, commandId);
            hotbar.setItemStackForSlot(slot, updated);
            inventory.markChanged();
            player.sendInventory();
            return true;
        }
        return false;
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

    private String resolveNpcDisplayName(Ref<EntityStore> npcRef, Store<EntityStore> store, NPCEntity npc) {
        if (npc == null) {
            return "NPC";
        }
        String componentDisplayName = resolveNpcDisplayNameFromComponents(npcRef, store);
        if (componentDisplayName != null && !componentDisplayName.isBlank()) {
            return componentDisplayName;
        }
        String displayName = npc.getLegacyDisplayName();
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        String nameKey = resolveNpcNameKey(npc);
        if (nameKey != null && !nameKey.isBlank()) {
            String translated = translateNpcNameKey(nameKey);
            if (translated != null && !translated.isBlank()) {
                return translated;
            }
        }
        String roleId = resolveNpcRoleId(npc);
        if (roleId != null && !roleId.isBlank()) {
            return roleId;
        }
        return "NPC";
    }

    private String resolveCachedUnloadedDisplayName(LinkedNpcRecord record) {
        if (record == null) {
            return null;
        }
        if (record.cachedDisplayName != null && !record.cachedDisplayName.isBlank()) {
            return record.cachedDisplayName;
        }
        if (record.cachedNameKey != null && !record.cachedNameKey.isBlank()) {
            String translated = translateNpcNameKey(record.cachedNameKey);
            if (translated != null && !translated.isBlank()) {
                return translated;
            }
            return record.cachedNameKey;
        }
        if (record.cachedRoleId != null && !record.cachedRoleId.isBlank()) {
            return record.cachedRoleId;
        }
        return null;
    }

    private String resolveNpcDisplayNameFromComponents(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        ComponentType<EntityStore, TameworkNpcNameComponent> nameType = TameworkNpcNameComponent.getComponentType();
        if (nameType != null) {
            TameworkNpcNameComponent nameComponent = store.getComponent(npcRef, nameType);
            if (nameComponent != null && nameComponent.getName() != null && !nameComponent.getName().isBlank()) {
                return nameComponent.getName();
            }
        }
        DisplayNameComponent displayName = store.getComponent(npcRef, DisplayNameComponent.getComponentType());
        if (displayName != null && displayName.getDisplayName() != null) {
            String ansi = displayName.getDisplayName().getAnsiMessage();
            if (ansi != null && !ansi.isBlank()) {
                return ansi;
            }
        }
        return null;
    }

    private String resolveNpcNameKey(NPCEntity npc) {
        if (npc == null) {
            return null;
        }
        String roleParamNameKey = resolveRoleNameKeyFromParams(npc.getRole());
        if (roleParamNameKey != null && !roleParamNameKey.isBlank()) {
            return roleParamNameKey;
        }
        String nameKey = readStringGetter(
                npc,
                "getRoleNameKey",
                "getNpcNameKey",
                "getNameKey"
        );
        if (nameKey != null && !nameKey.isBlank()) {
            return nameKey;
        }
        int roleIndex = npc.getRoleIndex();
        if (roleIndex >= 0) {
            NPCPlugin plugin = NPCPlugin.get();
            if (plugin != null) {
                String indexedNameKey = plugin.getName(roleIndex);
                if (looksLikeTranslationKey(indexedNameKey)) {
                    return indexedNameKey;
                }
            }
        }
        return null;
    }

    private String resolveRoleNameKeyFromParams(Role role) {
        if (role == null) {
            return null;
        }
        String direct = readStringGetter(
                role,
                "getRoleNameKey",
                "getNpcNameKey",
                "getNameKey",
                "getNameTranslationKey"
        );
        if (direct != null && !direct.isBlank()) {
            return direct;
        }
        Object entitySupport = invokeObjectGetter(role, "getEntitySupport");
        Object sensorScope = invokeObjectGetter(entitySupport, "getSensorScope");
        return readScopeStringParam(
                sensorScope,
                "NameTranslationKey",
                "RoleNameTranslationKey",
                "NameKey",
                "RoleNameKey",
                "NpcNameKey"
        );
    }

    private static boolean looksLikeTranslationKey(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.indexOf('.') >= 0;
    }

    private String translateNpcNameKey(String nameKey) {
        if (nameKey == null || nameKey.isBlank()) {
            return null;
        }
        Tamework instance = Tamework.getInstance();
        TranslationRegistry registry = instance != null ? instance.getTranslationRegistry() : null;
        if (registry == null) {
            return null;
        }
        for (String candidate : buildNameKeyCandidates(nameKey)) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String translated = registry.get(candidate);
            if (translated != null && !translated.isBlank()) {
                return translated;
            }
        }
        return null;
    }

    private List<String> buildNameKeyCandidates(String nameKey) {
        ArrayList<String> candidates = new ArrayList<>(8);
        addCandidate(candidates, nameKey);
        if (!nameKey.contains(".")) {
            addCandidate(candidates, "npcRoles." + nameKey + ".name");
            addCandidate(candidates, "server.npcRoles." + nameKey + ".name");
            return candidates;
        }
        if (nameKey.startsWith("server.")) {
            addCandidate(candidates, nameKey.substring("server.".length()));
        } else {
            addCandidate(candidates, "server." + nameKey);
        }
        addCandidate(candidates, nameKey.replace(".npcRole.", ".npcRoles."));
        addCandidate(candidates, nameKey.replace(".npcRoles.", ".npcRole."));
        if (nameKey.startsWith("npcRoles.")) {
            addCandidate(candidates, "server." + nameKey);
        }
        if (nameKey.startsWith("server.npcRoles.")) {
            addCandidate(candidates, nameKey.substring("server.".length()));
        }
        return candidates;
    }

    private void addCandidate(List<String> candidates, String key) {
        if (key == null || key.isBlank() || candidates.contains(key)) {
            return;
        }
        candidates.add(key);
    }

    private static String readScopeStringParam(Object scope, String... paramNames) {
        if (scope == null || paramNames == null) {
            return null;
        }
        for (String paramName : paramNames) {
            if (paramName == null || paramName.isBlank()) {
                continue;
            }
            try {
                Method supplierMethod = scope.getClass().getMethod("getStringSupplier", String.class);
                Object supplierObj = supplierMethod.invoke(scope, paramName);
                if (!(supplierObj instanceof Supplier<?> supplier)) {
                    continue;
                }
                Object value = supplier.get();
                if (value instanceof String stringValue && !stringValue.isBlank()) {
                    return stringValue;
                }
            } catch (Exception | LinkageError ignored) {
                // Continue trying alternate parameter names and compatibility paths.
            }
        }
        return null;
    }

    private String resolveNpcRoleId(NPCEntity npc) {
        if (npc == null) {
            return null;
        }
        String roleName = npc.getRoleName();
        if (roleName != null && !roleName.isBlank()) {
            return roleName;
        }
        return readStringGetter(
                npc,
                "getRoleId",
                "getRoleKey"
        );
    }

    private static String readStringGetter(Object target, String... methodNames) {
        if (target == null || methodNames == null) {
            return null;
        }
        for (String methodName : methodNames) {
            String value = invokeStringGetter(target, methodName);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String invokeStringGetter(Object target, String methodName) {
        if (target == null || methodName == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            Object value = method.invoke(target);
            return value instanceof String ? (String) value : null;
        } catch (Exception | LinkageError ex) {
            return null;
        }
    }

    private static Object invokeObjectGetter(Object target, String methodName) {
        if (target == null || methodName == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Exception | LinkageError ex) {
            return null;
        }
    }

    private HealthSnapshot readNpcHealthSnapshot(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        ComponentType<EntityStore, EntityStatMap> statType = EntityStatMap.getComponentType();
        if (statType == null) {
            return null;
        }
        EntityStatMap statMap = store.getComponent(npcRef, statType);
        if (statMap == null) {
            return null;
        }
        int healthIndex = EntityStatType.getAssetMap().getIndex("Health");
        if (healthIndex < 0) {
            return null;
        }
        EntityStatValue health = statMap.get(healthIndex);
        if (health == null) {
            return null;
        }
        int current = Math.max(0, Math.round(health.get()));
        int max = Math.max(1, Math.round(health.getMax()));
        if (current > max) {
            current = max;
        }
        return new HealthSnapshot(current, max);
    }

    private TwCommandItemConfig resolveConfig(String itemId, String configIdOverride) {
        if (configIdOverride != null && !configIdOverride.isBlank()
                && TwCommandItemConfig.getAssetMap() != null) {
            TwCommandItemConfig override = TwCommandItemConfig.getAssetMap().getAsset(configIdOverride);
            if (override != null) {
                return override;
            }
        }
        if (registry == null || itemId == null || itemId.isBlank()) {
            return null;
        }
        return registry.get(itemId);
    }

    private ToolResolution ensureToolId(ItemStack itemStack) {
        String toolId = itemStack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_TOOL_ID, Codec.STRING);
        if (toolId != null && !toolId.isBlank()) {
            return new ToolResolution(itemStack, toolId, false);
        }
        String generated = UUID.randomUUID().toString();
        return new ToolResolution(
                itemStack.withMetadata(TameworkMetadataKeys.COMMAND_TOOL_ID, Codec.STRING, generated),
                generated,
                true
        );
    }

    private CommandEntry resolveCommand(TwCommandItemConfig config, String commandIdOverride, ItemStack itemStack) {
        if (config == null) {
            return null;
        }
        CommandEntry direct = config.findCommandById(commandIdOverride);
        if (direct != null) {
            return direct;
        }
        if (itemStack != null) {
            String selectedId = itemStack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_SELECTED_ID, Codec.STRING);
            CommandEntry selected = config.findCommandById(selectedId);
            if (selected != null) {
                return selected;
            }
        }
        return config.findDefaultCommand();
    }

    private CommandEntry resolvePanelRecallCommand(TwCommandItemConfig config, ItemStack itemStack) {
        if (config == null) {
            return null;
        }
        CommandEntry direct = config.findCommandById("Recall");
        if (direct != null) {
            return direct;
        }
        CommandEntry[] commands = config.getCommandList();
        if (commands != null) {
            for (CommandEntry entry : commands) {
                if (isRecallCommand(entry)) {
                    return entry;
                }
            }
        }
        CommandEntry fallback = resolveCommand(config, null, itemStack);
        return isRecallCommand(fallback) ? fallback : null;
    }

    private CommandEntry resolvePanelReturnHomeCommand(TwCommandItemConfig config, ItemStack itemStack) {
        if (config == null) {
            return null;
        }
        CommandEntry direct = config.findCommandById("ReturnHome");
        if (direct != null) {
            return direct;
        }
        CommandEntry[] commands = config.getCommandList();
        if (commands != null) {
            for (CommandEntry entry : commands) {
                if (isReturnHomeCommand(entry)) {
                    return entry;
                }
            }
        }
        CommandEntry fallback = resolveCommand(config, null, itemStack);
        return isReturnHomeCommand(fallback) ? fallback : null;
    }

    private Ref<EntityStore> resolveCommandTarget(Ref<EntityStore> playerRef,
                                                  Store<EntityStore> store,
                                                  TwCommandItemConfig config,
                                                  CommandEntry command,
                                                  Ref<EntityStore> explicitTarget) {
        if (playerRef == null || !playerRef.isValid() || store == null) {
            return null;
        }
        if (explicitTarget != null && explicitTarget.isValid() && !explicitTarget.equals(playerRef)) {
            return explicitTarget;
        }
        if (!needsEntityTarget(command)) {
            return null;
        }
        float radius = resolveTargetEntityRadius(config);
        Ref<EntityStore> raycastTarget = TargetUtil.getTargetEntity(playerRef, radius, store);
        if (raycastTarget == null || !raycastTarget.isValid() || raycastTarget.equals(playerRef)) {
            return null;
        }
        return raycastTarget;
    }

    private boolean needsEntityTarget(CommandEntry command) {
        if (command == null || command.getSteps() == null) {
            return false;
        }
        for (CommandStep step : command.getSteps()) {
            if (step instanceof SetTargetStep targetStep) {
                TargetSource source = targetStep.getSource() != null
                        ? targetStep.getSource()
                        : TargetSource.CrosshairTarget;
                if (source == TargetSource.CrosshairTarget || source == TargetSource.LastAttackTarget) {
                    return true;
                }
            }
        }
        return false;
    }

    private float resolveTargetEntityRadius(TwCommandItemConfig config) {
        double distance = config != null && config.getRadius() > 0 ? config.getRadius() : DEFAULT_RAYCAST_DISTANCE;
        distance = Math.max(1.0, Math.min(distance, Float.MAX_VALUE));
        return (float) distance;
    }

    private Vector3d resolveRaycastPosition(Ref<EntityStore> playerRef,
                                            Store<EntityStore> store,
                                            TwCommandItemConfig config,
                                            CommandEntry command) {
        if (!needsRaycast(command) || playerRef == null || !playerRef.isValid()) {
            return null;
        }
        double distance = config != null && config.getRadius() > 0 ? config.getRadius() : DEFAULT_RAYCAST_DISTANCE;
        return TargetUtil.getTargetLocation(playerRef, blockId -> blockId != 0, distance, store);
    }

    private boolean needsRaycast(CommandEntry command) {
        if (command == null || command.getSteps() == null) {
            return false;
        }
        for (CommandStep step : command.getSteps()) {
            if (step instanceof MoveToPositionStep moveStep && moveStep.getSource() == MoveSource.RaycastHit) {
                return true;
            }
            if (step instanceof StoreHomeStep storeHomeStep
                    && storeHomeStep.getSource() == StoreSource.RaycastHit) {
                return true;
            }
        }
        return false;
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
                if (!matchesMembership(context, npcRef, npc, playerUuid)) {
                    continue;
                }
                if (!passesOwnerAndTamed(context, npcRef, playerUuid)) {
                    continue;
                }
                if (!isRoleAllowed(resolveRoleId(npc), context.config)) {
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
        boolean returnHome = isReturnHomeCommand(context.command);
        boolean recall = isRecallCommand(context.command);
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
            Vector3d safeDestination = computeSafeRecallPosition(context, sourceHint);
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

    private String[] mergeToolIds(String[] existing, String requiredToolId) {
        Set<String> out = new HashSet<>();
        if (existing != null) {
            for (String value : existing) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                out.add(value);
            }
        }
        if (requiredToolId != null && !requiredToolId.isBlank()) {
            out.add(requiredToolId);
        }
        return out.toArray(new String[0]);
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

    private boolean matchesMembership(Context context, Ref<EntityStore> npcRef, NPCEntity npc, UUID playerUuid) {
        MembershipMode mode = context.config.getMembershipMode() != null
                ? context.config.getMembershipMode()
                : MembershipMode.LinkedOnly;
        boolean linked = isLinked(npcRef, playerUuid, context.toolId, context.store);
        boolean owner = isOwnedByPlayer(npcRef, playerUuid, context.store);
        boolean master = isMasterTargetedToPlayer(npc, context.playerRef);
        return switch (mode) {
            case OwnerScope -> owner;
            case MasterTarget -> master;
            case LinkedOrMasterTarget -> linked || master;
            case LinkedOnly -> linked;
        };
    }

    private boolean isLinked(Ref<EntityStore> npcRef, UUID playerUuid, String toolId, Store<EntityStore> store) {
        TameworkCommandLinksComponent links = store.getComponent(npcRef, TameworkCommandLinksComponent.getComponentType());
        if (links == null || toolId == null || toolId.isBlank()) {
            return false;
        }
        UUID ownerId = links.getOwnerId();
        if (ownerId != null && !ownerId.equals(playerUuid)) {
            return false;
        }
        return links.containsToolId(toolId);
    }

    private boolean isOwnedByPlayer(Ref<EntityStore> npcRef, UUID playerUuid, Store<EntityStore> store) {
        UUID ownerId = resolveOwnerId(npcRef, store);
        return ownerId != null && ownerId.equals(playerUuid);
    }

    private boolean passesOwnerAndTamed(Context context, Ref<EntityStore> npcRef, UUID playerUuid) {
        UUID ownerId = resolveOwnerId(npcRef, context.store);
        if (ownerId != null && !ownerId.equals(playerUuid)) {
            return false;
        }
        if (context.config.isRequireOwner() && ownerId == null) {
            return false;
        }
        if (context.config.isRequireTamed() && !TamedStateResolver.isTamed(npcRef, context.store)) {
            return false;
        }
        return true;
    }

    private UUID resolveOwnerId(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        TameworkOwnerComponent owner = store.getComponent(npcRef, TameworkOwnerComponent.getComponentType());
        return owner != null ? owner.getOwnerId() : null;
    }

    private boolean isMasterTargetedToPlayer(NPCEntity npc, Ref<EntityStore> playerRef) {
        if (npc == null || npc.getRole() == null || npc.getRole().getMarkedEntitySupport() == null) {
            return false;
        }
        try {
            Method method = npc.getRole().getMarkedEntitySupport().getClass().getMethod("getMarkedEntity", String.class);
            Object value = method.invoke(npc.getRole().getMarkedEntitySupport(), MASTER_TARGET_SLOT);
            if (!(value instanceof Ref<?> marked)) {
                return false;
            }
            return marked.isValid() && marked.equals(playerRef);
        } catch (Exception ignored) {
            return false;
        }
    }

    private String resolveRoleId(NPCEntity npc) {
        if (npc == null) {
            return null;
        }
        if (npc.getRoleName() != null && !npc.getRoleName().isBlank()) {
            return npc.getRoleName();
        }
        if (npc.getRoleIndex() >= 0 && NPCPlugin.get() != null) {
            return NPCPlugin.get().getName(npc.getRoleIndex());
        }
        return null;
    }

    private boolean isRoleAllowed(String roleId, TwCommandItemConfig config) {
        TwCommandItemConfig.AllowedRoles allowed = config.getAllowedRoles();
        if (allowed == null || allowed.getMode() == null) {
            return true;
        }
        return switch (allowed.getMode()) {
            case AllowAll -> true;
            case Allowlist -> contains(allowed.getAllowlist(), roleId);
            case Denylist -> !contains(allowed.getDenylist(), roleId);
        };
    }

    private boolean contains(String[] values, String expected) {
        if (values == null || values.length == 0 || expected == null || expected.isBlank()) {
            return false;
        }
        for (String value : values) {
            if (expected.equals(value)) {
                return true;
            }
        }
        return false;
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
                    resolveNpcDisplayNameFromComponents(candidate.ref, context.store),
                    resolveNpcNameKey(candidate.npc),
                    resolveNpcRoleId(candidate.npc)
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
        UUID ownerId = resolveOwnerId(targetRef, store);
        if (ownerId != null && !ownerId.equals(playerId)) {
            return LinkToggleResult.notToggled();
        }
        if (config.isRequireOwner() && ownerId == null) {
            return LinkToggleResult.notToggled();
        }
        if (config.isRequireTamed() && !TamedStateResolver.isTamed(targetRef, store)) {
            return LinkToggleResult.notToggled();
        }
        if (!isRoleAllowed(resolveRoleId(npc), config)) {
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
                        resolveNpcDisplayNameFromComponents(targetRef, store),
                        resolveNpcNameKey(npc),
                        resolveNpcRoleId(npc)
                );
            } else {
                updatedItem = removeLinkedNpcRecord(updatedItem, npcUuid);
            }
        }
        String name = resolveNpcDisplayName(targetRef, store, npc);
        return new LinkToggleResult(true, linked, name, updatedItem);
    }

    private void maybeRelocateLoadedRecallCandidate(Context context, Candidate candidate) {
        if (context == null || candidate == null || candidate.ref == null || candidate.npc == null) {
            return;
        }
        if (!isRecallCommand(context.command)) {
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
        Vector3d safePosition = computeSafeRecallPosition(context, new Vector3d(npcPos));
        if (safePosition == null) {
            return;
        }
        candidate.npc.moveTo(candidate.ref, safePosition.x, safePosition.y, safePosition.z, context.store);
    }

    private boolean isReturnHomeCommand(CommandEntry command) {
        if (command == null || command.getSteps() == null) {
            return false;
        }
        for (CommandStep step : command.getSteps()) {
            if (!(step instanceof MoveToPositionStep moveStep)) {
                continue;
            }
            MoveSource source = moveStep.getSource() != null ? moveStep.getSource() : MoveSource.RaycastHit;
            if (source == MoveSource.StoredHome) {
                return true;
            }
        }
        return false;
    }

    private boolean isRecallCommand(CommandEntry command) {
        if (command == null) {
            return false;
        }
        if (command.getId() != null && "recall".equalsIgnoreCase(command.getId().trim())) {
            return true;
        }
        if (command.getSteps() == null) {
            return false;
        }
        for (CommandStep step : command.getSteps()) {
            if (step instanceof ClearCombatStep clearCombatStep && clearCombatStep.isAssignOwnerAsMasterTarget()) {
                return true;
            }
        }
        return false;
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

    private Vector3d computeSafeRecallPosition(Context context, Vector3d sourcePosition) {
        if (context == null) {
            return null;
        }
        TwGlobalConfig globalConfig = TwGlobalConfig.resolveActive();
        return computeSafeCompanionPlacementPosition(
                context.playerRef,
                context.store,
                context.recallSafeSpawnDistance,
                sourcePosition,
                globalConfig
        );
    }

    private Vector3d computeSafeRecallPosition(Ref<EntityStore> playerRef,
                                               Store<EntityStore> store,
                                               double safeSpawnDistance,
                                               Vector3d sourcePosition) {
        TwGlobalConfig globalConfig = TwGlobalConfig.resolveActive();
        return computeSafeCompanionPlacementPosition(
                playerRef,
                store,
                safeSpawnDistance,
                sourcePosition,
                globalConfig
        );
    }

    private Vector3d computeSafeRespawnPosition(Ref<EntityStore> playerRef,
                                                Store<EntityStore> store,
                                                double safeSpawnDistance,
                                                Vector3d sourcePosition) {
        TwGlobalConfig globalConfig = TwGlobalConfig.resolveActive();
        return computeSafeCompanionPlacementPosition(
                playerRef,
                store,
                safeSpawnDistance,
                sourcePosition,
                globalConfig
        );
    }

    private Vector3d computeSafeCompanionPlacementPosition(Ref<EntityStore> playerRef,
                                                           Store<EntityStore> store,
                                                           double safeSpawnDistance,
                                                           Vector3d sourcePosition,
                                                           TwGlobalConfig globalConfig) {
        if (store == null || playerRef == null || !playerRef.isValid()) {
            return null;
        }
        TransformComponent playerTransform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (playerTransform == null) {
            return null;
        }
        Vector3d playerPos = new Vector3d(playerTransform.getPosition());
        Vector3d desired = computeDesiredPlacementPosition(playerPos, safeSpawnDistance, sourcePosition);
        double minRelativeY = resolvePlacementMinRelativeY(globalConfig);
        double maxRelativeY = resolvePlacementMaxRelativeY(globalConfig, minRelativeY);
        World world = store.getExternalData() != null ? store.getExternalData().getWorld() : null;
        if (world == null) {
            if (desired != null) {
                return new Vector3d(desired.x, playerPos.y + 1.0, desired.z);
            }
            return new Vector3d(playerPos.x, playerPos.y + 1.0, playerPos.z);
        }

        Vector3d lookDirection = resolvePlayerLookDirection(playerRef, store);
        double dirX = lookDirection != null ? lookDirection.x : 1.0;
        double dirZ = lookDirection != null ? lookDirection.z : 0.0;
        if (lookDirection == null && desired != null) {
            double dx = desired.x - playerPos.x;
            double dz = desired.z - playerPos.z;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 0.001) {
                dirX = dx / len;
                dirZ = dz / len;
            }
        }
        double baseAngle = Math.atan2(dirZ, dirX);
        double targetDistance = Math.max(2.0, safeSpawnDistance);
        double[] distanceCandidates = resolvePlacementDistanceCandidates(globalConfig, targetDistance);
        double[] angleOffsets = resolvePlacementAngleOffsets();
        for (double distance : distanceCandidates) {
            if (distance < 2.0) {
                continue;
            }
            for (double angleOffset : angleOffsets) {
                double radians = baseAngle + Math.toRadians(angleOffset);
                double x = playerPos.x + Math.cos(radians) * distance;
                double z = playerPos.z + Math.sin(radians) * distance;
                Vector3d surface = projectToSurface(world, x, playerPos.y + 2.0, z, 48.0);
                if (surface == null) {
                    surface = projectToSurface(world, x, playerPos.y + 24.0, z, 64.0);
                }
                if (surface != null && isWithinPlacementVerticalBand(surface.y, playerPos.y, minRelativeY, maxRelativeY)) {
                    return surface;
                }
            }
        }

        Vector3d nearPlayer = projectToSurface(world, playerPos.x, playerPos.y + 8.0, playerPos.z, 48.0);
        if (nearPlayer != null && isWithinPlacementVerticalBand(nearPlayer.y, playerPos.y, minRelativeY, maxRelativeY)) {
            return nearPlayer;
        }
        if (desired != null) {
            return new Vector3d(desired.x, playerPos.y + 1.0, desired.z);
        }
        return new Vector3d(playerPos.x, playerPos.y + 1.0, playerPos.z);
    }

    private Vector3d computeDesiredPlacementPosition(Vector3d playerPos,
                                                     double safeSpawnDistance,
                                                     Vector3d sourcePosition) {
        if (playerPos == null) {
            return null;
        }
        double dirX = 1.0;
        double dirZ = 0.0;
        if (sourcePosition != null) {
            double sx = sourcePosition.x - playerPos.x;
            double sz = sourcePosition.z - playerPos.z;
            double len = Math.sqrt(sx * sx + sz * sz);
            if (len > 0.001) {
                dirX = sx / len;
                dirZ = sz / len;
            }
        }
        double distance = Math.max(2.0, safeSpawnDistance);
        return new Vector3d(
                playerPos.x + dirX * distance,
                playerPos.y + 1.0,
                playerPos.z + dirZ * distance
        );
    }

    private double[] resolvePlacementDistanceCandidates(TwGlobalConfig globalConfig, double fallbackDistance) {
        double close = resolvePositiveDouble(
                globalConfig != null ? globalConfig.getCommandDeadRespawnDistanceClose() : 0.0,
                RESPAWN_DISTANCE_CLOSE
        );
        double near = resolvePositiveDouble(
                globalConfig != null ? globalConfig.getCommandDeadRespawnDistanceNear() : 0.0,
                RESPAWN_DISTANCE_NEAR
        );
        double mid = resolvePositiveDouble(
                globalConfig != null ? globalConfig.getCommandDeadRespawnDistanceMid() : 0.0,
                RESPAWN_DISTANCE_MID
        );
        double far = resolvePositiveDouble(
                globalConfig != null ? globalConfig.getCommandDeadRespawnDistanceFar() : 0.0,
                RESPAWN_DISTANCE_FAR
        );
        return new double[] {
                Math.max(2.0, close),
                Math.max(2.0, near),
                Math.max(2.0, mid),
                Math.max(2.0, far),
                Math.max(2.0, fallbackDistance)
        };
    }

    private double[] resolvePlacementAngleOffsets() {
        List<Double> offCamera = new ArrayList<>();
        List<Double> fallback = new ArrayList<>();
        for (double angleOffset : PLACEMENT_ANGLE_OFFSETS) {
            if (Math.abs(angleOffset) >= OUT_OF_VIEW_MIN_ANGLE_DEGREES) {
                offCamera.add(angleOffset);
                continue;
            }
            fallback.add(angleOffset);
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Collections.shuffle(offCamera, random);
        Collections.shuffle(fallback, random);
        double[] ordered = new double[offCamera.size() + fallback.size()];
        int index = 0;
        for (double value : offCamera) {
            ordered[index++] = value;
        }
        for (double value : fallback) {
            ordered[index++] = value;
        }
        return ordered;
    }

    private double resolvePlacementMinRelativeY(TwGlobalConfig globalConfig) {
        return resolveFiniteDouble(
                globalConfig != null ? globalConfig.getCommandPlacementMinRelativeY() : Double.NaN,
                COMMAND_PLACEMENT_MIN_RELATIVE_Y
        );
    }

    private double resolvePlacementMaxRelativeY(TwGlobalConfig globalConfig, double minRelativeY) {
        double maxRelativeY = resolveFiniteDouble(
                globalConfig != null ? globalConfig.getCommandPlacementMaxRelativeY() : Double.NaN,
                COMMAND_PLACEMENT_MAX_RELATIVE_Y
        );
        return maxRelativeY < minRelativeY ? minRelativeY : maxRelativeY;
    }

    private boolean isWithinPlacementVerticalBand(double surfaceY,
                                                  double playerY,
                                                  double minRelativeY,
                                                  double maxRelativeY) {
        double minY = playerY + minRelativeY;
        double maxY = playerY + maxRelativeY;
        return surfaceY >= minY && surfaceY <= maxY;
    }

    private Vector3d resolvePlayerLookDirection(Ref<EntityStore> playerRef, Store<EntityStore> store) {
        if (playerRef == null || !playerRef.isValid() || store == null) {
            return null;
        }
        Vector3f rotation = null;
        HeadRotation headRotation = store.getComponent(playerRef, HeadRotation.getComponentType());
        if (headRotation != null) {
            rotation = new Vector3f(headRotation.getRotation());
        }
        if (rotation == null) {
            TransformComponent transform = store.getComponent(playerRef, TransformComponent.getComponentType());
            if (transform == null) {
                return null;
            }
            rotation = new Vector3f(transform.getRotation());
        }
        Vector3f forward = new Vector3f(Vector3f.FORWARD);
        forward.rotateY(rotation.getYaw());
        forward.rotateX(rotation.getPitch());
        forward.normalize();
        Vector3d out = new Vector3d(forward.x, 0.0, forward.z);
        if (out.squaredLength() <= 0.0001) {
            return null;
        }
        out.normalize();
        return out;
    }

    private Vector3d projectToSurface(World world,
                                      double x,
                                      double y,
                                      double z,
                                      double maxDistance) {
        if (world == null || maxDistance <= 0.0) {
            return null;
        }
        Vector3d target = TargetUtil.getTargetLocation(
                world,
                this::isBlockingSpawnBlock,
                x,
                y,
                z,
                0.0,
                -1.0,
                0.0,
                maxDistance
        );
        if (target == null) {
            return null;
        }
        int blockY = (int) Math.floor(target.y);
        double surfaceY = blockY + 1.0 + 0.05;
        if (surfaceY < target.y + 0.02) {
            surfaceY = target.y + 0.02;
        }
        return new Vector3d(x, surfaceY, z);
    }

    private boolean isBlockingSpawnBlock(int blockId) {
        if (blockId == 0) {
            return false;
        }
        BlockType blockType = BlockType.getAssetMap().getAsset(blockId);
        if (blockType == null || blockType == BlockType.UNKNOWN) {
            return false;
        }
        return WorldUtil.isSolidOnlyBlock(blockType, 0);
    }

    private ItemStack upsertLinkedNpcRecord(ItemStack stack, UUID npcUuid, Vector3d position, Vector3d homePosition) {
        return upsertLinkedNpcRecord(stack, npcUuid, position, homePosition, null, null, null);
    }

    private ItemStack upsertLinkedNpcRecord(ItemStack stack,
                                            UUID npcUuid,
                                            Vector3d position,
                                            Vector3d homePosition,
                                            String cachedDisplayName,
                                            String cachedNameKey,
                                            String cachedRoleId) {
        if (stack == null || stack.isEmpty() || npcUuid == null) {
            return stack;
        }
        List<LinkedNpcRecord> records = new ArrayList<>(readLinkedNpcRecords(stack));
        String key = npcUuid.toString().toLowerCase(Locale.ROOT);
        boolean updated = false;
        for (int i = 0; i < records.size(); i++) {
            LinkedNpcRecord record = records.get(i);
            if (record == null || record.npcUuid == null) {
                continue;
            }
            if (!key.equals(record.npcUuid.toString().toLowerCase(Locale.ROOT))) {
                continue;
            }
            Vector3d mergedLastKnown = position != null ? position : record.lastKnownPosition;
            Vector3d mergedHome = homePosition != null ? homePosition : record.homePosition;
            String mergedDisplayName = firstNonBlank(cachedDisplayName, record.cachedDisplayName);
            String mergedNameKey = firstNonBlank(cachedNameKey, record.cachedNameKey);
            String mergedRoleId = firstNonBlank(cachedRoleId, record.cachedRoleId);
            records.set(i, new LinkedNpcRecord(
                    npcUuid,
                    mergedLastKnown,
                    mergedHome,
                    mergedDisplayName,
                    mergedNameKey,
                    mergedRoleId
            ));
            updated = true;
            break;
        }
        if (!updated) {
            records.add(new LinkedNpcRecord(
                    npcUuid,
                    position,
                    homePosition,
                    firstNonBlank(cachedDisplayName, null),
                    firstNonBlank(cachedNameKey, null),
                    firstNonBlank(cachedRoleId, null)
            ));
        }
        return writeLinkedNpcRecords(stack, records);
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
                    resolveNpcDisplayNameFromComponents(candidate.ref, store),
                    resolveNpcNameKey(candidate.npc),
                    resolveNpcRoleId(candidate.npc)
            );
        }
        return updated;
    }

    private ItemStack removeLinkedNpcRecord(ItemStack stack, UUID npcUuid) {
        if (stack == null || stack.isEmpty() || npcUuid == null) {
            return stack;
        }
        List<LinkedNpcRecord> records = readLinkedNpcRecords(stack);
        if (records.isEmpty()) {
            return stack;
        }
        String key = npcUuid.toString().toLowerCase(Locale.ROOT);
        ArrayList<LinkedNpcRecord> filtered = new ArrayList<>(records.size());
        for (LinkedNpcRecord record : records) {
            if (record == null || record.npcUuid == null) {
                continue;
            }
            if (key.equals(record.npcUuid.toString().toLowerCase(Locale.ROOT))) {
                continue;
            }
            filtered.add(record);
        }
        return writeLinkedNpcRecords(stack, filtered);
    }

    private List<LinkedNpcRecord> readLinkedNpcRecords(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return List.of();
        }
        String encoded = stack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_LINKED_NPCS, Codec.STRING);
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        String[] lines = encoded.split(LINK_RECORD_SEPARATOR);
        ArrayList<LinkedNpcRecord> records = new ArrayList<>(lines.length);
        for (String line : lines) {
            LinkedNpcRecord record = parseLinkedNpcRecord(line);
            if (record == null || record.npcUuid == null) {
                continue;
            }
            records.add(record);
        }
        return records;
    }

    private ItemStack writeLinkedNpcRecords(ItemStack stack, List<LinkedNpcRecord> records) {
        if (stack == null || stack.isEmpty()) {
            return stack;
        }
        if (records == null || records.isEmpty()) {
            return stack.withMetadata(TameworkMetadataKeys.COMMAND_LINKED_NPCS, Codec.STRING, "");
        }
        StringBuilder builder = new StringBuilder();
        Set<UUID> seen = new HashSet<>();
        for (LinkedNpcRecord record : records) {
            if (record == null || record.npcUuid == null || seen.contains(record.npcUuid)) {
                continue;
            }
            seen.add(record.npcUuid);
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(record.npcUuid);
            Vector3d encodedLastKnown = record.lastKnownPosition != null
                    ? record.lastKnownPosition
                    : record.homePosition;
            if (encodedLastKnown != null) {
                builder.append('|').append(encodedLastKnown.x);
                builder.append('|').append(encodedLastKnown.y);
                builder.append('|').append(encodedLastKnown.z);
            }
            if (record.homePosition != null) {
                builder.append('|').append(record.homePosition.x);
                builder.append('|').append(record.homePosition.y);
                builder.append('|').append(record.homePosition.z);
            }
            if (record.cachedDisplayName != null && !record.cachedDisplayName.isBlank()) {
                builder.append('|').append("dn=").append(encodeRecordText(record.cachedDisplayName));
            }
            if (record.cachedNameKey != null && !record.cachedNameKey.isBlank()) {
                builder.append('|').append("nk=").append(encodeRecordText(record.cachedNameKey));
            }
            if (record.cachedRoleId != null && !record.cachedRoleId.isBlank()) {
                builder.append('|').append("rid=").append(encodeRecordText(record.cachedRoleId));
            }
        }
        return stack.withMetadata(TameworkMetadataKeys.COMMAND_LINKED_NPCS, Codec.STRING, builder.toString());
    }

    private LinkedNpcRecord parseLinkedNpcRecord(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.trim().split(LINK_RECORD_PARTS_SEPARATOR);
        if (parts.length == 0) {
            return null;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(parts[0].trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
        Vector3d position = null;
        Vector3d homePosition = null;
        String cachedDisplayName = null;
        String cachedNameKey = null;
        String cachedRoleId = null;
        int index = 1;
        if (parts.length >= 4) {
            try {
                position = new Vector3d(
                        Double.parseDouble(parts[1]),
                        Double.parseDouble(parts[2]),
                        Double.parseDouble(parts[3])
                );
                index = 4;
            } catch (NumberFormatException ignored) {
                position = null;
                index = 1;
            }
        }
        if (parts.length >= index + 3) {
            try {
                homePosition = new Vector3d(
                        Double.parseDouble(parts[index]),
                        Double.parseDouble(parts[index + 1]),
                        Double.parseDouble(parts[index + 2])
                );
                index += 3;
            } catch (NumberFormatException ignored) {
                homePosition = null;
            }
        }
        for (int i = index; i < parts.length; i++) {
            String token = parts[i];
            if (token == null || token.isBlank()) {
                continue;
            }
            if (token.startsWith("dn=")) {
                cachedDisplayName = decodeRecordText(token.substring(3));
                continue;
            }
            if (token.startsWith("nk=")) {
                cachedNameKey = decodeRecordText(token.substring(3));
                continue;
            }
            if (token.startsWith("rid=")) {
                cachedRoleId = decodeRecordText(token.substring(4));
            }
        }
        return new LinkedNpcRecord(uuid, position, homePosition, cachedDisplayName, cachedNameKey, cachedRoleId);
    }

    private String encodeRecordText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeRecordText(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            String decoded = new String(bytes, StandardCharsets.UTF_8);
            return decoded.isBlank() ? null : decoded;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void emitCommandExecutionFeedback(Context context, int affected, int queued) {
        if (context == null || context.player == null || context.command == null) {
            return;
        }
        String defaultMessage;
        if (affected > 0 && queued > 0) {
            defaultMessage = "Command " + resolveCommandLabel(context.command)
                    + " applied to " + affected + " NPC(s), queued for " + queued + " unloaded NPC(s).";
        } else if (affected > 0) {
            defaultMessage = "Command " + resolveCommandLabel(context.command) + " applied to " + affected + " NPC(s).";
        } else if (queued > 0) {
            defaultMessage = "Command " + resolveCommandLabel(context.command) + " queued for " + queued + " unloaded NPC(s).";
        } else {
            defaultMessage = "No NPCs could execute that command.";
        }
        CommandFeedback feedback = context.command.getFeedback();
        String hudMessage = resolveFeedbackText(
                feedback != null ? feedback.getHudMessage() : null,
                context.command,
                affected,
                defaultMessage
        );
        if (hudMessage != null && !hudMessage.isBlank()) {
            sendSuccessMessage(context.player, hudMessage);
        }
        if (feedback == null) {
            return;
        }
        emitFeedbackSound(feedback.getSoundEvent(), context.playerRef, context.store);
        emitFeedbackParticles(feedback.getParticleSystem(), feedback.getParticleOffset(), context.playerRef, context.store);
    }

    private String resolveFeedbackText(String template,
                                       CommandEntry command,
                                       int affected,
                                       String fallback) {
        String value = (template != null && !template.isBlank()) ? template : fallback;
        if (value == null || value.isBlank()) {
            return null;
        }
        String commandLabel = resolveCommandLabel(command);
        return value
                .replace("%count%", Integer.toString(affected))
                .replace("%command%", commandLabel)
                .replace("{count}", Integer.toString(affected))
                .replace("{command}", commandLabel);
    }

    private void emitFeedbackSound(String soundEventId,
                                   Ref<EntityStore> playerRef,
                                   Store<EntityStore> store) {
        if (soundEventId == null || soundEventId.isBlank() || playerRef == null || !playerRef.isValid() || store == null) {
            return;
        }
        int soundEventIndex = SoundEvent.getAssetMap().getIndex(soundEventId);
        if (soundEventIndex <= 0) {
            return;
        }
        TransformComponent transform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }
        SoundUtil.playSoundEvent3d(soundEventIndex, SoundCategory.SFX, transform.getPosition(), store);
    }

    private void emitFeedbackParticles(String particleSystem,
                                       Vector3d offset,
                                       Ref<EntityStore> playerRef,
                                       Store<EntityStore> store) {
        if (particleSystem == null || particleSystem.isBlank() || playerRef == null || !playerRef.isValid() || store == null) {
            return;
        }
        TransformComponent transform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }
        Vector3d position = new Vector3d(transform.getPosition());
        if (offset != null) {
            position.x += offset.x;
            position.y += offset.y;
            position.z += offset.z;
        }
        ParticleUtil.spawnParticleEffect(particleSystem, position, store);
    }

    private boolean isCooldownActive(ItemStack stack, int cooldownMs) {
        if (cooldownMs <= 0) {
            return false;
        }
        Long until = stack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_COOLDOWN_UNTIL, Codec.LONG);
        return until != null && until > System.currentTimeMillis();
    }

    private void sendDefaultMessage(Player player, String text) {
        if (player == null || text == null || text.isBlank()) {
            return;
        }
        uiMessageService.show(player, text, NotificationStyle.Default);
    }

    private void sendSuccessMessage(Player player, String text) {
        if (player == null || text == null || text.isBlank()) {
            return;
        }
        uiMessageService.show(player, text, NotificationStyle.Success);
    }

    private void sendWarningMessage(Player player, String text) {
        if (player == null || text == null || text.isBlank()) {
            return;
        }
        uiMessageService.show(player, text, NotificationStyle.Warning);
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

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private boolean updateHeldItem(Player player, ItemStack updated) {
        Inventory inventory = player.getInventory();
        if (inventory == null) {
            return false;
        }
        ItemContainer hotbar = inventory.getHotbar();
        if (hotbar == null) {
            return false;
        }
        byte slot = inventory.getActiveHotbarSlot();
        if (slot == Inventory.INACTIVE_SLOT_INDEX) {
            return false;
        }
        hotbar.setItemStackForSlot((short) slot, updated);
        inventory.markChanged();
        player.sendInventory();
        return true;
    }

    private static final class HealthSnapshot {
        private final int current;
        private final int max;

        private HealthSnapshot(int current, int max) {
            this.current = current;
            this.max = max;
        }
    }

    private static final class ToolResolution {
        private final ItemStack stack;
        private final String toolId;
        private final boolean changed;

        private ToolResolution(ItemStack stack, String toolId, boolean changed) {
            this.stack = stack;
            this.toolId = toolId;
            this.changed = changed;
        }
    }

    private static final class Context {
        private final Player player;
        private final Ref<EntityStore> playerRef;
        private final Store<EntityStore> store;
        private final TwCommandItemConfig config;
        private final CommandEntry command;
        private final String itemId;
        private final String toolId;
        private final Ref<EntityStore> commandTarget;
        private final Vector3d raycastPosition;
        private ItemStack workingItem;
        private boolean itemChanged;
        private final boolean blockAllPlayerDamageIfOwned;
        private final boolean invulnerableIfOwned;
        private final double returnHomeTeleportDistance;
        private final double returnHomePathDistanceBeforeTeleport;
        private final long returnHomeTeleportDelayMs;
        private final double recallSafeSpawnDistance;
        private final double recallForceRelocateDistance;

        private Context(Player player,
                        Ref<EntityStore> playerRef,
                        Store<EntityStore> store,
                        TwCommandItemConfig config,
                        CommandEntry command,
                        String itemId,
                        String toolId,
                        Ref<EntityStore> commandTarget,
                        Vector3d raycastPosition,
                        ItemStack workingItem,
                        boolean blockAllPlayerDamageIfOwned,
                        boolean invulnerableIfOwned,
                        double returnHomeTeleportDistance,
                        double returnHomePathDistanceBeforeTeleport,
                        long returnHomeTeleportDelayMs,
                        double recallSafeSpawnDistance,
                        double recallForceRelocateDistance) {
            this.player = player;
            this.playerRef = playerRef;
            this.store = store;
            this.config = config;
            this.command = command;
            this.itemId = itemId;
            this.toolId = toolId;
            this.commandTarget = commandTarget;
            this.raycastPosition = raycastPosition;
            this.workingItem = workingItem;
            this.blockAllPlayerDamageIfOwned = blockAllPlayerDamageIfOwned;
            this.invulnerableIfOwned = invulnerableIfOwned;
            this.returnHomeTeleportDistance = returnHomeTeleportDistance;
            this.returnHomePathDistanceBeforeTeleport = returnHomePathDistanceBeforeTeleport;
            this.returnHomeTeleportDelayMs = returnHomeTeleportDelayMs;
            this.recallSafeSpawnDistance = recallSafeSpawnDistance;
            this.recallForceRelocateDistance = recallForceRelocateDistance;
        }
    }

    private static final class Candidate {
        private final Ref<EntityStore> ref;
        private final NPCEntity npc;
        private final double distSq;

        private Candidate(Ref<EntityStore> ref, NPCEntity npc, double distSq) {
            this.ref = ref;
            this.npc = npc;
            this.distSq = distSq;
        }
    }

    private static final class StepResult {
        private final boolean applied;
        private final boolean abortAll;

        private StepResult(boolean applied, boolean abortAll) {
            this.applied = applied;
            this.abortAll = abortAll;
        }
    }

    private static final class CommandSelectionResult {
        private final ItemStack stack;
        private final CommandEntry command;
        private final boolean changed;

        private CommandSelectionResult(ItemStack stack, CommandEntry command, boolean changed) {
            this.stack = stack;
            this.command = command;
            this.changed = changed;
        }

        private static CommandSelectionResult none(ItemStack stack) {
            return new CommandSelectionResult(stack, null, false);
        }
    }

    private static final class LinkedNpcRecord {
        private final UUID npcUuid;
        private final Vector3d lastKnownPosition;
        private final Vector3d homePosition;
        private final String cachedDisplayName;
        private final String cachedNameKey;
        private final String cachedRoleId;

        private LinkedNpcRecord(UUID npcUuid, Vector3d lastKnownPosition, Vector3d homePosition) {
            this(npcUuid, lastKnownPosition, homePosition, null, null, null);
        }

        private LinkedNpcRecord(UUID npcUuid,
                                Vector3d lastKnownPosition,
                                Vector3d homePosition,
                                String cachedDisplayName,
                                String cachedNameKey,
                                String cachedRoleId) {
            this.npcUuid = npcUuid;
            this.lastKnownPosition = lastKnownPosition != null ? new Vector3d(lastKnownPosition) : null;
            this.homePosition = homePosition != null ? new Vector3d(homePosition) : null;
            this.cachedDisplayName = (cachedDisplayName != null && !cachedDisplayName.isBlank()) ? cachedDisplayName : null;
            this.cachedNameKey = (cachedNameKey != null && !cachedNameKey.isBlank()) ? cachedNameKey : null;
            this.cachedRoleId = (cachedRoleId != null && !cachedRoleId.isBlank()) ? cachedRoleId : null;
        }
    }

    private static final class RelocationState {
        private final String state;
        private final String subState;

        private RelocationState(String state, String subState) {
            this.state = state;
            this.subState = subState;
        }
    }

    private static final class LinkToggleResult {
        private final boolean toggled;
        private final boolean linked;
        private final String npcName;
        private final ItemStack updatedItem;

        private LinkToggleResult(boolean toggled, boolean linked, String npcName, ItemStack updatedItem) {
            this.toggled = toggled;
            this.linked = linked;
            this.npcName = npcName;
            this.updatedItem = updatedItem;
        }

        private static LinkToggleResult notToggled() {
            return new LinkToggleResult(false, false, null, null);
        }
    }
}
