package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ownership.CompanionPopulationCommitResult;
import com.alechilles.alecstamework.ownership.CompanionSpawnPopulationAdmissionService;
import com.alechilles.alecstamework.ownership.OwnerComponentMutationService;
import com.alechilles.alecstamework.ownership.PlannedCompanionSpawnProbe;
import com.alechilles.alecstamework.ownership.PreparedCompanionSpawnBatch;
import com.alechilles.alecstamework.persistence.operation.PersistenceCheckpoint;
import com.alechilles.alecstamework.persistence.operation.PersistenceCheckpointHook;
import com.alechilles.alecstamework.runtime.dispatch.LeaseBoundWorldDispatcher;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.Pair;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Claims one prepared unit, writes its pre-add holder, and terminalizes population state as soon
 * as NPCPlugin exposes a live entity. Async completion retains only stable identity and re-resolves
 * the target on the world thread before callbacks or destructive source finalization.
 */
final class CompanionPreparedSpawnService {
    private final CompanionSpawnPopulationAdmissionService admissionService;
    private final PersistenceCheckpointHook checkpoints;
    private final CompanionSpawnCommitContinuation commitContinuation =
            new CompanionSpawnCommitContinuation();

    CompanionPreparedSpawnService(@Nonnull CompanionSpawnPopulationAdmissionService admissionService) {
        this(admissionService, PersistenceCheckpointHook.NO_OP);
    }

    CompanionPreparedSpawnService(@Nonnull CompanionSpawnPopulationAdmissionService admissionService,
                                  @Nonnull PersistenceCheckpointHook checkpoints) {
        this.admissionService = Objects.requireNonNull(admissionService, "admissionService");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
    }

    boolean spawnAndCommit(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull NPCPlugin npcPlugin,
            int roleIndex,
            @Nonnull Vector3d position,
            @Nonnull Rotation3f rotation,
            @Nonnull PreparedCompanionSpawnBatch batch,
            int unitIndex,
            @Nullable Callbacks callbacks
    ) {
        Callbacks safeCallbacks = callbacks == null ? Callbacks.NOOP : callbacks;
        if (!claimQuietly(batch, unitIndex)) {
            cancelQuietly(batch, unitIndex, "spawn-population-recheck-failed");
            deny(safeCallbacks, "spawn-population-recheck-failed");
            return false;
        }
        return spawnClaimedAndCommit(
                world, store, npcPlugin, roleIndex, position, rotation, batch, unitIndex,
                safeCallbacks);
    }

    /** Executes a world projection after an outer journal has already claimed the prepared unit. */
    boolean spawnClaimedAndCommit(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull NPCPlugin npcPlugin,
            int roleIndex,
            @Nonnull Vector3d position,
            @Nonnull Rotation3f rotation,
            @Nonnull PreparedCompanionSpawnBatch batch,
            int unitIndex,
            @Nullable Callbacks callbacks
    ) {
        return spawnClaimedAndCommit(world, store, npcPlugin, roleIndex, position, rotation,
                batch, unitIndex, SpawnHolderAugmenter.NO_OP, callbacks);
    }

