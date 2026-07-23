package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.OwnerPopulationEntry;
import com.alechilles.alecstamework.ownership.OwnerPopulationRuntime;
import com.alechilles.alecstamework.ownership.PreparedCompanionSpawnBatch;
import com.alechilles.alecstamework.persistence.sqlite.CommandTimedSummonRepository;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceReadExecutor;
import com.alechilles.alecstamework.runtime.dispatch.LeaseBoundWorldDispatcher;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** World-thread implementation for durable command-roster storage and front-first summon. */
public final class HytaleCommandTimedSummonProjectionPort
        implements CommandTimedSummoningService.ProjectionPort {
    private static final double FRONT_DISTANCE = 5.0D;

    private final OwnerPopulationRuntime ownerRuntime;
    private final NpcProfileRepository profiles;
    private final CommandTimedSummonRepository repository;
    private final PersistenceReadExecutor reads;
    private final CommandTimedSummonPopulationPort population;
    private final CoopResidentStateSnapshotService snapshots;
    private final CoopResidentStateSnapshotCodec snapshotCodec = new CoopResidentStateSnapshotCodec();
    private final CoopResidentStateRestorer restorer = new CoopResidentStateRestorer();
    private final PlannedNpcProjectionPostAddService postAdd = new PlannedNpcProjectionPostAddService();
    private final CommandCompanionPlacementService placement = new CommandCompanionPlacementService();
    private final CompanionProjectionSpawnPositionService spawnPosition =
            new CompanionProjectionSpawnPositionService();
    private final ConcurrentHashMap<String, CompletableFuture<Void>> lifecycleChains =
            new ConcurrentHashMap<>();
    @Nullable private volatile CommandTimedSummoningService lifecycleService;

    public HytaleCommandTimedSummonProjectionPort(
            @Nonnull OwnerPopulationRuntime ownerRuntime,
            @Nonnull NpcProfileRepository profiles,
            @Nonnull CommandTimedSummonRepository repository,
            @Nonnull PersistenceReadExecutor reads,
            @Nonnull CommandTimedSummonPopulationPort population,
            @Nonnull CoopResidentStateSnapshotService snapshots) {
        this.ownerRuntime = Objects.requireNonNull(ownerRuntime, "ownerRuntime");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.population = Objects.requireNonNull(population, "population");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    public void installLifecycleService(@Nullable CommandTimedSummoningService service) {
        lifecycleService = service;
    }

    public boolean available() {
        return ownerRuntime.populationGroupsReady()
                && population.spawnAdmissions() != null
                && TameworkProjectionIdentityComponent.getComponentType() != null;
    }

    /** World-removal hook: freeze a complete restorable state before a chunk unloads. */
    public void captureUnloadedSnapshot(
            @Nonnull UUID npcUuid,
            @Nonnull CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot) {
        String profileId = ownerRuntime.identityResolver().resolveProfileId(npcUuid).orElse(null);
        if (profileId == null) return;
        String json = snapshotCodec.encode(snapshot);
        enqueueLifecycle(profileId, () -> repository.saveProjectedSnapshotAsync(
                        profileId, npcUuid, json, sha256(json), System.currentTimeMillis())
                .completion().thenCompose(outcome -> outcome != null && outcome.isCommitted()
                        ? transitionProjection(profileId, null, null, false)
                        : CompletableFuture.failedFuture(new IllegalStateException(
                        "timed-summon-unload-snapshot-not-committed"))));
    }

    /** Load callback consumes marker identity synchronously, then performs persistence work off-thread. */
    public void projectionLoaded(@Nonnull UUID npcUuid,
                                 @Nonnull TameworkProjectionIdentityComponent marker) {
        String profileId = marker.getProfileId();
        String familyId = marker.getSlotKey();
        String sessionId = marker.getOperationId();
        if (profileId == null || profileId.isBlank() || familyId == null || familyId.isBlank()
                || sessionId == null || sessionId.isBlank()) return;
        enqueueLifecycle(profileId,
                () -> transitionProjection(profileId, familyId, sessionId, true));
    }

    private CompletionStage<?> transitionProjection(
            String profileId, @Nullable String expectedFamilyId,
            @Nullable String expectedSessionId, boolean loaded) {
        return reads.submit(() -> repository.findProjectedSession(profileId)).thenCompose(session -> {
            CommandTimedSummoningService service = lifecycleService;
            if (service == null || session == null || session.summonSessionId() == null
                    || (expectedFamilyId != null
                    && !expectedFamilyId.equals(session.commandFamilyId()))
                    || (expectedSessionId != null
                    && !expectedSessionId.equals(session.summonSessionId()))) {
                return CompletableFuture.completedFuture(null);
            }
            if (loaded && session.state()
                    == com.alechilles.alecstamework.persistence.sqlite.CommandTimedSummonSessionRecord.State.ACTIVE) {
                return CompletableFuture.completedFuture(null);
            }
            if (!loaded && session.state()
                    == com.alechilles.alecstamework.persistence.sqlite.CommandTimedSummonSessionRecord.State.UNLOADED) {
                return CompletableFuture.completedFuture(null);
            }
            return service.setProjectionLoaded(
                    session.ownerUuid(), session.commandFamilyId(), session.profileId(),
                    session.rowRevision(), session.summonSessionId(), loaded,
                    System.currentTimeMillis());
        });
    }

    private void enqueueLifecycle(String profileId, Supplier<CompletionStage<?>> work) {
        lifecycleChains.compute(profileId, (ignored, prior) -> {
            CompletableFuture<Void> base = prior == null
                    ? CompletableFuture.completedFuture(null)
                    : prior.handle((value, failure) -> null);
            CompletableFuture<Void> next = base
                    .thenCompose(value -> work.get().thenApply(ignoredValue -> (Void) null))
                    .handle((value, failure) -> (Void) null)
                    .toCompletableFuture();
            next.whenComplete((value, failure) -> lifecycleChains.remove(profileId, next));
            return next;
        });
    }

    /** Rehydrates tombstones so an old persisted chunk cannot resurrect a stored projection. */
    public CompletionStage<Void> recoverRetirementTombstones() {
        return reads.submit(() -> {
            CommandTimedProjectionRetirementIndex.clear();
            for (CommandTimedSummonRepository.ProjectionSnapshot snapshot
                    : repository.loadProjectionSnapshots()) {
                var session = repository.findSession(
                        snapshot.ownerUuid(), snapshot.commandFamilyId(), snapshot.profileId());
                if (session != null && session.state()
                        == com.alechilles.alecstamework.persistence.sqlite.CommandTimedSummonSessionRecord.State.ROSTER_STORED) {
                    CommandTimedProjectionRetirementIndex.retire(
                            snapshot.sourceNpcUuid(), snapshot.profileId(), snapshot.commandFamilyId(),
                            "startup-roster-storage");
                }
            }
            return null;
        });
    }

    @Nonnull
    @Override
    public CompletionStage<CommandTimedSummoningService.SpawnPlan> planSpawnInFront(
            @Nonnull UUID ownerUuid, @Nonnull String profileId) {
        return reads.submit(() -> role(profileId)).thenCompose(roleId -> {
            if (!available() || roleId == null) {
                return CompletableFuture.completedFuture(new CommandTimedSummoningService.SpawnPlan(
                        false, null, null, null, "timed-summon-projection-unavailable"));
            }
            List<World> worlds = worlds();
            if (worlds.isEmpty()) return noOwnerPlan();
            CompletableFuture<CommandTimedSummoningService.SpawnPlan> completion = new CompletableFuture<>();
            AtomicInteger remaining = new AtomicInteger(worlds.size());
            for (World world : worlds) {
                LeaseBoundWorldDispatcher.execute(world, () -> {
                    if (!completion.isDone()) {
                        WorldPlayerResolver.ResolvedPlayer player = WorldPlayerResolver.resolve(world, ownerUuid);
                        Vector3d target = player == null ? null : placement.computeSafeRecallPosition(
                                player.ref(), player.store(), FRONT_DISTANCE, roleId, null);
                        if (target != null) {
                            completion.complete(new CommandTimedSummoningService.SpawnPlan(
                                    true, world.getName(), ChunkUtil.chunkCoordinate(target.x),
                                    ChunkUtil.chunkCoordinate(target.z), "front-placement-ready"));
                        }
                    }
                    completeMissingOwner(completion, remaining);
                }, () -> completeMissingOwner(completion, remaining));
            }
            return completion;
        });
    }

    @Nonnull
    @Override
    public CompletionStage<CommandTimedSummoningService.ProjectionResult> spawn(
            @Nonnull CommandTimedSummoningService.SpawnPlan plan,
            @Nonnull CommandTimedSummoningService.PopulationContext context,
            @Nonnull CommandTimedSummoningService.PopulationReservation reservation,
            @Nonnull String summonSessionId) {
        PreparedCompanionSpawnBatch batch = population.claimedBatch(reservation.populationOperationId());
        World world = world(plan.destinationWorld());
        if (!available() || batch == null || world == null
                || plan.destinationChunkX() == null || plan.destinationChunkZ() == null) {
            return projection(CommandTimedSummoningService.ProjectionOutcome.NOT_APPLIED,
                    null, "timed-summon-spawn-context-unavailable");
        }
        return reads.submit(() -> new SpawnState(
                        context.roleId() != null ? context.roleId() : role(context.profileId()),
                        loadSnapshot(context)))
                .thenCompose(state -> spawnPrepared(
                        plan, context, batch, summonSessionId, world, state))
                .exceptionally(failure -> notApplied("timed-summon-snapshot-invalid"));
    }

    private CompletionStage<CommandTimedSummoningService.ProjectionResult> spawnPrepared(
            CommandTimedSummoningService.SpawnPlan plan,
            CommandTimedSummoningService.PopulationContext context,
            PreparedCompanionSpawnBatch batch,
            String summonSessionId,
            World world,
            SpawnState state) {
        if (state.roleId() == null) return projection(
                CommandTimedSummoningService.ProjectionOutcome.NOT_APPLIED,
                null, "timed-summon-spawn-context-unavailable");
        CompletableFuture<CommandTimedSummoningService.ProjectionResult> completion =
                new CompletableFuture<>();
        long chunkIndex = ChunkUtil.indexChunk(plan.destinationChunkX(), plan.destinationChunkZ());
        world.getChunkAsync(chunkIndex).whenComplete((chunk, failure) ->
                LeaseBoundWorldDispatcher.execute(world,
                        () -> spawnOnWorld(world, chunk, failure, plan, context, batch, state.roleId(),
                                summonSessionId, state.snapshot(), completion),
                        () -> completion.complete(ambiguous("timed-summon-world-dispatch-rejected"))));
        return completion;
    }

    private void spawnOnWorld(World world, @Nullable WorldChunk chunk, @Nullable Throwable failure,
                              CommandTimedSummoningService.SpawnPlan plan,
                              CommandTimedSummoningService.PopulationContext context,
                              PreparedCompanionSpawnBatch batch, String roleId, String sessionId,
                              SnapshotState snapshot,
                              CompletableFuture<CommandTimedSummoningService.ProjectionResult> completion) {
        NPCPlugin npcPlugin = NPCPlugin.get();
        int roleIndex = npcPlugin == null ? -1 : npcPlugin.getIndex(roleId);
        if (failure != null || chunk == null || npcPlugin == null || roleIndex < 0
                || world.getEntityStore() == null) {
            completion.complete(notApplied("timed-summon-spawn-context-unavailable"));
            return;
        }
        CompanionProjectionSpawnPositionService.Placement destination = spawnPosition.resolve(
                world, context.ownerUuid(), roleId, plan.destinationChunkX(),
                plan.destinationChunkZ(), chunk);
        AtomicReference<CoopResidentStateRestorer.PostAddWork> postWork = new AtomicReference<>();
        TameworkProjectionIdentityComponent marker = marker(
                context, sessionId, snapshot.sourceNpcUuid(), batch.spawn(0).plannedNpcUuid());
        boolean started = new CompanionPreparedSpawnService(population.spawnAdmissions())
                .spawnClaimedAndCommit(world, world.getEntityStore().getStore(), npcPlugin, roleIndex,
                        destination.position(), destination.rotation(), batch, 0,
                        (npc, holder) -> {
                            if (snapshot.snapshot() != null) {
                                postWork.set(restorer.restoreToHolder(holder, snapshot.snapshot(), marker));
                            } else {
                                ComponentType<EntityStore, TameworkProjectionIdentityComponent> type =
                                        TameworkProjectionIdentityComponent.getComponentType();
                                if (type == null) throw new IllegalStateException("projection-marker-unavailable");
                                holder.putComponent(type, marker);
                            }
                        }, new CompanionPreparedSpawnService.Callbacks() {
                            @Override
                            public void onSpawned(CompanionPreparedSpawnService.SpawnedCompanion live) {
                                CoopResidentStateRestorer.PostAddWork work = postWork.get();
                                if (work != null) postAdd.apply(live.ref(), live.npc(), live.store(), work);
                                completion.complete(new CommandTimedSummoningService.ProjectionResult(
                                        CommandTimedSummoningService.ProjectionOutcome.SUCCESS,
                                        live.plannedNpcUuid(), "timed-summon-projected"));
                            }

                            @Override public void onDenied(String reason) {
                                completion.complete(notApplied(reason));
                            }

                            @Override public void onDurabilityDegraded(String reason) {
                                completion.complete(ambiguous(reason));
                            }

                            @Override public void onWorldDispatchRejected(String reason) {
                                completion.complete(ambiguous(reason));
                            }

                            @Override public void onTerminal() {
                                if (!completion.isDone()) completion.complete(
                                        ambiguous("timed-summon-spawn-terminal-without-result"));
                            }
                        });
        if (!started && !completion.isDone()) {
            completion.complete(notApplied("timed-summon-spawn-not-started"));
        }
    }

    @Nonnull
    @Override
    public CompletionStage<CommandTimedSummoningService.ProjectionResult> snapshotAndDespawn(
            @Nonnull CommandTimedSummoningService.PopulationContext context,
            @Nonnull String summonSessionId) {
        UUID npcUuid = context.projectionNpcUuid() != null ? context.projectionNpcUuid()
                : ownerRuntime.identityResolver().currentNpcUuid(context.profileId()).orElse(null);
        if (npcUuid == null) return projection(CommandTimedSummoningService.ProjectionOutcome.NOT_APPLIED,
                null, "timed-summon-live-identity-unavailable");
        return reads.submit(() -> new StorageInspection(
                        role(context.profileId()), hasDurableStoredSnapshot(context, npcUuid)))
                .thenCompose(inspection -> snapshotAndDespawnPrepared(
                        context, summonSessionId, npcUuid, inspection))
                .exceptionally(failure -> ambiguous("timed-summon-storage-evidence-read-failed"));
    }

    private CompletionStage<CommandTimedSummoningService.ProjectionResult> snapshotAndDespawnPrepared(
            CommandTimedSummoningService.PopulationContext context,
            String summonSessionId,
            UUID npcUuid,
            StorageInspection inspection) {
        List<World> worlds = worlds();
        if (worlds.isEmpty()) return projection(CommandTimedSummoningService.ProjectionOutcome.AMBIGUOUS,
                npcUuid, "timed-summon-worlds-unavailable");
        CompletableFuture<CommandTimedSummoningService.ProjectionResult> completion =
                new CompletableFuture<>();
        AtomicInteger remaining = new AtomicInteger(worlds.size());
        AtomicBoolean matched = new AtomicBoolean();
        for (World world : worlds) {
            LeaseBoundWorldDispatcher.execute(world,
                    () -> captureOnWorld(world, context, summonSessionId, npcUuid,
                            completion, remaining, matched, inspection.durableAbsence(),
                            inspection.roleId()),
                    () -> finishAbsentSearch(
                            completion, remaining, matched, inspection.durableAbsence(), context, npcUuid));
        }
        return completion;
    }

    private void captureOnWorld(World world, CommandTimedSummoningService.PopulationContext context,
                                String sessionId, UUID npcUuid,
                                CompletableFuture<CommandTimedSummoningService.ProjectionResult> completion,
                                AtomicInteger remaining, AtomicBoolean matched,
                                boolean durableAbsence, @Nullable String roleId) {
        if (completion.isDone() || world.getEntityStore() == null) {
            finishAbsentSearch(completion, remaining, matched,
                    durableAbsence, context, npcUuid);
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        Ref<EntityStore> ref = world.getEntityRef(npcUuid);
        NPCEntity npc = ref == null || !ref.isValid() ? null
                : store.getComponent(ref, NPCEntity.getComponentType());
        if (npc == null || !npcUuid.equals(npc.getUuid()) || npc.isDespawning()) {
            finishAbsentSearch(completion, remaining, matched,
                    durableAbsence, context, npcUuid);
            return;
        }
        if (!matched.compareAndSet(false, true)) return;
        CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot =
                snapshots.captureSnapshotForPersistence(ref, store, npcUuid, roleId);
        if (snapshot == null) {
            completion.complete(notApplied("timed-summon-snapshot-capture-failed"));
            return;
        }
        String json = snapshotCodec.encode(snapshot);
        CommandTimedSummonRepository.ProjectionSnapshot durable =
                new CommandTimedSummonRepository.ProjectionSnapshot(
                        context.ownerUuid(), context.commandFamilyId(), context.profileId(), npcUuid,
                        json, sha256(json), System.currentTimeMillis());
        repository.saveProjectionSnapshotAsync(durable).completion().whenComplete((outcome, failure) -> {
            if (failure != null || outcome == null || !outcome.isCommitted()) {
                completion.complete(ambiguous("timed-summon-snapshot-persist-failed"));
                return;
            }
            LeaseBoundWorldDispatcher.execute(world,
                    () -> removeExactProjection(world, context, sessionId, npcUuid, completion),
                    () -> completion.complete(ambiguous("timed-summon-remove-dispatch-rejected")));
        });
    }

    private void removeExactProjection(World world, CommandTimedSummoningService.PopulationContext context,
                                       String sessionId, UUID npcUuid,
                                       CompletableFuture<CommandTimedSummoningService.ProjectionResult> completion) {
        try {
            if (completion.isDone() || world.getEntityStore() == null) return;
            Store<EntityStore> store = world.getEntityStore().getStore();
            Ref<EntityStore> ref = world.getEntityRef(npcUuid);
            if (ref == null || !ref.isValid()) {
                completion.complete(ambiguous("timed-summon-projection-vanished-before-retirement"));
                return;
            }
            NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
            if (npc == null || !npcUuid.equals(npc.getUuid()) || npc.isDespawning()) {
                completion.complete(ambiguous("timed-summon-projection-changed-before-retirement"));
                return;
            }
            /*
             * The projection already carries the durable command-roster marker installed before
             * spawn. Replacing that component immediately before removal is both redundant and a
             * second ECS mutation that can fail before Store#removeEntity is reached.
             */
            store.removeEntity(ref, RemoveReason.REMOVE);
            if (ref.isValid()) {
                completion.complete(ambiguous("timed-summon-projection-removal-unconfirmed"));
            } else {
                completion.complete(new CommandTimedSummoningService.ProjectionResult(
                        CommandTimedSummoningService.ProjectionOutcome.SUCCESS, npcUuid,
                        "timed-summon-projection-stored"));
            }
        } catch (RuntimeException | LinkageError failure) {
            Tamework plugin = Tamework.getInstance();
            if (plugin != null) {
                plugin.getLogger().at(Level.WARNING).withCause(failure).log(
                        "Timed command summon failed to remove projection npc=" + npcUuid
                                + " profile=" + context.profileId()
                                + "; stale-operation recovery will reconcile it.");
            }
            completion.complete(ambiguous("timed-summon-projection-removal-failed"));
        }
    }

    @Nonnull
    @Override
    public CompletionStage<CommandTimedSummoningService.ProjectionEvidence> inspect(
            @Nonnull CommandTimedSummoningService.PopulationContext context,
            @Nullable String summonSessionId) {
        UUID npcUuid = context.projectionNpcUuid() != null ? context.projectionNpcUuid()
                : ownerRuntime.identityResolver().currentNpcUuid(context.profileId()).orElse(null);
        if (npcUuid == null) return CompletableFuture.completedFuture(
                CommandTimedSummoningService.ProjectionEvidence.ABSENT);
        return reads.submit(() -> hasDurableStoredSnapshot(context, npcUuid))
                .thenCompose(durableAbsence -> inspectPrepared(context, npcUuid, durableAbsence))
                .exceptionally(failure -> CommandTimedSummoningService.ProjectionEvidence.AMBIGUOUS);
    }

    private CompletionStage<CommandTimedSummoningService.ProjectionEvidence> inspectPrepared(
            CommandTimedSummoningService.PopulationContext context,
            UUID npcUuid,
            boolean durableAbsence) {
        List<World> worlds = worlds();
        if (worlds.isEmpty()) return CompletableFuture.completedFuture(
                CommandTimedSummoningService.ProjectionEvidence.AMBIGUOUS);
        CompletableFuture<CommandTimedSummoningService.ProjectionEvidence> completion =
                new CompletableFuture<>();
        AtomicInteger remaining = new AtomicInteger(worlds.size());
        for (World world : worlds) {
            LeaseBoundWorldDispatcher.execute(world, () -> {
                Ref<EntityStore> ref = world.getEntityRef(npcUuid);
                if (ref != null && ref.isValid()) completion.complete(
                        CommandTimedSummoningService.ProjectionEvidence.PRESENT);
                if (remaining.decrementAndGet() == 0 && !completion.isDone()) {
                    completion.complete(durableAbsence
                            ? CommandTimedSummoningService.ProjectionEvidence.ABSENT
                            : CommandTimedSummoningService.ProjectionEvidence.AMBIGUOUS);
                }
            }, () -> {
                if (remaining.decrementAndGet() == 0 && !completion.isDone()) completion.complete(
                        CommandTimedSummoningService.ProjectionEvidence.AMBIGUOUS);
            });
        }
        return completion;
    }

    private boolean hasDurableStoredSnapshot(CommandTimedSummoningService.PopulationContext context,
                                             UUID npcUuid) {
        try {
            CommandTimedSummonRepository.ProjectionSnapshot snapshot = repository.findProjectionSnapshot(
                    context.ownerUuid(), context.commandFamilyId(), context.profileId());
            OwnerPopulationEntry owner = ownerRuntime.index().entry(context.profileId()).orElse(null);
            return snapshot != null && npcUuid.equals(snapshot.sourceNpcUuid()) && owner != null
                    && (owner.lifecycleState() == CompanionLifecycleState.STORING
                    || owner.lifecycleState() == CompanionLifecycleState.ROSTER_STORED);
        } catch (Exception failure) {
            return false;
        }
    }

    private SnapshotState loadSnapshot(CommandTimedSummoningService.PopulationContext context) {
        try {
            CommandTimedSummonRepository.ProjectionSnapshot persisted = repository.findProjectionSnapshot(
                    context.ownerUuid(), context.commandFamilyId(), context.profileId());
            if (persisted == null) return new SnapshotState(null, null);
            if (!persisted.snapshotSha256().equals(sha256(persisted.snapshotJson()))) {
                throw new IllegalStateException("timed-summon-snapshot-hash-mismatch");
            }
            CoopResidentStateSnapshotCodec.DecodeResult decoded = snapshotCodec.decode(persisted.snapshotJson());
            if (decoded.status() != CoopResidentStateSnapshotCodec.Status.FOUND
                    || decoded.snapshot() == null) {
                throw new IllegalStateException("timed-summon-snapshot-decode-failed");
            }
            return new SnapshotState(decoded.snapshot(), persisted.sourceNpcUuid());
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("timed-summon-snapshot-read-failed", failure);
        }
    }

    private TameworkProjectionIdentityComponent marker(
            CommandTimedSummoningService.PopulationContext context, String sessionId,
            @Nullable UUID sourceNpcUuid, UUID projectionNpcUuid) {
        return new TameworkProjectionIdentityComponent(
                context.profileId(), sessionId,
                TameworkProjectionIdentityComponent.KIND_COMMAND_ROSTER,
                context.commandFamilyId(), sourceNpcUuid,
                Integer.toUnsignedLong(Objects.hash(sessionId, projectionNpcUuid)));
    }

    @Nullable private String role(String profileId) {
        NpcProfileRepository.ProfileRecord profile = profiles.loadProfileById(profileId);
        return profile == null || profile.roleId() == null || profile.roleId().isBlank()
                ? null : profile.roleId();
    }

    private static List<World> worlds() {
        Universe universe = Universe.get();
        return universe == null || universe.getWorlds() == null
                ? List.of() : new ArrayList<>(universe.getWorlds().values());
    }

    @Nullable private static World world(@Nullable String name) {
        Universe universe = Universe.get();
        return universe == null || name == null ? null : universe.getWorld(name);
    }

    private static CompletionStage<CommandTimedSummoningService.SpawnPlan> noOwnerPlan() {
        return CompletableFuture.completedFuture(new CommandTimedSummoningService.SpawnPlan(
                false, null, null, null, "timed-summon-owner-not-loaded"));
    }

    private static void completeMissingOwner(
            CompletableFuture<CommandTimedSummoningService.SpawnPlan> completion,
            AtomicInteger remaining) {
        if (remaining.decrementAndGet() == 0 && !completion.isDone()) completion.complete(
                new CommandTimedSummoningService.SpawnPlan(
                        false, null, null, null, "timed-summon-owner-not-loaded"));
    }

    private static void finishAbsentSearch(
            CompletableFuture<CommandTimedSummoningService.ProjectionResult> completion,
            AtomicInteger remaining, AtomicBoolean matched, boolean durableAbsence,
            CommandTimedSummoningService.PopulationContext context, UUID npcUuid) {
        if (remaining.decrementAndGet() != 0 || matched.get() || completion.isDone()) return;
        if (durableAbsence) {
            CommandTimedProjectionRetirementIndex.retire(
                    npcUuid, context.profileId(), context.commandFamilyId(), context.idempotencyKey());
            completion.complete(new CommandTimedSummoningService.ProjectionResult(
                    CommandTimedSummoningService.ProjectionOutcome.SUCCESS, npcUuid,
                    "timed-summon-unloaded-projection-retired"));
        } else {
            completion.complete(new CommandTimedSummoningService.ProjectionResult(
                    CommandTimedSummoningService.ProjectionOutcome.AMBIGUOUS, npcUuid,
                    "timed-summon-projection-not-loaded"));
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static CompletionStage<CommandTimedSummoningService.ProjectionResult> projection(
            CommandTimedSummoningService.ProjectionOutcome outcome, @Nullable UUID uuid, String reason) {
        return CompletableFuture.completedFuture(
                new CommandTimedSummoningService.ProjectionResult(outcome, uuid, reason));
    }

    private static CommandTimedSummoningService.ProjectionResult notApplied(String reason) {
        return new CommandTimedSummoningService.ProjectionResult(
                CommandTimedSummoningService.ProjectionOutcome.NOT_APPLIED, null, reason);
    }

    private static CommandTimedSummoningService.ProjectionResult ambiguous(String reason) {
        return new CommandTimedSummoningService.ProjectionResult(
                CommandTimedSummoningService.ProjectionOutcome.AMBIGUOUS, null, reason);
    }

    private record SnapshotState(
            @Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot,
            @Nullable UUID sourceNpcUuid) {
    }

    private record SpawnState(
            @Nullable String roleId,
            @Nonnull SnapshotState snapshot) {
    }

    private record StorageInspection(
            @Nullable String roleId,
            boolean durableAbsence) {
    }
}
