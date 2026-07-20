package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.CommandEntry;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import javax.annotation.Nullable;

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
    private final CommandCanonicalRecordCommitGate canonicalRecordCommitGate;
    private final LinkedNpcRecordCollection linkedRecordCollection;
    @Nullable
    private final CommandNpcProfileActionResolver profileActionResolver;

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
        this(
                resolutionService,
                linkMutationService,
                deathService,
                captureService,
                coopService,
                lostService,
                relocationDispatchService,
                stepExecutionService,
                feedbackService,
                defaultReturnHomeTeleportDistance,
                defaultReturnHomePathDistanceBeforeTeleport,
                defaultReturnHomeTeleportDelayMs,
                defaultRecallSafeSpawnDistance,
                defaultRecallForceRelocateDistance,
                null
        );
    }

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
                           double defaultRecallForceRelocateDistance,
                           @Nullable CommandNpcProfileActionResolver profileActionResolver) {
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
        this.canonicalRecordCommitGate = new CommandCanonicalRecordCommitGate();
        this.linkedRecordCollection = new LinkedNpcRecordCollection();
        this.profileActionResolver = profileActionResolver;
    }

    void applyMenuMoveCommand(Player player,
                              String toolId,
                              UUID npcUuid,
                              boolean returnHome,
                              Function<CommandEntry, String> labelResolver) {
        if (player == null || toolId == null || toolId.isBlank() || npcUuid == null) {
            return;
        }
        Player capturedPlayer = player;
        WorldPlayerResolver.ResolvedPlayer livePlayer = WorldPlayerResolver.resolveCurrent(player);
        if (livePlayer == null) {
            String unavailableLabel = LocalizedText.resolve(
                    capturedPlayer,
                    returnHome
                            ? "tamework.ui.notifications.command.move.returnHome.actionLabel"
                            : "tamework.ui.notifications.command.move.recall.actionLabel"
            );
            feedbackService.showWarningKey(
                    capturedPlayer, "tamework.ui.notifications.command.move.unavailable", unavailableLabel);
            return;
        }
        player = livePlayer.player();
        String actionLabel = LocalizedText.resolve(
                player,
                returnHome
                        ? "tamework.ui.notifications.command.move.returnHome.actionLabel"
                        : "tamework.ui.notifications.command.move.recall.actionLabel"
        );
        Inventory inventory = player.getInventory();
        if (inventory == null || inventory.getHotbar() == null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.move.unavailable", actionLabel);
            return;
        }
        World world = livePlayer.world();
        Store<EntityStore> store = livePlayer.store();
        Ref<EntityStore> playerRef = livePlayer.ref();

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
            List<LinkedNpcRecord> linkedRecords = linkMutationService.readLinkedNpcRecords(stack);
            LinkedNpcRecord record = linkMutationService.findLinkedNpcRecord(linkedRecords, npcUuid);
            if (record == null) {
                feedbackService.showWarningKey(player, "tamework.ui.notifications.command.shared.notLinkedToTool");
                return;
            }
            UUID selectedNpcUuid = record.npcUuid;
            String selectedProfileId = record.profileId;
            CommandNpcProfileActionResolver.ActionTarget relocationTarget =
                    resolveRelocationTarget(record);
            if (relocationTarget == null || !relocationTarget.isActionable()) {
                feedbackService.showWarningKey(
                        player, "tamework.ui.notifications.command.move.unavailable", actionLabel);
                return;
            }
            record = relocationTarget.resolvedRecord();
            npcUuid = record.npcUuid;
            if (profileActionResolver != null
                    && (relocationTarget.redirected()
                    || !java.util.Objects.equals(record.profileId, selectedProfileId))) {
                List<LinkedNpcRecord> repairedRecords = linkedRecordCollection.replaceResolvedSelection(
                        linkedRecords, selectedNpcUuid, record);
                if (!repairedRecords.equals(linkedRecords)) {
                    ItemStack canonicalStack = linkMutationService.writeLinkedNpcRecords(stack, repairedRecords);
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
                        feedbackService.showWarningKey(
                                player, "tamework.ui.notifications.command.shared.itemNotFound");
                        return;
                    }
                    stack = canonicalStack;
                    linkedRecords = repairedRecords;
                }
            }
            if (deathService != null
                    && deathService.getDeadSnapshotForTool(npcUuid, toolId, player.getUuid()) != null) {
                feedbackService.showWarningKey(player, "tamework.ui.notifications.command.move.dead");
                return;
            }
            if (captureService != null
                    && captureService.getCapturedSnapshotForToolOrOwner(
                    npcUuid,
                    toolId,
                    player.getUuid()
            ) != null) {
                feedbackService.showWarningKey(player, "tamework.ui.notifications.command.move.captured");
                return;
            }
            if (coopService != null
                    && coopService.getCoopSnapshotForToolOrOwner(
                    npcUuid,
                    toolId,
                    player.getUuid()
            ) != null) {
                feedbackService.showWarningKey(player, "tamework.ui.notifications.command.move.inCoop");
                return;
            }
            if (lostService != null && lostService.isLost(npcUuid)) {
                feedbackService.showWarningKey(player, "tamework.ui.notifications.command.move.lost");
                return;
            }
            TwCommandItemConfig config = resolutionService.resolveConfig(stack.getItemId(), null);
            if (config == null || !config.isEnabled()) {
                feedbackService.showWarningKey(player, "tamework.ui.notifications.command.shared.itemNotConfigured");
                return;
            }
            CommandEntry panelCommand = returnHome
                    ? resolutionService.resolvePanelReturnHomeCommand(config, stack)
                    : resolutionService.resolvePanelRecallCommand(config, stack);
            if (panelCommand == null) {
                feedbackService.showWarningKey(
                        player,
                        returnHome
                                ? "tamework.ui.notifications.command.move.returnHome.notConfigured"
                                : "tamework.ui.notifications.command.move.recall.notConfigured"
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
                    feedbackService.showWarningKey(player, "tamework.ui.notifications.command.move.returnHome.noHome");
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
                    TameworkRuntimeSettings.blockAllPlayerDamageIfOwned(settings.isBlockAllPlayerDamageIfOwned()),
                    TameworkRuntimeSettings.invulnerableIfOwned(settings.isInvulnerableIfOwned()),
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
                loadedRecipients.add(new Candidate(npcRef, npc, distSq, record.profileId));
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
                feedbackService.showWarningKey(
                        player,
                        returnHome
                                ? "tamework.ui.notifications.command.move.returnHome.noneMoved"
                                : "tamework.ui.notifications.command.execution.none"
                );
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
        feedbackService.showWarningKey(player, "tamework.ui.notifications.command.shared.itemNotFound");
    }

    @Nullable
    private CommandNpcProfileActionResolver.ActionTarget resolveRelocationTarget(
            @Nullable LinkedNpcRecord record) {
        if (record == null || record.npcUuid == null || profileActionResolver == null) {
            return record == null ? null : new CommandNpcProfileActionResolver.ActionTarget(
                    CommandNpcProfileActionResolver.ResolutionStatus.RESOLVED,
                    record.profileId,
                    record.npcUuid,
                    record.npcUuid,
                    record,
                    List.of(record.npcUuid),
                    List.of(),
                    null
            );
        }
        return profileActionResolver.resolveRelocation(record);
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
