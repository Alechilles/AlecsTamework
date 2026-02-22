package com.alechilles.alecstamework.items;

import com.hypixel.hytale.math.vector.Vector3d;
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
    private final CommandResolutionService resolutionService;
    private final CommandStepExecutionService stepExecutionService;
    private final CommandCompanionPlacementService companionPlacementService;

    CommandRelocationDispatchService(CommandNpcRelocationService relocationService,
                                     CommandLinkedNpcDeathService deathService,
                                     CommandResolutionService resolutionService,
                                     CommandStepExecutionService stepExecutionService,
                                     CommandCompanionPlacementService companionPlacementService) {
        this.relocationService = relocationService;
        this.deathService = deathService;
        this.resolutionService = resolutionService;
        this.stepExecutionService = stepExecutionService;
        this.companionPlacementService = companionPlacementService;
    }

    int queueRelocationsForUnloaded(Context context, List<LinkedNpcRecord> unloadedLinked) {
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

    void maybeRelocateLoadedRecallCandidate(Context context, Candidate candidate) {
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
}
