package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Coordinates relocation dispatch for loaded and unloaded linked companions.
 *
 * <p>With the companion index set, an unloaded recall follows {@link RecallRoute}: a LIVE
 * companion in the player's world is moved by the relocation service, one in another world is
 * restored near the player, and any other record is skipped. Relocations never use the old
 * cross-world transfer.
 */
final class CommandRelocationDispatchService {
    private final CommandNpcRelocationService relocationService;
    private final CommandResolutionService resolutionService;
    private final CommandStepExecutionService stepExecutionService;
    private final CommandCompanionPlacementService companionPlacementService;
    @Nullable
    private final CompanionQueries companions;
    @Nullable
    private volatile CompanionRestoreRecallSink recallRestore;

    CommandRelocationDispatchService(CommandNpcRelocationService relocationService,
                                     CommandResolutionService resolutionService,
                                     CommandStepExecutionService stepExecutionService,
                                     CommandCompanionPlacementService companionPlacementService,
                                     @Nullable CompanionQueries companions) {
        this.relocationService = relocationService;
        this.resolutionService = resolutionService;
        this.stepExecutionService = stepExecutionService;
        this.companionPlacementService = companionPlacementService;
        this.companions = companions;
    }

    /** Sets where a recall of a companion in another world goes; until then such a recall is skipped. */
    void setRecallRestore(@Nullable CompanionRestoreRecallSink recallRestore) {
        this.recallRestore = recallRestore;
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
        CompanionDestinationAdmissionPolicy.Decision destinationDecision =
                CompanionDestinationAdmissionPolicy.assess(world);
        if (recall && destinationDecision
                != CompanionDestinationAdmissionPolicy.Decision.ALLOWED) {
            return QueueResult.rejected(destinationDecision);
        }
        int queued = 0;
        for (LinkedNpcRecord record : unloadedLinked) {
            if (record == null || record.npcUuid == null) {
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
            CompanionRecord indexed = indexRecord(record);
            RecallRoute route = companions == null ? RecallRoute.LOAD_AND_MOVE
                    : RecallRoute.decide(indexed, isLoadedIn(indexed, world), world.getName());
            if (route == RecallRoute.REFUSE) {
                continue;
            }
            if (route == RecallRoute.RESTORE) {
                if (restoreNearPlayer(context, ownerUuid, indexed)) {
                    queued++;
                }
                continue;
            }
            Vector3d sourceHint = indexed != null
                    ? new Vector3d(indexed.location().x(), indexed.location().y(), indexed.location().z())
                    : record.lastKnownPosition != null ? record.lastKnownPosition : record.homePosition;
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
            // The index knows the current body; a cached row can name a replaced one.
            UUID npcUuid = indexed != null && indexed.currentNpcUuid() != null
                    ? indexed.currentNpcUuid() : record.npcUuid;
            relocationService.queueRelocation(
                    world,
                    npcUuid,
                    safeDestination,
                    ownerUuid,
                    true,
                    true,
                    postRelocationState.state,
                    postRelocationState.subState,
                    0L,
                    sourceHint,
                    record.homePosition,
                    false,
                    settings.getOnTransferFailure(),
                    null,
                    true
            );
            queued++;
        }
        return new QueueResult(
                queued,
                CompanionDestinationAdmissionPolicy.Decision.ALLOWED
        );
    }

    /** The index record behind a row: by profile id, else by the row's NPC UUID. */
    @Nullable
    private CompanionRecord indexRecord(LinkedNpcRecord record) {
        if (companions == null) {
            return null;
        }
        UUID profileId = parseUuid(record.profileId);
        CompanionRecord indexed = profileId == null ? null : companions.get(profileId);
        return indexed != null ? indexed : companions.byNpcUuid(record.npcUuid);
    }

    private boolean isLoadedIn(@Nullable CompanionRecord indexed, World world) {
        return indexed != null && companions != null && world.getName().equals(indexed.location().world())
                && companions.loadedBody(indexed.profileId()) != null;
    }

    /**
     * Starts a restore of the owner's companion at a placement frozen now, on the player's world
     * thread. Returns whether the restore started; its result arrives later and is logged.
     */
    private boolean restoreNearPlayer(Context context, @Nullable UUID ownerUuid, CompanionRecord indexed) {
        CompanionRestoreRecallSink restore = recallRestore;
        if (restore == null || ownerUuid == null || !ownerUuid.equals(indexed.ownerUuid())) {
            return false;
        }
        TwCompanionConfig.EffectiveSettings settings =
                TwCompanionConfig.resolveEffectiveForRole(indexed.roleId());
        double safeSpawnDistance = resolvePositiveDouble(
                settings.getRecallSafeSpawnDistance(),
                context.recallSafeSpawnDistance
        );
        CompanionSpawnPlacement placement = companionPlacementService.computeRestorationPlacement(
                context.playerRef, context.store, safeSpawnDistance, indexed.roleId(), null);
        if (placement == null) {
            return false;
        }
        restore.restoreNear(ownerUuid, indexed.profileId(), placement);
        return true;
    }

    @Nullable
    private static UUID parseUuid(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    void maybeRelocateLoadedRecallCandidate(Context context, Candidate candidate) {
        if (context == null || candidate == null || candidate.ref == null || candidate.npc == null) {
            return;
        }
        if (context.config.usesBondedCompanionRoster()) {
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

    record QueueResult(
            int queued,
            CompanionDestinationAdmissionPolicy.Decision destinationDecision
    ) {
        private static QueueResult none() {
            return new QueueResult(
                    0,
                    CompanionDestinationAdmissionPolicy.Decision.ALLOWED
            );
        }

        private static QueueResult rejected(
                CompanionDestinationAdmissionPolicy.Decision decision
        ) {
            return new QueueResult(0, decision);
        }
    }
}
