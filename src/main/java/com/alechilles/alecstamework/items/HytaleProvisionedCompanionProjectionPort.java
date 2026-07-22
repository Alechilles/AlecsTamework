package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.CompanionProvisioningProjectionStatus;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.api.PopulationAdmissionForcePolicy;
import com.alechilles.alecstamework.api.PopulationAdmissionIdentity;
import com.alechilles.alecstamework.api.PopulationAdmissionOperation;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationCompanionLifecycle;
import com.alechilles.alecstamework.api.ProvisionedCompanionTransition;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.CompanionSpawnAdmissionRequest;
import com.alechilles.alecstamework.ownership.CompanionSpawnPopulationAdmissionService;
import com.alechilles.alecstamework.ownership.CompanionSpawnPreparationResult;
import com.alechilles.alecstamework.ownership.OwnerPopulationEntry;
import com.alechilles.alecstamework.ownership.OwnerPopulationOperation;
import com.alechilles.alecstamework.ownership.OwnerPopulationRuntime;
import com.alechilles.alecstamework.ownership.PreparedCompanionSpawnBatch;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.provisioning.ProvisionedCompanionProjectionPort;
import com.alechilles.alecstamework.provisioning.ProvisioningPopulationBackend;
import com.alechilles.alecstamework.runtime.dispatch.LeaseBoundWorldDispatcher;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.npc.NPCPlugin;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Production projection bridge for intentionally null-NPC provisioned profiles.
 *
 * <p>Every live projection goes through the same deterministic planned-spawn admission and
 * pre-add holder mutation used by spawners and restore commands. World and chunk work is always
 * dispatched onto the owning world thread. A retry that observes the exact canonical profile
 * already active converges idempotently instead of creating another NPC.</p>
 */
