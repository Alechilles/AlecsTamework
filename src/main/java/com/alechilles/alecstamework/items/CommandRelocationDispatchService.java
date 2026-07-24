package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.List;
import java.util.UUID;

/**
 * Coordinates relocation dispatch for loaded and unloaded linked companions.
 */
final class CommandRelocationDispatchService {
    private final CommandNpcRelocationService relocationService;
    private final CommandLinkedNpcDeathService deathService;
    private final CommandLinkedNpcCaptureService captureService;
    private final CommandLinkedNpcCoopService coopService;
    private final CommandResolutionService resolutionService;
    private final CommandStepExecutionService stepExecutionService;
    private final CommandCompanionPlacementService companionPlacementService;

    CommandRelocationDispatchService(CommandNpcRelocationService relocationService,
                                     CommandLinkedNpcDeathService deathService,
                                     CommandLinkedNpcCaptureService captureService,
                                     CommandLinkedNpcCoopService coopService,
                                     CommandResolutionService resolutionService,
                                     CommandStepExecutionService stepExecutionService,
                                     CommandCompanionPlacementService companionPlacementService) {
        this.relocationService = relocationService;
        this.deathService = deathService;
        this.captureService = captureService;
        this.coopService = coopService;
        this.resolutionService = resolutionService;
        this.stepExecutionService = stepExecutionService;
        this.companionPlacementService = companionPlacementService;
    }

    QueueResult queueRelocationsForUnloaded(Context context, List<LinkedNpcRecord> unloadedLinked) {
        if (context == null || unloadedLinked == null || unloadedLinked.isEmpty() || relocationService == null) {
            return QueueResult.none();
        }
        boolean returnHome = resolutionService.isReturnHomeCommand(context.command);
        boolean recall = resolutionService.isRecallCommand(context.command);
        if (!returnHome && !recall) {
            return QueueResult.none();
        }
        if (!CommandTravelSettings.isRecallTeleportingEnabled()) {
            return QueueResult.none();
        }
        RelocationState postRelocationState = stepExecutionService.resolveRelocationState(context.command, returnHome, recall);
        World world = context.player != null ? context.player.getWorld() : null;
        UUID ownerUuid = context.player != null ? context.player.getUuid() : null;
        if (world == null) {
            return QueueResult.none();
        }
        int queued = 0;
        for (LinkedNpcRecord record : unloadedLinked) {
            if (record == null || record.npcUuid == null) {
                continue;
            }
            if (relocationService.isDeleteOnRemoveRecoveryPending(record.npcUuid)) {
                continue;
            }
            if (captureService != null
                    && captureService.getCapturedSnapshotForToolOrOwner(
                    record.npcUuid,
                    context.toolId,
                    ownerUuid
            ) != null) {
                continue;
            }
            if (coopService != null
                    && coopService.getCoopSnapshotForToolOrOwner(
                    record.npcUuid,
                    context.toolId,
                    ownerUuid
            ) != null) {
                continue;
            }
            if (deathService != null
                    && deathService.getDeadSnapshotForTool(record.npcUuid, context.toolId, ownerUuid) != null) {
                continue;
            }
            relocationService.rememberSourceWorld(record.npcUuid, record.lastKnownWorldName);
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
                        record.homePosition,
                        false,
                        TwCompanionConfig.TransferFailurePolicy.QueueForRecall
                );
                queued++;
                continue;
            }
            Vector3d sourceHint = record.lastKnownPosition != null ? record.lastKnownPosition : record.homePosition;
            TwCompanionConfig.EffectiveSettings settings =
                    TwCompanionConfig.resolveEffectiveForRole(record.cachedRoleId);
            double safeSpawnDistance = resolvePositiveDouble(
                    settings.getRecallSafeSpawnDistance(),
                    context.recallSafeSpawnDistance
            );
            Vector3d safeDestination = companionPlacementService.computeSafeRecallPosition(
                    context.playerRef,
                    context.store,
                    safeSpawnDistance,
                    record.cachedRoleId,
                    sourceHint
            );
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
                    record.homePosition,
                    settings.isCrossWorldRecallEnabled(),
                    settings.getOnTransferFailure()
            );
            queued++;
        }
        return new QueueResult(queued);
    }

    void maybeRelocateLoadedRecallCandidate(Context context, Candidate candidate) {
        if (context == null || candidate == null || candidate.ref == null || candidate.npc == null) {
            return;
        }
        if (!resolutionService.isRecallCommand(context.command)) {
            return;
        }
        if (!CommandTravelSettings.isRecallTeleportingEnabled()) {
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
        String roleId = CompanionRoleIdResolver.resolveRoleId(candidate.ref, context.store);
        if ((roleId == null || roleId.isBlank()) && candidate.npc != null) {
            roleId = candidate.npc.getRoleName();
        }
        TwCompanionConfig.EffectiveSettings settings = TwCompanionConfig.resolveEffectiveForRole(roleId);
        double forceRelocateDistance = resolvePositiveDouble(
                settings.getRecallForceRelocateDistance(),
                context.recallForceRelocateDistance
        );
        if (distSq < forceRelocateDistance * forceRelocateDistance) {
            return;
        }
        double safeSpawnDistance = resolvePositiveDouble(
                settings.getRecallSafeSpawnDistance(),
                context.recallSafeSpawnDistance
        );
        Vector3d safePosition = companionPlacementService.computeSafeRecallPosition(
                context.playerRef,
                context.store,
                safeSpawnDistance,
                roleId,
                new Vector3d(npcPos)
        );
        if (safePosition == null) {
            return;
        }
        World world = context.player == null ? null : context.player.getWorld();
        UUID ownerUuid = context.player == null ? null : context.player.getUuid();
        if (world != null && ownerUuid != null && candidate.npc.getUuid() != null) {
            relocationService.queueRelocation(
                    world, candidate.npc.getUuid(), safePosition, ownerUuid,
                    true, true, null, null, 0L, new Vector3d(npcPos), null
            );
        }
    }

    private double resolvePositiveDouble(double configured, double fallback) {
        return configured > 0.0 ? configured : fallback;
    }

    record QueueResult(int queued) {
        private static QueueResult none() {
            return new QueueResult(0);
        }
    }
}
