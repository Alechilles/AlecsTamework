package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.CommandEntry;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Executes per-NPC menu move actions (Recall / Return Home) for command items.
 */
final class CommandMenuMoveService {
    private final CommandResolutionService resolutionService;
    private final CommandLinkMutationService linkMutationService;
    private final CommandLinkedNpcDeathService deathService;
    private final CommandLinkedNpcCaptureService captureService;
    private final CommandLinkedNpcCoopService coopService;
    private final CommandLinkedNpcLostService lostService;
    private final CommandRelocationDispatchService relocationDispatchService;
    private final CommandStepExecutionService stepExecutionService;
    private final CommandFeedbackService feedbackService;
    private final double defaultReturnHomeTeleportDistance;
    private final double defaultReturnHomePathDistanceBeforeTeleport;
    private final long defaultReturnHomeTeleportDelayMs;
    private final double defaultRecallSafeSpawnDistance;
    private final double defaultRecallForceRelocateDistance;

    CommandMenuMoveService(CommandResolutionService resolutionService,
                           CommandLinkMutationService linkMutationService,
                           CommandLinkedNpcDeathService deathService,
                           CommandLinkedNpcCaptureService captureService,
                           CommandLinkedNpcCoopService coopService,
                           CommandLinkedNpcLostService lostService,
                           CommandRelocationDispatchService relocationDispatchService,
                           CommandStepExecutionService stepExecutionService,
                           CommandFeedbackService feedbackService,
                           double defaultReturnHomeTeleportDistance,
                           double defaultReturnHomePathDistanceBeforeTeleport,
                           long defaultReturnHomeTeleportDelayMs,
                           double defaultRecallSafeSpawnDistance,
                           double defaultRecallForceRelocateDistance) {
        this.resolutionService = resolutionService;
        this.linkMutationService = linkMutationService;
        this.deathService = deathService;
        this.captureService = captureService;
        this.coopService = coopService;
        this.lostService = lostService;
        this.relocationDispatchService = relocationDispatchService;
        this.stepExecutionService = stepExecutionService;
        this.feedbackService = feedbackService;
        this.defaultReturnHomeTeleportDistance = defaultReturnHomeTeleportDistance;
        this.defaultReturnHomePathDistanceBeforeTeleport = defaultReturnHomePathDistanceBeforeTeleport;
        this.defaultReturnHomeTeleportDelayMs = defaultReturnHomeTeleportDelayMs;
        this.defaultRecallSafeSpawnDistance = defaultRecallSafeSpawnDistance;
        this.defaultRecallForceRelocateDistance = defaultRecallForceRelocateDistance;
    }

    void applyMenuMoveCommand(Player player,
                              String toolId,
                              UUID npcUuid,
                              boolean returnHome,
                              Function<CommandEntry, String> labelResolver) {
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
            if (captureService != null
                    && captureService.getCapturedSnapshotForToolOrOwner(
                    npcUuid,
                    toolId,
                    player.getUuid()
            ) != null) {
                feedbackService.showWarning(player, "That companion is captured in a soul lantern. Spawn it first.");
                return;
            }
            if (coopService != null
                    && coopService.getCoopSnapshotForToolOrOwner(
                    npcUuid,
                    toolId,
                    player.getUuid()
            ) != null) {
                feedbackService.showWarning(player, "That companion is currently housed in a coop.");
                return;
            }
            if (lostService != null && lostService.isLost(npcUuid)) {
                feedbackService.showWarning(player, "That companion is lost. Use Respawn to recover it.");
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
            Ref<EntityStore> npcRef = world.getEntityRef(npcUuid);
            NPCEntity npc = (npcRef != null && npcRef.isValid())
                    ? store.getComponent(npcRef, NPCEntity.getComponentType())
                    : null;
            String roleId = null;
            if (npcRef != null && npcRef.isValid()) {
                roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
            }
            if ((roleId == null || roleId.isBlank()) && record.cachedRoleId != null && !record.cachedRoleId.isBlank()) {
                roleId = record.cachedRoleId;
            }
            if ((roleId == null || roleId.isBlank()) && npc != null) {
                roleId = npc.getRoleName();
            }
            TwCompanionConfig.EffectiveSettings settings = TwCompanionConfig.resolveEffectiveForRole(roleId);
            double returnHomeTeleportDistance = resolvePositiveDouble(
                    settings.getReturnHomeTeleportDistance(),
                    defaultReturnHomeTeleportDistance
            );
            double returnHomePathDistanceBeforeTeleport = resolvePositiveDouble(
                    settings.getReturnHomePathDistanceBeforeTeleport(),
                    defaultReturnHomePathDistanceBeforeTeleport
            );
            long returnHomeTeleportDelayMs = resolvePositiveLong(
                    settings.getReturnHomeTeleportDelayMs(),
                    defaultReturnHomeTeleportDelayMs
            );
            double recallSafeSpawnDistance = resolvePositiveDouble(
                    settings.getRecallSafeSpawnDistance(),
                    defaultRecallSafeSpawnDistance
            );
            double recallForceRelocateDistance = resolvePositiveDouble(
                    settings.getRecallForceRelocateDistance(),
                    defaultRecallForceRelocateDistance
            );
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
                    settings.isBlockAllPlayerDamageIfOwned(),
                    settings.isInvulnerableIfOwned(),
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
            int queued = relocationDispatchService.queueRelocationsForUnloaded(context, unloadedRecipients);
            if (context.itemChanged) {
                hotbar.setItemStackForSlot(slot, context.workingItem);
            }
            if (affected <= 0 && queued <= 0) {
                feedbackService.showWarning(player, returnHome ? "No companions could return home." : "No NPCs could execute that command.");
                return;
            }
            feedbackService.emitCommandExecutionFeedback(
                    context.player,
                    context.playerRef,
                    context.store,
                    context.command,
                    affected,
                    queued,
                    labelResolver
            );
            return;
        }
        feedbackService.showWarning(player, "Unable to find that command item.");
    }

    private StepResult executeCommand(Context context, Candidate candidate) {
        relocationDispatchService.maybeRelocateLoadedRecallCandidate(context, candidate);
        return stepExecutionService.executeCommand(context, candidate);
    }

    private double resolvePositiveDouble(double configured, double fallback) {
        return configured > 0.0 ? configured : fallback;
    }

    private long resolvePositiveLong(long configured, long fallback) {
        return configured > 0L ? configured : fallback;
    }
}