public final class HytaleProvisionedCompanionProjectionPort
        implements ProvisionedCompanionProjectionPort {
    private static final String SOURCE = "companion_provisioning";

    private final OwnerPopulationRuntime ownerRuntime;
    private final NpcProfileRepository profiles;
    private final CompanionSpawnPopulationAdmissionService admission;
    @Nullable private final CommandNpcRelocationService relocation;
    private final CompanionProjectionSpawnPositionService spawnPosition =
            new CompanionProjectionSpawnPositionService();
    private final ConcurrentHashMap<UUID, PreparedProjection> prepared = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<
            ProvisioningPopulationBackend.AdmissionPreparation>> resumptions =
            new ConcurrentHashMap<>();

    public HytaleProvisionedCompanionProjectionPort(
            @Nonnull OwnerPopulationRuntime ownerRuntime,
            @Nonnull NpcProfileRepository profiles) {
        this(ownerRuntime, profiles, null);
    }

    public HytaleProvisionedCompanionProjectionPort(
            @Nonnull OwnerPopulationRuntime ownerRuntime,
            @Nonnull NpcProfileRepository profiles,
            @Nullable CommandNpcRelocationService relocation) {
        this.ownerRuntime = Objects.requireNonNull(ownerRuntime, "ownerRuntime");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.admission = ownerRuntime.companionSpawnAdmissionService();
        this.relocation = relocation;
    }

    @Override
    public boolean available() {
        return admission != null && ownerRuntime.populationGroupsReady();
    }

    @Nonnull
    @Override
    public CompletionStage<ProvisioningPopulationBackend.AdmissionPreparation> prepare(
            @Nonnull ProvisioningPopulationBackend.ActiveRequest request) {
        Objects.requireNonNull(request, "request");
        Source source = resolveSource(request.profileId(), request.ownerUuid(),
                request.roleId(), request.expectedProfileRevision(),
                CompanionLifecycleState.PROVISIONED_DORMANT);
        if (!source.allowed()) {
            return CompletableFuture.completedFuture(preparation(source.reason()));
        }
        if (source.alreadyActive()) {
            UUID operationId = stableProjectionOperationId(request.provisioningOperationId());
            prepared.put(operationId, PreparedProjection.alreadyActive(request.profileId()));
            return CompletableFuture.completedFuture(prepared(operationId,
                    "provisioned-companion-already-active"));
        }
        CompanionSpawnAdmissionRequest spawn = spawnRequest(
                request.profileId(), source.previousNpcUuid(), source.lifecycle(), request.ownerUuid(),
                request.destination(), request.provisioningOperationId());
        return prepareSpawn(spawn, request.profileId());
    }

    @Nonnull
    @Override
    public CompletionStage<ProvisioningPopulationBackend.AdmissionPreparation> resume(
            @Nonnull ProvisioningPopulationBackend.ActiveRequest request,
            @Nonnull UUID previousPopulationOperationId) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(previousPopulationOperationId, "previousPopulationOperationId");
        PreparedProjection cached = prepared.get(previousPopulationOperationId);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached.profileId().equals(request.profileId())
                    ? prepared(previousPopulationOperationId,
                    "provisioned-companion-projection-reacquired")
                    : new ProvisioningPopulationBackend.AdmissionPreparation(
                    ProvisioningPopulationBackend.AdmissionPreparation.Status.QUARANTINED,
                    "provisioned-companion-recovery-profile-conflict", null, null));
        }
        CompletableFuture<ProvisioningPopulationBackend.AdmissionPreparation> created =
                new CompletableFuture<>();
        CompletableFuture<ProvisioningPopulationBackend.AdmissionPreparation> concurrent =
                resumptions.putIfAbsent(previousPopulationOperationId, created);
        if (concurrent != null) {
            return concurrent;
        }
        resumeUncached(request, previousPopulationOperationId).whenComplete((result, failure) -> {
            resumptions.remove(previousPopulationOperationId, created);
            if (failure == null && result != null) created.complete(result);
            else created.completeExceptionally(failure == null
                    ? new IllegalStateException("provisioned-companion-resume-result-missing")
                    : failure);
        });
        return created;
    }

    @Nonnull
    private CompletionStage<ProvisioningPopulationBackend.AdmissionPreparation> resumeUncached(
            ProvisioningPopulationBackend.ActiveRequest request,
            UUID previousPopulationOperationId) {
        OwnerPopulationEntry owner = ownerRuntime.index().entry(request.profileId()).orElse(null);
        NpcProfileRepository.ProfileRecord profile = profiles.loadProfileById(request.profileId());
        if (owner == null || owner.ownerId() == null || profile == null
                || profile.ownerUuid() == null || profile.roleId() == null) {
            return CompletableFuture.completedFuture(recoveryPreparation(
                    ProvisioningPopulationBackend.AdmissionPreparation.Status.QUARANTINED,
                    "provisioned-companion-recovery-profile-missing"));
        }
        if (!owner.ownerId().equals(request.ownerUuid())
                || !profile.ownerUuid().equals(request.ownerUuid())) {
            return CompletableFuture.completedFuture(recoveryPreparation(
                    ProvisioningPopulationBackend.AdmissionPreparation.Status.QUARANTINED,
                    "provisioned-companion-recovery-owner-conflict"));
        }
        if (!profile.roleId().equals(request.roleId())) {
            return CompletableFuture.completedFuture(recoveryPreparation(
                    ProvisioningPopulationBackend.AdmissionPreparation.Status.QUARANTINED,
                    "provisioned-companion-recovery-role-conflict"));
        }

        CompanionSpawnAdmissionRequest spawn = spawnRequest(
                request.profileId(), null, CompanionLifecycleState.PROVISIONED_DORMANT,
                request.ownerUuid(), request.destination(), request.provisioningOperationId());
        UUID currentNpcUuid = ownerRuntime.identityResolver().currentNpcUuid(request.profileId())
                .orElse(profile.currentNpcUuid());
        if (owner.lifecycleState() == CompanionLifecycleState.ACTIVE
                || owner.lifecycleState() == CompanionLifecycleState.UNLOADED
                || owner.lifecycleState() == CompanionLifecycleState.RESTORING) {
            if (!isExactRecoveredProjection(spawn, currentNpcUuid)) {
                return CompletableFuture.completedFuture(recoveryPreparation(
                        ProvisioningPopulationBackend.AdmissionPreparation.Status.QUARANTINED,
                        "provisioned-companion-recovery-active-identity-conflict"));
            }
            prepared.put(previousPopulationOperationId,
                    PreparedProjection.alreadyActive(request.profileId()));
            return CompletableFuture.completedFuture(prepared(previousPopulationOperationId,
                    "provisioned-companion-recovery-already-active"));
        }
        if (owner.lifecycleState() != CompanionLifecycleState.PROVISIONED_DORMANT
                || owner.revision() != request.expectedProfileRevision()
                || currentNpcUuid != null) {
            return CompletableFuture.completedFuture(recoveryPreparation(
                    ProvisioningPopulationBackend.AdmissionPreparation.Status.QUARANTINED,
                    "provisioned-companion-recovery-source-conflict"));
        }
        return prepareSpawn(spawn, request.profileId(), previousPopulationOperationId);
    }

    @Override
    public ProvisioningPopulationBackend.ClaimResult claim(@Nonnull UUID populationOperationId) {
        PreparedProjection projection = prepared.get(populationOperationId);
        if (projection == null) {
            return new ProvisioningPopulationBackend.ClaimResult(
                    false, "provisioned-companion-preparation-missing", null);
        }
        if (projection.alreadyActive()) {
            projection.claimed = true;
            return new ProvisioningPopulationBackend.ClaimResult(
                    true, "provisioned-companion-already-active", null);
        }
        boolean claimed;
        try {
            claimed = admission.claimForSpawn(projection.batch(), 0);
        } catch (RuntimeException | LinkageError failure) {
            claimed = false;
        }
        projection.claimed = claimed;
        return new ProvisioningPopulationBackend.ClaimResult(claimed,
                claimed ? "provisioned-companion-projection-claimed"
                        : "provisioned-companion-projection-claim-denied", null);
    }

    @Nonnull
    @Override
    public CompletionStage<ProvisioningPopulationBackend.ProfileSnapshot> commit(
            @Nonnull UUID populationOperationId) {
        PreparedProjection projection = prepared.get(populationOperationId);
        if (projection == null || !projection.claimed) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "provisioned-companion-projection-not-claimed"));
        }
        if (projection.alreadyActive()) {
            prepared.remove(populationOperationId, projection);
            return snapshot(projection.profileId())
                    .<CompletionStage<ProvisioningPopulationBackend.ProfileSnapshot>>map(
                            CompletableFuture::completedFuture)
                    .orElseGet(() -> CompletableFuture.failedFuture(new IllegalStateException(
                            "provisioned-companion-active-profile-missing")));
        }
        return projectOnWorld(populationOperationId, projection);
    }

    @Nonnull
    @Override
    public CompletionStage<Void> cancel(@Nonnull UUID populationOperationId,
                                        @Nonnull String reason) {
        PreparedProjection projection = prepared.remove(populationOperationId);
        if (projection == null || projection.batch() == null || projection.claimed) {
            return CompletableFuture.completedFuture(null);
        }
        return admission.cancelRemainingAsync(projection.batch(), reason).thenApply(ignored -> null);
    }

    @Nonnull
    @Override
    public CompletionStage<ProvisioningPopulationBackend.TransitionOutcome> transition(
            @Nonnull ProvisioningPopulationBackend.TransitionRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.transition() == ProvisionedCompanionTransition.REVIVE_DORMANT) {
            return transitionToDormant(request);
        }
        CompanionLifecycleState expected = switch (request.transition()) {
            case ACTIVATE -> CompanionLifecycleState.PROVISIONED_DORMANT;
            case REVIVE_ACTIVE -> CompanionLifecycleState.DEAD_REVIVABLE;
            case REVIVE_DORMANT -> throw new IllegalStateException("handled above");
        };
        if (request.destination() == null) {
            return CompletableFuture.completedFuture(new ProvisioningPopulationBackend.TransitionOutcome(
                    ProvisioningPopulationBackend.TransitionOutcome.Status.UNAVAILABLE,
                    "provisioned-companion-active-destination-unavailable", null, null));
        }
        NpcProfileRepository.ProfileRecord profile = profiles.loadProfileById(request.profileId());
        if (profile == null || profile.ownerUuid() == null || profile.roleId() == null) {
            return completedTransition(ProvisioningPopulationBackend.TransitionOutcome.Status.DENIED,
                    "provisioned-companion-profile-missing", null);
        }
        if (!profile.ownerUuid().equals(request.actorUuid())) {
            return completedTransition(ProvisioningPopulationBackend.TransitionOutcome.Status.DENIED,
                    "provisioned-companion-owner-mismatch", null);
        }
        Source source = resolveSource(request.profileId(), profile.ownerUuid(), profile.roleId(),
                request.expectedProfileRevision(), expected);
        if (!source.allowed()) {
            return completedTransition(ProvisioningPopulationBackend.TransitionOutcome.Status.DENIED,
                    source.reason(), null);
        }
        if (source.alreadyActive()) {
            return recallActive(request, source.previousNpcUuid(), profile.roleId())
                    .thenApply(queued -> new ProvisioningPopulationBackend.TransitionOutcome(
                            ProvisioningPopulationBackend.TransitionOutcome.Status.IDEMPOTENT,
                            queued ? "provisioned-companion-active-recall-queued"
                                    : "provisioned-companion-already-active",
                            snapshot(request.profileId()).orElse(null), null));
        }
        CompanionSpawnAdmissionRequest spawn = spawnRequest(
                request.profileId(), source.previousNpcUuid(), source.lifecycle(), profile.ownerUuid(),
                request.destination(), request.operationId());
        return prepareSpawn(spawn, request.profileId()).thenCompose(preparation -> {
            if (preparation.status() != ProvisioningPopulationBackend.AdmissionPreparation.Status.PREPARED) {
                return completedTransition(ProvisioningPopulationBackend.TransitionOutcome.Status.DENIED,
                        preparation.reason(), null);
            }
            UUID populationOperationId = preparation.populationOperationId();
            ProvisioningPopulationBackend.ClaimResult claimed = claim(populationOperationId);
            if (!claimed.claimed()) {
                return cancel(populationOperationId, claimed.reason()).thenCompose(ignored ->
                        completedTransition(ProvisioningPopulationBackend.TransitionOutcome.Status.DENIED,
                                claimed.reason(), null));
            }
            return commit(populationOperationId).handle((committed, failure) ->
                    failure == null && committed != null
                            ? new ProvisioningPopulationBackend.TransitionOutcome(
                            ProvisioningPopulationBackend.TransitionOutcome.Status.COMMITTED,
                            "provisioned-companion-transition-committed", committed, null)
                            : new ProvisioningPopulationBackend.TransitionOutcome(
                            ProvisioningPopulationBackend.TransitionOutcome.Status.QUARANTINED,
                            "provisioned-companion-transition-outcome-ambiguous", null, null));
        });
    }

    @Nonnull
    private CompletionStage<Boolean> recallActive(
            ProvisioningPopulationBackend.TransitionRequest request,
            @Nullable UUID npcUuid,
            String roleId) {
        if (relocation == null || npcUuid == null || request.destination() == null) {
            return CompletableFuture.completedFuture(false);
        }
        Universe universe = Universe.get();
        World world = universe == null ? null
                : universe.getWorld(request.destination().worldName());
        if (world == null || !world.isAlive()) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> completion = new CompletableFuture<>();
        LeaseBoundWorldDispatcher.execute(world, () -> {
            try {
                Store<EntityStore> store = world.getEntityStore().getStore();
                Ref<EntityStore> actorRef = world.getEntityRef(request.actorUuid());
                Vector3d destination = new CommandCompanionPlacementService()
                        .computeSafeRecallPosition(actorRef, store, 5.0D, roleId, null);
                if (destination == null) {
                    completion.complete(false);
                    return;
                }
                Ref<EntityStore> npcRef = world.getEntityRef(npcUuid);
                TransformComponent transform = npcRef == null ? null : store.getComponent(
                        npcRef, TransformComponent.getComponentType());
                Vector3d source = transform == null || transform.getPosition() == null
                        ? null : new Vector3d(transform.getPosition());
                relocation.queueRelocation(
                        world, npcUuid, destination, request.actorUuid(), true, true,
                        null, null, 0L, source, null, true,
                        TwCompanionConfig.TransferFailurePolicy.QueueForRecall);
                completion.complete(true);
            } catch (RuntimeException | LinkageError failure) {
                completion.complete(false);
            }
        }, () -> completion.complete(false));
        return completion;
    }

    @Nonnull
    private CompletionStage<ProvisioningPopulationBackend.TransitionOutcome> transitionToDormant(
            ProvisioningPopulationBackend.TransitionRequest request) {
        NpcProfileRepository.ProfileRecord profile = profiles.loadProfileById(request.profileId());
        OwnerPopulationEntry owner = ownerRuntime.index().entry(request.profileId()).orElse(null);
        if (profile == null || profile.ownerUuid() == null || profile.roleId() == null
                || owner == null || owner.ownerId() == null) {
            return completedTransition(ProvisioningPopulationBackend.TransitionOutcome.Status.DENIED,
                    "provisioned-companion-profile-missing", null);
        }
        if (!profile.ownerUuid().equals(request.actorUuid())
                || !owner.ownerId().equals(request.actorUuid())) {
            return completedTransition(ProvisioningPopulationBackend.TransitionOutcome.Status.DENIED,
                    "provisioned-companion-owner-mismatch", null);
        }
        if (owner.lifecycleState() == CompanionLifecycleState.PROVISIONED_DORMANT) {
            return completedTransition(ProvisioningPopulationBackend.TransitionOutcome.Status.IDEMPOTENT,
                    "provisioned-companion-already-dormant", snapshot(request.profileId()).orElse(null));
        }
        if (owner.lifecycleState() != CompanionLifecycleState.DEAD_REVIVABLE) {
            return completedTransition(ProvisioningPopulationBackend.TransitionOutcome.Status.DENIED,
                    "provisioned-companion-lifecycle-mismatch", null);
        }
        UUID currentNpcUuid = ownerRuntime.identityResolver().currentNpcUuid(request.profileId())
                .orElse(profile.currentNpcUuid());
        if (currentNpcUuid == null) {
            return completedTransition(ProvisioningPopulationBackend.TransitionOutcome.Status.QUARANTINED,
                    "provisioned-companion-dead-identity-missing", null);
        }
        PopulationAdmissionRequest admissionRequest = dormantReviveAdmissionRequest(
                request, currentNpcUuid);
        return ownerRuntime.populationPolicyAuthority().tryAdmit(admissionRequest)
                .thenCompose(preparedDecision -> {
                    if (preparedDecision == null || !preparedDecision.accepted()
                            || preparedDecision.token() == null) {
                        return completedTransition(
                                ProvisioningPopulationBackend.TransitionOutcome.Status.DENIED,
                                preparedDecision == null
                                        ? "provisioned-companion-dormant-prepare-missing"
                                        : preparedDecision.reason(), null);
                    }
                    var claimed = ownerRuntime.populationPolicyAuthority()
                            .claimForApply(preparedDecision.token());
                    if (claimed == null || !claimed.accepted()) {
                        return ownerRuntime.populationPolicyAuthority()
                                .cancel(preparedDecision.token()).thenCompose(ignored ->
                                        completedTransition(
                                                ProvisioningPopulationBackend.TransitionOutcome.Status.DENIED,
                                                claimed == null
                                                        ? "provisioned-companion-dormant-claim-missing"
                                                        : claimed.reason(), null));
                    }
                    return ownerRuntime.populationPolicyAuthority().commit(preparedDecision.token())
                            .thenApply(committed -> {
                                if (committed == null || !committed.accepted()) {
                                    return new ProvisioningPopulationBackend.TransitionOutcome(
                                            ProvisioningPopulationBackend.TransitionOutcome.Status.QUARANTINED,
                                            committed == null
                                                    ? "provisioned-companion-dormant-commit-missing"
                                                    : committed.reason(), null, committed);
                                }
                                return new ProvisioningPopulationBackend.TransitionOutcome(
                                        ProvisioningPopulationBackend.TransitionOutcome.Status.COMMITTED,
                                        "provisioned-companion-revived-dormant",
                                        snapshot(request.profileId()).orElse(null), committed);
                            });
                });
    }

    @Nonnull
    private CompletionStage<ProvisioningPopulationBackend.AdmissionPreparation> prepareSpawn(
            CompanionSpawnAdmissionRequest request, String profileId) {
        return prepareSpawn(request, profileId, null);
    }

    @Nonnull
    private CompletionStage<ProvisioningPopulationBackend.AdmissionPreparation> prepareSpawn(
            CompanionSpawnAdmissionRequest request, String profileId,
            @Nullable UUID recoveredOperationId) {
        if (!available()) {
            return CompletableFuture.completedFuture(new ProvisioningPopulationBackend.AdmissionPreparation(
                    ProvisioningPopulationBackend.AdmissionPreparation.Status.UNAVAILABLE,
                    "provisioned-companion-projection-unavailable", null, null));
        }
        return admission.prepareAsync(request).handle((result, failure) -> {
            if (failure != null || result == null) {
                return new ProvisioningPopulationBackend.AdmissionPreparation(
                        ProvisioningPopulationBackend.AdmissionPreparation.Status.UNAVAILABLE,
                        "provisioned-companion-projection-prepare-failed", null, null);
            }
            if (!result.allowed() || result.preparedBatch() == null) {
                return new ProvisioningPopulationBackend.AdmissionPreparation(
                        ProvisioningPopulationBackend.AdmissionPreparation.Status.DENIED,
                        result.reason(), null, null);
            }
            UUID operationId = recoveredOperationId == null
                    ? result.preparedBatch().populationBatch().batchId()
                    : recoveredOperationId;
            prepared.put(operationId, new PreparedProjection(
                    profileId, request, result.preparedBatch(), false));
            return prepared(operationId, result.reason());
        });
    }

    @Nonnull
    private CompletionStage<ProvisioningPopulationBackend.ProfileSnapshot> projectOnWorld(
            UUID operationId, PreparedProjection projection) {
        CompletableFuture<ProvisioningPopulationBackend.ProfileSnapshot> completion =
                new CompletableFuture<>();
        Universe universe = Universe.get();
        World world = universe == null ? null : universe.getWorld(projection.request().worldName());
        if (world == null || !world.isAlive()) {
            completeProjectionFailure(operationId, projection, completion,
                    "provisioned-companion-world-unavailable", false);
            return completion;
        }
        long chunkIndex = ChunkUtil.indexChunk(
                projection.request().chunkX(), projection.request().chunkZ());
        world.getChunkAsync(chunkIndex).whenComplete((chunk, loadFailure) ->
                LeaseBoundWorldDispatcher.execute(world,
                        () -> spawnOnWorld(operationId, projection, world, chunk,
                                loadFailure, completion),
                        () -> completeProjectionFailure(operationId, projection, completion,
                                "provisioned-companion-world-dispatch-rejected", true)));
        return completion;
    }

    private void spawnOnWorld(
            UUID operationId,
            PreparedProjection projection,
            World world,
            @Nullable WorldChunk chunk,
            @Nullable Throwable loadFailure,
            CompletableFuture<ProvisioningPopulationBackend.ProfileSnapshot> completion) {
        NPCPlugin npcPlugin = NPCPlugin.get();
        int roleIndex = npcPlugin == null ? -1 : npcPlugin.getIndex(resolveRole(projection.profileId()));
        if (loadFailure != null || chunk == null || npcPlugin == null || roleIndex < 0
                || world.getEntityStore() == null) {
            completeProjectionFailure(operationId, projection, completion,
                    "provisioned-companion-spawn-context-unavailable", false);
            return;
        }
        String roleId = resolveRole(projection.profileId());
        CompanionProjectionSpawnPositionService.Placement placement = spawnPosition.resolve(
                world, projection.request().ownerId(), roleId,
                projection.request().chunkX(), projection.request().chunkZ(), chunk);
        EntityStore entityStore = world.getEntityStore();
        boolean started = new CompanionPreparedSpawnService(admission).spawnClaimedAndCommit(
                world, entityStore.getStore(), npcPlugin, roleIndex, placement.position(),
                placement.rotation(), projection.batch(), 0,
                new CompanionPreparedSpawnService.Callbacks() {
                    @Override
                    public void onSpawned(CompanionPreparedSpawnService.SpawnedCompanion live) {
                        prepared.remove(operationId, projection);
                        ProvisioningPopulationBackend.ProfileSnapshot snapshot =
                                snapshot(projection.profileId()).orElse(null);
                        if (snapshot == null) {
                            completion.completeExceptionally(new IllegalStateException(
                                    "provisioned-companion-profile-missing-after-spawn"));
                            return;
                        }
                        completion.complete(snapshot);
                    }

                    @Override
                    public void onDenied(String reason) {
                        completeProjectionFailure(operationId, projection, completion, reason, false);
                    }

                    @Override
                    public void onDurabilityDegraded(String reason) {
                        completeProjectionFailure(operationId, projection, completion, reason, true);
                    }

                    @Override
                    public void onWorldDispatchRejected(String reason) {
                        completeProjectionFailure(operationId, projection, completion, reason, true);
                    }

                    @Override
                    public void onTerminal() {
                        if (!completion.isDone()) {
                            completeProjectionFailure(operationId, projection, completion,
                                    "provisioned-companion-spawn-terminal-without-result", true);
                        }
                    }
                });
        if (!started && !completion.isDone()) {
            completeProjectionFailure(operationId, projection, completion,
                    "provisioned-companion-spawn-not-started", false);
        }
    }

    private void completeProjectionFailure(
            UUID operationId, PreparedProjection projection,
            CompletableFuture<?> completion, String reason, boolean ambiguous) {
        prepared.remove(operationId, projection);
        if (!ambiguous && projection.batch() != null) {
            admission.cancelRemainingAsync(projection.batch(), reason);
        }
        completion.completeExceptionally(new IllegalStateException(reason));
    }

    @Nonnull
    private Source resolveSource(String profileId, UUID ownerUuid, String roleId,
                                 long expectedRevision, CompanionLifecycleState expectedLifecycle) {
        OwnerPopulationEntry owner = ownerRuntime.index().entry(profileId).orElse(null);
        NpcProfileRepository.ProfileRecord profile = profiles.loadProfileById(profileId);
        if (owner == null || profile == null || owner.ownerId() == null
                || profile.ownerUuid() == null || profile.roleId() == null) {
            return Source.denied("provisioned-companion-profile-missing");
        }
        if (!owner.ownerId().equals(ownerUuid) || !profile.ownerUuid().equals(ownerUuid)) {
            return Source.denied("provisioned-companion-owner-mismatch");
        }
        if (!profile.roleId().equals(roleId)) {
            return Source.denied("provisioned-companion-role-mismatch");
        }
        if (owner.revision() != expectedRevision) {
            return Source.denied("provisioned-companion-profile-revision-mismatch");
        }
        UUID current = ownerRuntime.identityResolver().currentNpcUuid(profileId)
                .orElse(profile.currentNpcUuid());
        if ((owner.lifecycleState() == CompanionLifecycleState.ACTIVE
                || owner.lifecycleState() == CompanionLifecycleState.UNLOADED)
                && current != null) {
            return Source.alreadyActive(current, owner.lifecycleState());
        }
        if (owner.lifecycleState() != expectedLifecycle) {
            return Source.denied("provisioned-companion-lifecycle-mismatch");
        }
        return Source.allowed(current, expectedLifecycle);
    }

    @Nonnull
    private Optional<ProvisioningPopulationBackend.ProfileSnapshot> snapshot(String profileId) {
        OwnerPopulationEntry owner = ownerRuntime.index().entry(profileId).orElse(null);
        NpcProfileRepository.ProfileRecord profile = profiles.loadProfileById(profileId);
        if (owner == null || owner.ownerId() == null || profile == null || profile.roleId() == null) {
            return Optional.empty();
        }
        PopulationCompanionLifecycle lifecycle = PopulationCompanionLifecycle.valueOf(
                owner.lifecycleState().name());
        UUID current = ownerRuntime.identityResolver().currentNpcUuid(profileId)
                .orElse(profile.currentNpcUuid());
        CompanionProvisioningProjectionStatus status = switch (lifecycle) {
            case ACTIVE, UNLOADED, RESTORING -> CompanionProvisioningProjectionStatus.ACTIVE;
            case PROVISIONED_DORMANT -> CompanionProvisioningProjectionStatus.NOT_REQUESTED;
            default -> CompanionProvisioningProjectionStatus.FAILED_RECOVERABLE;
        };
        return Optional.of(new ProvisioningPopulationBackend.ProfileSnapshot(
                profileId, owner.ownerId(), profile.roleId(), lifecycle, status,
                current, owner.revision(), profile.updatedAtMs()));
    }

    private String resolveRole(String profileId) {
        NpcProfileRepository.ProfileRecord profile = profiles.loadProfileById(profileId);
        return profile == null || profile.roleId() == null ? "" : profile.roleId();
    }

    @Nonnull
    static CompanionSpawnAdmissionRequest spawnRequest(
            String profileId, @Nullable UUID previousNpcUuid,
            CompanionLifecycleState lifecycle, UUID ownerUuid,
            PopulationAdmissionLocation destination, UUID operationId) {
        return new CompanionSpawnAdmissionRequest(
                profileId, previousNpcUuid, lifecycle, false, ownerUuid, null,
                destination.worldName(), destination.chunkX(), destination.chunkZ(),
                OwnerPopulationOperation.RESTORE, SOURCE,
                "companion-provisioning:" + operationId, false);
    }

    @Nonnull
    static PopulationAdmissionRequest dormantReviveAdmissionRequest(
            ProvisioningPopulationBackend.TransitionRequest request, UUID currentNpcUuid) {
        return new PopulationAdmissionRequest(
                new PopulationAdmissionIdentity(request.profileId(), null,
                        "provisioned-revive-dormant:" + request.operationId()),
                currentNpcUuid, request.expectedProfileRevision(), request.actorUuid(),
                request.actorUuid(), null, null, PopulationAdmissionOperation.LIFECYCLE_CHANGE,
                1, PopulationAdmissionForcePolicy.ENFORCE,
                PopulationCompanionLifecycle.PROVISIONED_DORMANT);
    }

    /** Recovery may accept live evidence only for this operation's deterministic NPC UUID. */
    static boolean isExactRecoveredProjection(
            CompanionSpawnAdmissionRequest request, @Nullable UUID currentNpcUuid) {
        return currentNpcUuid != null
                && currentNpcUuid.equals(
                CompanionSpawnPopulationAdmissionService.plannedNpcUuid(request));
    }

    private static UUID stableProjectionOperationId(UUID provisioningOperationId) {
        return UUID.nameUUIDFromBytes(("companion-projection:" + provisioningOperationId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static ProvisioningPopulationBackend.AdmissionPreparation prepared(
            UUID operationId, String reason) {
        return new ProvisioningPopulationBackend.AdmissionPreparation(
                ProvisioningPopulationBackend.AdmissionPreparation.Status.PREPARED,
                reason, operationId, null);
    }

    private static ProvisioningPopulationBackend.AdmissionPreparation preparation(String reason) {
        ProvisioningPopulationBackend.AdmissionPreparation.Status status =
                reason.endsWith("unavailable")
                        ? ProvisioningPopulationBackend.AdmissionPreparation.Status.UNAVAILABLE
                        : ProvisioningPopulationBackend.AdmissionPreparation.Status.DENIED;
        return new ProvisioningPopulationBackend.AdmissionPreparation(status, reason, null, null);
    }

    private static ProvisioningPopulationBackend.AdmissionPreparation recoveryPreparation(
            ProvisioningPopulationBackend.AdmissionPreparation.Status status, String reason) {
        return new ProvisioningPopulationBackend.AdmissionPreparation(status, reason, null, null);
    }

    private static CompletionStage<ProvisioningPopulationBackend.TransitionOutcome> completedTransition(
            ProvisioningPopulationBackend.TransitionOutcome.Status status,
            String reason, @Nullable ProvisioningPopulationBackend.ProfileSnapshot profile) {
        return CompletableFuture.completedFuture(new ProvisioningPopulationBackend.TransitionOutcome(
                status, reason, profile, null));
    }

    private static final class PreparedProjection {
        private final String profileId;
        @Nullable private final CompanionSpawnAdmissionRequest request;
        @Nullable private final PreparedCompanionSpawnBatch batch;
        private final boolean alreadyActive;
        private volatile boolean claimed;

        private PreparedProjection(String profileId,
                                   @Nullable CompanionSpawnAdmissionRequest request,
                                   @Nullable PreparedCompanionSpawnBatch batch,
                                   boolean alreadyActive) {
            this.profileId = profileId;
            this.request = request;
            this.batch = batch;
            this.alreadyActive = alreadyActive;
        }

        static PreparedProjection alreadyActive(String profileId) {
            return new PreparedProjection(profileId, null, null, true);
        }

        String profileId() { return profileId; }
        CompanionSpawnAdmissionRequest request() { return request; }
        PreparedCompanionSpawnBatch batch() { return batch; }
        boolean alreadyActive() { return alreadyActive; }
    }

    private record Source(boolean allowed, boolean alreadyActive, @Nonnull String reason,
                          @Nullable UUID previousNpcUuid,
                          @Nonnull CompanionLifecycleState lifecycle) {
        static Source denied(String reason) {
            return new Source(false, false, reason, null,
                    CompanionLifecycleState.UNKNOWN_DORMANT);
        }

        static Source allowed(@Nullable UUID previousNpcUuid, CompanionLifecycleState lifecycle) {
            return new Source(true, false, "provisioned-companion-source-ready",
                    previousNpcUuid, lifecycle);
        }

        static Source alreadyActive(UUID currentNpcUuid, CompanionLifecycleState lifecycle) {
            return new Source(true, true, "provisioned-companion-already-active",
                    currentNpcUuid, lifecycle);
        }
    }
}
