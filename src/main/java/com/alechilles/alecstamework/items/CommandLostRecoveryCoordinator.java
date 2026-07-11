package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.persistence.sqlite.LostRecoveryEnvelope;
import com.alechilles.alecstamework.persistence.sqlite.LostRecoveryLoadResult;
import com.alechilles.alecstamework.persistence.sqlite.LostRepository;
import com.alechilles.alecstamework.persistence.sqlite.NpcLiveAliasRepairRepository;
import com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Coordinates profile-first lost recovery across durable claims and world-thread projection work.
 *
 * <p>No player, entity reference, store, or component is carried through an asynchronous
 * persistence callback. Only stable IDs and immutable snapshot data cross threads; live objects
 * are resolved again inside {@link World#execute(Runnable)}.</p>
 */
final class CommandLostRecoveryCoordinator {
    private static final String MASTER_TARGET_SLOT = "MasterTarget";

    private final CommandNpcIdentityService identityService;
    private final LostRepository lostRepository;
    private final NpcRecoveryOperationRepository operationRepository;
    private final NpcLiveAliasRepairRepository liveAliasRepairRepository;
    private final CommandLinkedNpcInventoryRepairService inventoryRepairService;
    private final CommandCompanionPlacementService placementService;
    private final PlannedNpcProjectionSpawner projectionSpawner;
    private final PlannedNpcProjectionPostAddService postAddService;
    private final CommandStepExecutionService stepExecutionService;
    private final Set<String> inFlightOperationIds = ConcurrentHashMap.newKeySet();

    CommandLostRecoveryCoordinator(
            @Nonnull CommandNpcIdentityService identityService,
            @Nonnull LostRepository lostRepository,
            @Nonnull NpcRecoveryOperationRepository operationRepository,
            @Nonnull NpcLiveAliasRepairRepository liveAliasRepairRepository,
            @Nonnull CommandLinkedNpcInventoryRepairService inventoryRepairService,
            @Nonnull CommandCompanionPlacementService placementService,
            @Nonnull PlannedNpcProjectionSpawner projectionSpawner,
            @Nonnull PlannedNpcProjectionPostAddService postAddService,
            @Nonnull CommandStepExecutionService stepExecutionService) {
        this.identityService = identityService;
        this.lostRepository = lostRepository;
        this.operationRepository = operationRepository;
        this.liveAliasRepairRepository = liveAliasRepairRepository;
        this.inventoryRepairService = inventoryRepairService;
        this.placementService = placementService;
        this.projectionSpawner = projectionSpawner;
        this.postAddService = postAddService;
        this.stepExecutionService = stepExecutionService;
    }

    /** Starts an asynchronous recovery request from a world-thread command boundary. */
    void request(@Nonnull World world,
                 @Nonnull UUID playerUuid,
                 @Nonnull String toolId,
                 @Nonnull LinkedNpcRecord record,
                 double safeSpawnDistance,
                 @Nonnull Completion completion) {
        Request request = new Request(
                world, playerUuid, toolId, copyRecord(record), Math.max(2.0, safeSpawnDistance), completion);
        CompletableFuture.supplyAsync(() -> prepare(request.record()))
                .whenComplete((prepared, failure) -> {
                    if (failure != null || prepared == null) {
                        complete(request, Outcome.failed("Recovery identity check failed."));
                    } else if (prepared.mode() == PreparationMode.LIVE_ALIAS) {
                        repairLiveAlias(request, prepared);
                    } else if (prepared.mode() == PreparationMode.CLAIM_NEW) {
                        claim(request, prepared);
                    } else if (prepared.mode() == PreparationMode.RESUME) {
                        resume(request, prepared.envelope(), prepared.operation());
                    } else {
                        complete(request, Outcome.failed(prepared.failureMessage()));
                    }
                });
    }

    @Nonnull
    private Preparation prepare(@Nonnull LinkedNpcRecord record) {
        CommandNpcIdentityService.IdentityResolution identity = identityService.resolve(record);
        if (identity.status() != CommandNpcIdentityService.ResolutionStatus.RESOLVED
                || identity.profileId() == null) {
            return Preparation.failed(identityFailure(identity));
        }
        NpcRecoveryOperationRepository.RecoveryOperation active = loadActive(identity);
        if (identity.durableState().activeRecovery() && active == null) {
            return Preparation.failed("Recovery operation state is unreadable or conflicted.");
        }
        if (!identity.liveUuids().isEmpty()) {
            UUID liveUuid = identity.liveUuids().getFirst();
            if (active != null) {
                return operationOwnsLiveTarget(active, liveUuid)
                        ? prepareResume(identity, active)
                        : Preparation.failed("A different live profile alias conflicts with recovery.");
            }
            if (identity.durableState().captured() || identity.durableState().dead()
                    || identity.durableState().legacyCoop() || identity.durableState().managedCoop()) {
                return Preparation.failed("The live companion is owned by another lifecycle state.");
            }
            return Preparation.liveAlias(identity, liveUuid);
        }
        if (!identity.durableState().lostAwaitingRecovery()
                || identity.durableState().captured()
                || identity.durableState().dead()
                || identity.durableState().legacyCoop()
                || identity.durableState().managedCoop()) {
            return Preparation.failed("That companion is not safely awaiting lost recovery.");
        }
        if (active != null) {
            return prepareResume(identity, active);
        }
        LostRecoveryEnvelope envelope = loadVerifiedEnvelope(identity.profileId());
        if (!validEnvelope(identity, envelope)) {
            return Preparation.failed("The saved recovery state is incomplete or unverified.");
        }
        return Preparation.claim(identity, envelope);
    }

    @Nonnull
    private Preparation prepareResume(@Nonnull CommandNpcIdentityService.IdentityResolution identity,
                                      @Nonnull NpcRecoveryOperationRepository.RecoveryOperation operation) {
        LostRecoveryEnvelope envelope = loadVerifiedEnvelope(identity.profileId());
        if (!validEnvelope(identity, envelope) || !operationMatches(operation, envelope)) {
            return Preparation.failed("The pending recovery does not match the saved companion state.");
        }
        return Preparation.resume(identity, envelope, operation);
    }

    @Nullable
    private NpcRecoveryOperationRepository.RecoveryOperation loadActive(
            @Nonnull CommandNpcIdentityService.IdentityResolution identity) {
        if (!identity.durableState().activeRecovery()) {
            return null;
        }
        NpcRecoveryOperationRepository.LoadResult loaded =
                operationRepository.loadActiveByProfile(identity.profileId());
        return loaded.status() == NpcRecoveryOperationRepository.LoadStatus.FOUND
                ? loaded.operation() : null;
    }

    @Nullable
    private LostRecoveryEnvelope loadVerifiedEnvelope(@Nonnull String profileId) {
        LostRecoveryLoadResult loaded = lostRepository.loadAwaitingByProfile(profileId);
        return loaded.status() == LostRecoveryLoadResult.Status.FOUND ? loaded.envelope() : null;
    }

    private boolean validEnvelope(@Nonnull CommandNpcIdentityService.IdentityResolution identity,
                                  @Nullable LostRecoveryEnvelope envelope) {
        return envelope != null
                && envelope.profileId().equals(identity.profileId())
                && envelope.sourceNpcUuid() != null
                && envelope.hasVerifiedFullState()
                && envelope.fullSnapshot() != null
                && envelope.fullSnapshot().roleId() != null
                && !envelope.fullSnapshot().roleId().isBlank()
                && envelope.isAwaitingRecovery()
                && identity.aliases().contains(envelope.sourceNpcUuid())
                && java.util.Objects.equals(identity.currentNpcUuid(), envelope.currentNpcUuid());
    }

    private boolean operationMatches(@Nonnull NpcRecoveryOperationRepository.RecoveryOperation operation,
                                     @Nonnull LostRecoveryEnvelope envelope) {
        return operation.active()
                && operation.profileId().equals(envelope.profileId())
                && java.util.Objects.equals(operation.sourceNpcUuid(), envelope.sourceNpcUuid())
                && operation.plannedTargetUuid() != null
                && (operation.state() == NpcRecoveryOperationRepository.RecoveryState.SPAWN_CLAIMED
                    || operation.state() == NpcRecoveryOperationRepository.RecoveryState.PROJECTION_CREATED);
    }

    private boolean operationOwnsLiveTarget(
            @Nonnull NpcRecoveryOperationRepository.RecoveryOperation operation,
            @Nonnull UUID liveUuid) {
        return liveUuid.equals(operation.plannedTargetUuid())
                || liveUuid.equals(operation.actualTargetUuid());
    }

    private void repairLiveAlias(@Nonnull Request request, @Nonnull Preparation prepared) {
        CommandNpcIdentityService.IdentityResolution identity = prepared.identity();
        NpcLiveAliasRepairRepository.RepairRequest repair =
                new NpcLiveAliasRepairRepository.RepairRequest(
                        identity.profileId(), identity.currentNpcUuid(), prepared.liveUuid(),
                        List.of(request.toolId()));
        PersistenceWriteQueue.WriteSubmission<NpcLiveAliasRepairRepository.RepairResult> submission =
                liveAliasRepairRepository.repair(repair);
        submission.completion().whenComplete((outcome, failure) -> {
            if (!committed(outcome) || outcome.value() == null || !outcome.value().isSuccess()) {
                complete(request, Outcome.failed("The live companion identity could not be repaired."));
                return;
            }
            request.world().execute(() -> finishLiveAliasRepair(request, prepared.liveUuid()));
        });
    }

    private void finishLiveAliasRepair(@Nonnull Request request, @Nonnull UUID liveUuid) {
        CommandNpcIdentityService.IdentityResolution fresh = identityService.resolve(request.record());
        if (fresh.status() != CommandNpcIdentityService.ResolutionStatus.RESOLVED
                || fresh.profileId() == null
                || fresh.liveUuids().size() != 1
                || !liveUuid.equals(fresh.liveUuids().getFirst())) {
            completeNow(request, Outcome.failed("The live companion changed during repair."));
            return;
        }
        PlayerContext player = resolvePlayer(request.world(), request.playerUuid());
        if (player == null) {
            return;
        }
        inventoryRepairService.repair(player.player(), repairRequest(
                request, fresh.profileId(), liveUuid, fresh.aliases(), null, null));
        request.completion().complete(
                player.player(), Outcome.alreadyLive(displayName(request.record())));
    }

    private void claim(@Nonnull Request request, @Nonnull Preparation prepared) {
        String operationId = UUID.randomUUID().toString();
        UUID plannedTargetUuid = UUID.randomUUID();
        LostRecoveryEnvelope envelope = prepared.envelope();
        var claim = new NpcRecoveryOperationRepository.RecoveryClaim(
                operationId, envelope.profileId(), envelope.sourceNpcUuid(), plannedTargetUuid);
        operationRepository.claim(claim).completion().whenComplete((outcome, failure) -> {
            if (!committed(outcome) || outcome.value() == null) {
                complete(request, Outcome.failed("The recovery claim could not be committed."));
                return;
            }
            NpcRecoveryOperationRepository.ClaimResult result = outcome.value();
            NpcRecoveryOperationRepository.RecoveryOperation operation = result.operation();
            boolean accepted = result.status() == NpcRecoveryOperationRepository.ClaimStatus.CLAIMED
                    || result.status() == NpcRecoveryOperationRepository.ClaimStatus.REPLAYED;
            boolean adoptConcurrent = result.status() == NpcRecoveryOperationRepository.ClaimStatus.PROFILE_CONFLICT
                    && operation != null && operationMatches(operation, envelope);
            if ((!accepted && !adoptConcurrent) || operation == null) {
                complete(request, Outcome.failed("Another lifecycle operation blocked recovery."));
                return;
            }
            resume(request, envelope, operation);
        });
    }

    private void resume(@Nonnull Request request,
                        @Nonnull LostRecoveryEnvelope envelope,
                        @Nonnull NpcRecoveryOperationRepository.RecoveryOperation operation) {
        if (!inFlightOperationIds.add(operation.operationId())) {
            complete(request, Outcome.inProgress());
            return;
        }
        request.world().execute(() -> resumeOnWorld(request, envelope, operation));
    }

    private void resumeOnWorld(@Nonnull Request request,
                               @Nonnull LostRecoveryEnvelope preparedEnvelope,
                               @Nonnull NpcRecoveryOperationRepository.RecoveryOperation operation) {
        LostRecoveryEnvelope envelope = loadVerifiedEnvelope(operation.profileId());
        CommandNpcIdentityService.IdentityResolution identity = identityService.resolve(request.record());
        if (!validEnvelope(identity, envelope)
                || !operationMatches(operation, envelope)
                || !sameEnvelopeSource(preparedEnvelope, envelope)) {
            failOperationRequest(request, operation, "Recovery state changed before projection spawn.");
            return;
        }
        UUID targetUuid = operation.plannedTargetUuid();
        if (identity.status() != CommandNpcIdentityService.ResolutionStatus.RESOLVED
                || targetUuid == null) {
            failOperationRequest(request, operation, identityFailure(identity));
            return;
        }
        if (!identity.liveUuids().isEmpty()) {
            if (identity.liveUuids().size() != 1 || !targetUuid.equals(identity.liveUuids().getFirst())) {
                failOperationRequest(request, operation, "A conflicting profile alias is live.");
                return;
            }
            resumeVisibleProjection(request, envelope, operation);
            return;
        }
        if (operation.state() != NpcRecoveryOperationRepository.RecoveryState.SPAWN_CLAIMED) {
            failOperationRequest(request, operation, "The recorded projection is no longer loaded; no replacement was spawned.");
            return;
        }
        spawnClaimedProjection(request, envelope, operation);
    }

    private void spawnClaimedProjection(
            @Nonnull Request request,
            @Nonnull LostRecoveryEnvelope envelope,
            @Nonnull NpcRecoveryOperationRepository.RecoveryOperation operation) {
        PlayerContext player = resolvePlayer(request.world(), request.playerUuid());
        if (player == null) {
            inFlightOperationIds.remove(operation.operationId());
            return;
        }
        Vector3d source = firstVector(
                request.record().lastKnownPosition,
                envelope.metadata().lastKnownPosition(),
                request.record().homePosition,
                envelope.metadata().homePosition());
        Vector3d destination = placementService.computeSafeRespawnPosition(
                player.reference(), player.store(), request.safeSpawnDistance(),
                envelope.fullSnapshot().roleId(), source);
        if (destination == null) {
            failOperationRequest(request, operation, "Unable to find a safe recovery position.");
            return;
        }
        TameworkProjectionIdentityComponent marker = new TameworkProjectionIdentityComponent(
                operation.profileId(), operation.operationId(),
                TameworkProjectionIdentityComponent.KIND_RECOVERY, null,
                operation.sourceNpcUuid(), operation.generation());
        PlannedNpcProjectionSpawner.SpawnResult spawned = projectionSpawner.spawn(
                new PlannedNpcProjectionSpawner.SpawnRequest(
                        envelope.fullSnapshot().roleId(), operation.plannedTargetUuid(),
                        envelope.fullSnapshot(), marker, destination,
                        resolveSpawnRotation(player.store(), player.reference(), destination),
                        player.store()));
        if (!spawned.isSuccess()) {
            failOperationRequest(request, operation,
                    "Recovery projection could not be created (" + spawned.status() + ").");
            return;
        }
        recordProjection(request, envelope, operation,
                new DeferredProjectionWork(spawned.postAddWork(), destination));
    }

    private void resumeVisibleProjection(
            @Nonnull Request request,
            @Nonnull LostRecoveryEnvelope envelope,
            @Nonnull NpcRecoveryOperationRepository.RecoveryOperation operation) {
        PlayerContext player = resolvePlayer(request.world(), request.playerUuid());
        Ref<EntityStore> reference = request.world().getEntityRef(operation.plannedTargetUuid());
        if (player == null || reference == null || !reference.isValid()
                || !validProjectionMarker(reference, player.store(), operation)) {
            failOperationRequest(request, operation,
                    "The pending projection is live elsewhere or has conflicting identity metadata.");
            return;
        }
        if (operation.state() == NpcRecoveryOperationRepository.RecoveryState.SPAWN_CLAIMED) {
            recordProjection(request, envelope, operation, null);
        } else {
            finalizeProjection(request, envelope, operation, null);
        }
    }

    private void recordProjection(
            @Nonnull Request request,
            @Nonnull LostRecoveryEnvelope envelope,
            @Nonnull NpcRecoveryOperationRepository.RecoveryOperation operation,
            @Nullable DeferredProjectionWork deferredWork) {
        operationRepository.recordProjectionCreated(
                operation.operationId(), operation.profileId(),
                operation.plannedTargetUuid(), operation.generation())
                .completion().whenComplete((outcome, failure) -> {
                    if (!committed(outcome) || outcome.value() == null
                            || !transitionSucceeded(outcome.value())) {
                        failOperationRequest(request, operation,
                                "The visible recovery projection could not be recorded.");
                        return;
                    }
                    finalizeProjection(request, envelope, outcome.value().operation(), deferredWork);
                });
    }

    private void finalizeProjection(
            @Nonnull Request request,
            @Nonnull LostRecoveryEnvelope envelope,
            @Nonnull NpcRecoveryOperationRepository.RecoveryOperation operation,
            @Nullable DeferredProjectionWork deferredWork) {
        List<String> toolIds = toolIds(envelope, request.toolId());
        var finalization = new NpcRecoveryOperationRepository.RecoveryFinalization(
                operation.operationId(), operation.profileId(), operation.sourceNpcUuid(),
                operation.plannedTargetUuid(), operation.plannedTargetUuid(),
                operation.generation(), toolIds);
        operationRepository.finalizeRecovery(finalization).completion().whenComplete((outcome, failure) -> {
            if (!committed(outcome) || outcome.value() == null
                    || !transitionSucceeded(outcome.value())) {
                failOperationRequest(request, operation,
                        "Recovery finalization failed; the marked projection was left for reconciliation.");
                return;
            }
            request.world().execute(() -> finishFinalizedProjection(
                    request, envelope, outcome.value().operation(), deferredWork));
        });
    }

    private void finishFinalizedProjection(
            @Nonnull Request request,
            @Nonnull LostRecoveryEnvelope envelope,
            @Nonnull NpcRecoveryOperationRepository.RecoveryOperation operation,
            @Nullable DeferredProjectionWork deferredWork) {
        inFlightOperationIds.remove(operation.operationId());
        PlayerContext player = resolvePlayer(request.world(), request.playerUuid());
        Ref<EntityStore> reference = request.world().getEntityRef(operation.plannedTargetUuid());
        if (player == null || reference == null || !reference.isValid()) {
            return;
        }
        NPCEntity npc = player.store().getComponent(reference, NPCEntity.getComponentType());
        if (npc == null || !validProjectionMarker(reference, player.store(), operation)) {
            request.completion().complete(player.player(),
                    Outcome.failed("Recovery committed, but the projection could not be verified."));
            return;
        }
        if (deferredWork != null && deferredWork.postAddWork() != null) {
            postAddService.apply(reference, npc, player.store(), deferredWork.postAddWork());
            CommandCompanionSpawnPhysicsResetService.resetSpawnedCompanionPhysics(
                    reference, npc, player.store());
        }
        applyFollowBootstrap(reference, npc, player.reference(), player.store());
        Set<UUID> aliases = new LinkedHashSet<>();
        aliases.addAll(identityService.resolve(request.record()).aliases());
        aliases.add(operation.sourceNpcUuid());
        aliases.add(operation.plannedTargetUuid());
        Vector3d position = deferredWork != null ? deferredWork.position() : null;
        inventoryRepairService.repair(player.player(), repairRequest(
                request, operation.profileId(), operation.plannedTargetUuid(),
                aliases, envelope, position));
        request.completion().complete(player.player(),
                Outcome.recovered(displayName(envelope, request.record())));
    }

    private boolean validProjectionMarker(
            @Nonnull Ref<EntityStore> reference,
            @Nonnull Store<EntityStore> store,
            @Nonnull NpcRecoveryOperationRepository.RecoveryOperation operation) {
        ComponentType<EntityStore, TameworkProjectionIdentityComponent> type =
                TameworkProjectionIdentityComponent.getComponentType();
        TameworkProjectionIdentityComponent marker = type != null
                ? store.getComponent(reference, type) : null;
        return marker != null
                && marker.matches(TameworkProjectionIdentityComponent.KIND_RECOVERY,
                    operation.operationId(), operation.profileId())
                && java.util.Objects.equals(marker.getSourceNpcUuid(), operation.sourceNpcUuid());
    }

    @Nonnull
    private CommandLinkedNpcInventoryRepairService.RepairRequest repairRequest(
            @Nonnull Request request,
            @Nonnull String profileId,
            @Nonnull UUID currentUuid,
            @Nonnull Iterable<UUID> aliases,
            @Nullable LostRecoveryEnvelope envelope,
            @Nullable Vector3d position) {
        LinkedHashSet<UUID> aliasSet = new LinkedHashSet<>();
        aliases.forEach(aliasSet::add);
        CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot =
                envelope != null ? envelope.fullSnapshot() : null;
        Vector3d home = snapshot != null && snapshot.commandLinks() != null
                ? snapshot.commandLinks().getHomePosition()
                : request.record().homePosition;
        String name = snapshot != null && snapshot.npcName() != null
                ? snapshot.npcName().getName() : request.record().cachedDisplayName;
        return new CommandLinkedNpcInventoryRepairService.RepairRequest(
                profileId, currentUuid, aliasSet, position,
                request.world().getName(), home, name,
                request.record().cachedNameKey,
                snapshot != null ? snapshot.roleId() : request.record().cachedRoleId,
                request.record().cachedCommandState);
    }

    private void applyFollowBootstrap(@Nonnull Ref<EntityStore> npcRef,
                                      @Nonnull NPCEntity npc,
                                      @Nonnull Ref<EntityStore> playerRef,
                                      @Nonnull Store<EntityStore> store) {
        Role role = npc.getRole();
        if (role != null && role.getMarkedEntitySupport() != null) {
            role.getMarkedEntitySupport().setMarkedEntity("LockedTarget", null);
            role.getMarkedEntitySupport().setMarkedEntity(MASTER_TARGET_SLOT, playerRef);
        }
        if (!stepExecutionService.applyState(npcRef, npc, store, "Follow", null)) {
            stepExecutionService.applyState(npcRef, npc, store, "Idle", null);
        }
    }

    @Nonnull
    private Rotation3f resolveSpawnRotation(@Nonnull Store<EntityStore> store,
                                            @Nonnull Ref<EntityStore> playerRef,
                                            @Nonnull Vector3d spawnPosition) {
        TransformComponent transform = store.getComponent(
                playerRef, TransformComponent.getComponentType());
        if (transform == null) {
            return new Rotation3f();
        }
        Vector3d playerPosition = new Vector3d(transform.getPosition());
        Vector3d relative = new Vector3d(
                playerPosition.x - spawnPosition.x, 0.0, playerPosition.z - spawnPosition.z);
        return relative.lengthSquared() > 0.0001
                ? Rotation3f.lookAt(relative)
                : new Rotation3f(transform.getRotation());
    }

    @Nullable
    private PlayerContext resolvePlayer(@Nonnull World world, @Nonnull UUID playerUuid) {
        try {
            Store<EntityStore> store = world.getEntityStore().getStore();
            Ref<EntityStore> reference = world.getEntityRef(playerUuid);
            Player player = reference != null && reference.isValid()
                    ? store.getComponent(reference, Player.getComponentType()) : null;
            return player != null ? new PlayerContext(player, reference, store) : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private void failOperationRequest(@Nonnull Request request,
                                      @Nonnull NpcRecoveryOperationRepository.RecoveryOperation operation,
                                      @Nonnull String message) {
        inFlightOperationIds.remove(operation.operationId());
        complete(request, Outcome.failed(message));
    }

    private void complete(@Nonnull Request request, @Nonnull Outcome outcome) {
        request.world().execute(() -> completeNow(request, outcome));
    }

    private void completeNow(@Nonnull Request request, @Nonnull Outcome outcome) {
        PlayerContext player = resolvePlayer(request.world(), request.playerUuid());
        if (player != null) {
            request.completion().complete(player.player(), outcome);
        }
    }

    private boolean committed(@Nullable PersistenceWriteQueue.WriteOutcome<?> outcome) {
        return outcome != null && outcome.isCommitted();
    }

    private boolean transitionSucceeded(
            @Nonnull NpcRecoveryOperationRepository.TransitionResult result) {
        return result.status() == NpcRecoveryOperationRepository.TransitionStatus.APPLIED
                || result.status() == NpcRecoveryOperationRepository.TransitionStatus.REPLAYED;
    }

    private boolean sameEnvelopeSource(@Nonnull LostRecoveryEnvelope first,
                                       @Nonnull LostRecoveryEnvelope second) {
        return first.profileId().equals(second.profileId())
                && java.util.Objects.equals(first.sourceNpcUuid(), second.sourceNpcUuid())
                && first.fullSnapshotSha256().equals(second.fullSnapshotSha256());
    }

    @Nonnull
    private List<String> toolIds(@Nonnull LostRecoveryEnvelope envelope,
                                 @Nonnull String requestedToolId) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        TameworkCommandLinksComponent links = envelope.fullSnapshot().commandLinks();
        if (links != null && links.getToolIds() != null) {
            for (String toolId : links.getToolIds()) {
                if (toolId != null && !toolId.isBlank()) {
                    ids.add(toolId.trim());
                }
            }
        }
        ids.add(requestedToolId.trim());
        return List.copyOf(ids);
    }

    @Nonnull
    private String identityFailure(@Nullable CommandNpcIdentityService.IdentityResolution identity) {
        if (identity == null) {
            return "Companion identity could not be resolved.";
        }
        return switch (identity.status()) {
            case CONFLICT -> "Multiple live identities conflict; no recovery was attempted.";
            case FAILED -> "Loaded-world identity coverage is incomplete or failed.";
            case UNRESOLVED -> "The command record has no canonical companion profile.";
            case RESOLVED -> "The companion is not recoverable in its current state.";
        };
    }

    @Nullable
    private Vector3d firstVector(@Nullable Vector3d... values) {
        for (Vector3d value : values) {
            if (value != null) {
                return new Vector3d(value);
            }
        }
        return null;
    }

    @Nullable
    private String displayName(@Nonnull LinkedNpcRecord record) {
        return record.cachedDisplayName;
    }

    @Nullable
    private String displayName(@Nonnull LostRecoveryEnvelope envelope,
                               @Nonnull LinkedNpcRecord record) {
        if (envelope.fullSnapshot().npcName() != null
                && envelope.fullSnapshot().npcName().getName() != null
                && !envelope.fullSnapshot().npcName().getName().isBlank()) {
            return envelope.fullSnapshot().npcName().getName();
        }
        return displayName(record);
    }

    @Nonnull
    private LinkedNpcRecord copyRecord(@Nonnull LinkedNpcRecord record) {
        return new LinkedNpcRecord(
                record.npcUuid, record.profileId,
                record.lastKnownPosition != null ? new Vector3d(record.lastKnownPosition) : null,
                record.lastKnownWorldName,
                record.homePosition != null ? new Vector3d(record.homePosition) : null,
                record.cachedDisplayName, record.cachedNameKey, record.cachedRoleId,
                record.cachedCommandState, record.active, record.breedingEnabled, record.groupId);
    }

    enum OutcomeStatus {
        RECOVERED,
        ALREADY_LIVE_REPAIRED,
        IN_PROGRESS,
        FAILED
    }

    record Outcome(@Nonnull OutcomeStatus status,
                   @Nullable String companionName,
                   @Nullable String message) {
        static Outcome recovered(@Nullable String name) {
            return new Outcome(OutcomeStatus.RECOVERED, name, null);
        }

        static Outcome alreadyLive(@Nullable String name) {
            return new Outcome(OutcomeStatus.ALREADY_LIVE_REPAIRED, name, null);
        }

        static Outcome inProgress() {
            return new Outcome(OutcomeStatus.IN_PROGRESS, null, "Recovery is already in progress.");
        }

        static Outcome failed(@Nullable String message) {
            return new Outcome(OutcomeStatus.FAILED, null,
                    message == null || message.isBlank() ? "Recovery failed safely." : message);
        }
    }

    @FunctionalInterface
    interface Completion {
        void complete(@Nonnull Player player, @Nonnull Outcome outcome);
    }

    private enum PreparationMode {
        LIVE_ALIAS,
        CLAIM_NEW,
        RESUME,
        FAILED
    }

    private record Preparation(@Nonnull PreparationMode mode,
                               @Nullable CommandNpcIdentityService.IdentityResolution identity,
                               @Nullable UUID liveUuid,
                               @Nullable LostRecoveryEnvelope envelope,
                               @Nullable NpcRecoveryOperationRepository.RecoveryOperation operation,
                               @Nullable String failureMessage) {
        static Preparation liveAlias(CommandNpcIdentityService.IdentityResolution identity,
                                     UUID liveUuid) {
            return new Preparation(PreparationMode.LIVE_ALIAS, identity, liveUuid,
                    null, null, null);
        }

        static Preparation claim(CommandNpcIdentityService.IdentityResolution identity,
                                 LostRecoveryEnvelope envelope) {
            return new Preparation(PreparationMode.CLAIM_NEW, identity, null,
                    envelope, null, null);
        }

        static Preparation resume(CommandNpcIdentityService.IdentityResolution identity,
                                  LostRecoveryEnvelope envelope,
                                  NpcRecoveryOperationRepository.RecoveryOperation operation) {
            return new Preparation(PreparationMode.RESUME, identity, null,
                    envelope, operation, null);
        }

        static Preparation failed(String message) {
            return new Preparation(PreparationMode.FAILED, null, null,
                    null, null, message);
        }
    }

    private record Request(@Nonnull World world,
                           @Nonnull UUID playerUuid,
                           @Nonnull String toolId,
                           @Nonnull LinkedNpcRecord record,
                           double safeSpawnDistance,
                           @Nonnull Completion completion) {
    }

    private record PlayerContext(@Nonnull Player player,
                                 @Nonnull Ref<EntityStore> reference,
                                 @Nonnull Store<EntityStore> store) {
    }

    private record DeferredProjectionWork(
            @Nullable CoopResidentStateRestorer.PostAddWork postAddWork,
            @Nullable Vector3d position) {
        @Override
        public Vector3d position() {
            return position != null ? new Vector3d(position) : null;
        }
    }
}