    /** Allows a durable profile snapshot to be restored in the same pre-add holder transaction. */
    boolean spawnClaimedAndCommit(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull NPCPlugin npcPlugin,
            int roleIndex,
            @Nonnull Vector3d position,
            @Nonnull Rotation3f rotation,
            @Nonnull PreparedCompanionSpawnBatch batch,
            int unitIndex,
            @Nonnull SpawnHolderAugmenter augmenter,
            @Nullable Callbacks callbacks
    ) {
        Callbacks safeCallbacks = callbacks == null ? Callbacks.NOOP : callbacks;
        SpawnAttempt attempt = spawn(
                world, store, npcPlugin, roleIndex, position, rotation, batch, unitIndex,
                Objects.requireNonNull(augmenter, "augmenter")
        );
        Pair<Ref<EntityStore>, NPCEntity> spawned = attempt.spawned();
        if (spawned == null || spawned.first() == null || spawned.second() == null) {
            if (attempt.outcomeAmbiguous()) {
                degradeAuthority("spawn_entity_outcome_ambiguous");
                notifyDegraded(safeCallbacks, "spawn-entity-outcome-ambiguous");
                terminal(safeCallbacks);
                return false;
            }
            cancelQuietly(batch, unitIndex, "spawn-entity-failed");
            deny(safeCallbacks, "spawn-entity-failed");
            return false;
        }
        UUID plannedNpcUuid = batch.spawn(unitIndex).plannedNpcUuid();
        if (!hasPlannedUuid(spawned.first(), store, plannedNpcUuid)) {
            despawnQuietly(spawned.second());
            degradeAuthority("spawn_live_identity_mismatch");
            notifyDegraded(safeCallbacks, "spawn-live-identity-mismatch");
            terminal(safeCallbacks);
            return false;
        }
        CompletableFuture<CompanionPopulationCommitResult> commit =
                commitLiveQuietly(batch, unitIndex);
        finishAfterCommit(
                world,
                batch,
                unitIndex,
                commit,
                safeCallbacks
        );
        return true;
    }

    private boolean claimQuietly(PreparedCompanionSpawnBatch batch, int unitIndex) {
        try {
            return admissionService.claimForSpawn(batch, unitIndex);
        } catch (RuntimeException | LinkageError failure) {
            degradeAuthority("spawn_claim_recheck_exception");
            return false;
        }
    }

    private void cancelQuietly(PreparedCompanionSpawnBatch batch, int unitIndex, String reason) {
        try {
            admissionService.cancelAsync(batch, unitIndex, reason);
        } catch (RuntimeException | LinkageError ignored) {
            degradeAuthority("spawn_cancel_start_exception");
            // Reconciliation quarantines a capability whose cancellation could not be started.
        }
    }

    private CompletableFuture<CompanionPopulationCommitResult> commitLiveQuietly(
            PreparedCompanionSpawnBatch batch,
            int unitIndex
    ) {
        try {
            CompletableFuture<CompanionPopulationCommitResult> result =
                    admissionService.commitLiveAsync(batch, unitIndex);
            return result == null
                    ? CompletableFuture.completedFuture(new CompanionPopulationCommitResult(
                    false, "spawn-population-commit-unavailable", false, null))
                    : result;
        } catch (RuntimeException | LinkageError failure) {
            degradeAuthority("spawn_commit_start_exception");
            return CompletableFuture.completedFuture(new CompanionPopulationCommitResult(
                    false, "spawn-population-commit-start-failed", false, null
            ));
        }
    }

    private void degradeAuthority(String reason) {
        try {
            admissionService.markReadinessDegraded(reason);
        } catch (RuntimeException | LinkageError ignored) {
            // The caller still receives a terminal degraded result.
        }
    }

    private static void despawnQuietly(@Nonnull NPCEntity npc) {
        try {
            npc.setToDespawn();
        } catch (RuntimeException | LinkageError ignored) {
            // Cancellation and terminal diagnostics still run below.
        }
    }

    private void finishAfterCommit(
            World world,
            PreparedCompanionSpawnBatch batch,
            int unitIndex,
            CompletableFuture<CompanionPopulationCommitResult> commit,
            Callbacks callbacks
    ) {
        commitContinuation.finish(
                commit,
                () -> resolveLive(world, batch, unitIndex),
                live -> invokeSourceFinalization(callbacks, live),
                live -> invokeSpawned(callbacks, live),
                () -> admissionService.completeSourceFinalizationAsync(batch, unitIndex),
                reason -> {
                    degradeAuthority("spawn_continuation_" + reason.replace('-', '_'));
                    notifyDegraded(callbacks, reason);
                },
                reason -> {
                    degradeAuthority("spawn_continuation_" + reason.replace('-', '_'));
                    notifyWorldDispatchRejected(callbacks, reason);
                },
                () -> terminal(callbacks),
                (task, rejected) -> LeaseBoundWorldDispatcher.execute(
                        world, task, rejected
                )
        );
    }

    @Nullable
    private SpawnedCompanion resolveLive(
            @Nonnull World world,
            @Nonnull PreparedCompanionSpawnBatch batch,
            int unitIndex
    ) {
        if (!world.isAlive() || world.getEntityStore() == null) {
            return null;
        }
        Store<EntityStore> liveStore = world.getEntityStore().getStore();
        if (liveStore == null) {
            return null;
        }
        PreparedCompanionSpawnBatch.ReservedSpawn reserved = batch.spawn(unitIndex);
        PlannedCompanionSpawnProbe.Result probe = PlannedCompanionSpawnProbe.probe(
                world, liveStore, reserved.plannedNpcUuid()
        );
        if (!probe.present() || !admissionService.isCurrentLiveIdentity(batch, unitIndex)
                || !hasExpectedOwner(
                probe.ref(), liveStore, reserved.request().ownerId()
        )) {
            degradeAuthority("spawn_live_identity_changed_before_continuation");
            return null;
        }
        return new SpawnedCompanion(
                world,
                liveStore,
                probe.ref(),
                probe.npc(),
                reserved.profileId(),
                reserved.plannedNpcUuid()
        );
    }

    private static boolean hasExpectedOwner(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nullable UUID expectedOwner
    ) {
        ComponentType<EntityStore, TameworkOwnerComponent> type =
                TameworkOwnerComponent.getComponentType();
        if (type == null || !ref.isValid()) {
            return false;
        }
        try {
            TameworkOwnerComponent owner = store.getComponent(ref, type);
            return Objects.equals(expectedOwner, owner == null ? null : owner.getOwnerId());
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    @Nonnull
    private SpawnAttempt spawn(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull NPCPlugin npcPlugin,
            int roleIndex,
            @Nonnull Vector3d position,
            @Nonnull Rotation3f rotation,
            @Nonnull PreparedCompanionSpawnBatch batch,
            int unitIndex,
            @Nonnull SpawnHolderAugmenter augmenter
    ) {
        try {
            hit(PersistenceCheckpoint.BEFORE_LIVE_ENTITY_SPAWN);
            Pair<Ref<EntityStore>, NPCEntity> spawned = npcPlugin.spawnEntity(
                    store,
                    roleIndex,
                    position,
                    rotation,
                    null,
                    (npc, holder, spawnStore) -> {
                        OwnerComponentMutationService.WriteResult write =
                                admissionService.writeSpawnHolder(batch, unitIndex, holder);
                        if (!write.applied()) {
                            throw new SpawnHolderPreparationException(write.reason());
                        }
                        augmenter.augment(npc, holder);
                    },
                    null
            );
            if (spawned != null && spawned.first() != null && spawned.second() != null) {
                hit(PersistenceCheckpoint.AFTER_LIVE_ENTITY_SPAWN);
            }
            return spawned != null && spawned.first() != null && spawned.second() != null
                    ? SpawnAttempt.spawned(spawned)
                    : recoverSpawn(world, store, batch.spawn(unitIndex).plannedNpcUuid());
        } catch (SpawnHolderPreparationException failure) {
            return SpawnAttempt.absent();
        } catch (RuntimeException | LinkageError failure) {
            return recoverSpawn(world, store, batch.spawn(unitIndex).plannedNpcUuid());
        }
    }

    private void hit(PersistenceCheckpoint checkpoint) {
        try {
            checkpoints.hit(checkpoint, null);
        } catch (Exception failure) {
            throw new PersistenceSpawnCheckpointException(failure);
        }
    }

    @Nonnull
    private static SpawnAttempt recoverSpawn(@Nonnull World world,
                                             @Nonnull Store<EntityStore> store,
                                             @Nonnull UUID plannedNpcUuid) {
        PlannedCompanionSpawnProbe.Result probe =
                PlannedCompanionSpawnProbe.probe(world, store, plannedNpcUuid);
        if (probe.present()) {
            return SpawnAttempt.spawned(Pair.of(probe.ref(), probe.npc()));
        }
        return probe.absenceProven() ? SpawnAttempt.absent() : SpawnAttempt.ambiguous();
    }

    private static boolean hasPlannedUuid(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull UUID plannedUuid
    ) {
        if (!ref.isValid() || UUIDComponent.getComponentType() == null) {
            return false;
        }
        try {
            UUIDComponent uuid = store.getComponent(ref, UUIDComponent.getComponentType());
            return uuid != null && plannedUuid.equals(uuid.getUuid());
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private static boolean invokeSourceFinalization(
            @Nonnull Callbacks callbacks,
            @Nonnull SpawnedCompanion live
    ) {
        try {
            return callbacks.finalizeSource(live);
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private static void invokeSpawned(
            @Nonnull Callbacks callbacks,
            @Nonnull SpawnedCompanion live
    ) {
        callbacks.onSpawned(live);
    }

    private static void deny(@Nonnull Callbacks callbacks, @Nonnull String reason) {
        try {
            callbacks.onDenied(reason);
        } catch (RuntimeException | LinkageError ignored) {
            // Notification failures cannot change the already-cancelled population operation.
        } finally {
            terminal(callbacks);
        }
    }

    private static void notifyDegraded(@Nonnull Callbacks callbacks, @Nonnull String reason) {
        try {
            callbacks.onDurabilityDegraded(reason);
        } catch (RuntimeException | LinkageError ignored) {
            // Diagnostics are best effort after the live operation has become terminal.
        }
    }

    private static void notifyWorldDispatchRejected(
            @Nonnull Callbacks callbacks,
            @Nonnull String reason
    ) {
        try {
            callbacks.onWorldDispatchRejected(reason);
        } catch (RuntimeException | LinkageError ignored) {
            // Durable accounting is already terminal or conservatively retained.
        }
    }

    private static void terminal(@Nonnull Callbacks callbacks) {
        try {
            callbacks.onTerminal();
        } catch (RuntimeException | LinkageError ignored) {
            // No admission remains for the callback to strand.
        }
    }

    record SpawnedCompanion(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull NPCEntity npc,
            @Nonnull String profileId,
            @Nonnull UUID plannedNpcUuid
    ) {
    }

    private record SpawnAttempt(@Nullable Pair<Ref<EntityStore>, NPCEntity> spawned,
                                boolean outcomeAmbiguous) {
        @Nonnull
        private static SpawnAttempt spawned(@Nonnull Pair<Ref<EntityStore>, NPCEntity> spawned) {
            return new SpawnAttempt(spawned, false);
        }

        @Nonnull
        private static SpawnAttempt absent() {
            return new SpawnAttempt(null, false);
        }

        @Nonnull
        private static SpawnAttempt ambiguous() {
            return new SpawnAttempt(null, true);
        }
    }

    interface Callbacks {
        Callbacks NOOP = new Callbacks() {
        };

        /** Called only after a live canonical identity exists; false leaves a safely stale source. */
        default boolean finalizeSource(@Nonnull SpawnedCompanion live) {
            return true;
        }

        default void onSpawned(@Nonnull SpawnedCompanion live) {
        }

        default void onDenied(@Nonnull String reason) {
        }

        default void onDurabilityDegraded(@Nonnull String reason) {
        }

        /**
         * Runs outside the world thread when deferred work cannot start. Implementations may only
         * close thread-safe state and must not access live ECS or player state.
         */
        default void onWorldDispatchRejected(@Nonnull String reason) {
        }

        default void onTerminal() {
        }
    }

    @FunctionalInterface
    interface SpawnHolderAugmenter {
        SpawnHolderAugmenter NO_OP = (npc, holder) -> { };
        void augment(@Nonnull NPCEntity npc, @Nonnull Holder<EntityStore> holder);
    }

    private static final class SpawnHolderPreparationException extends RuntimeException {
        private SpawnHolderPreparationException(String reason) {
            super(reason);
        }
    }

    private static final class PersistenceSpawnCheckpointException extends RuntimeException {
        private PersistenceSpawnCheckpointException(Exception cause) {
            super(cause);
        }
    }
}
