package com.alechilles.alecstamework.items.persistence.checkpoint;

import com.alechilles.alecstamework.companion.extension.ProfileExtensionData;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.items.ImportedRecallRecoverySink;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex;
import com.alechilles.alecstamework.npc.components.TameworkPersistenceRetirementComponent;
import com.alechilles.alecstamework.npc.spawning.CompanionSpawnAuthorityService;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDomainFacades;
import com.alechilles.alecstamework.runtime.dispatch.LeaseBoundWorldDispatcher;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.storage.GetChunkFlags;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Restores an exact full holder after its persisted source section is absent. */
public final class ExactCheckpointCompanionRecallRecovery
        implements ImportedRecallRecoverySink {
    private final PersistenceDomainFacades persistence;
    private final LoadedNpcIdentityIndex identities;
    private final HytaleLogger logger;
    private final CompanionEntityCheckpointCodec codec =
            new CompanionEntityCheckpointCodec();
    private final ExactCheckpointRecallRecoveryAuthor author =
            new ExactCheckpointRecallRecoveryAuthor();
    private final ExactCheckpointHolderFactory holders;
    private final ReturnedOriginalTargetDrainer returnedTargets =
            new ReturnedOriginalTargetDrainer();
    private final Set<UUID> returnedReconciliations =
            ConcurrentHashMap.newKeySet();

    public ExactCheckpointCompanionRecallRecovery(
            @Nonnull PersistenceDomainFacades persistence,
            @Nonnull LoadedNpcIdentityIndex identities,
            @Nullable ComponentType<
                    EntityStore,
                    TameworkPersistenceRetirementComponent
                    > retirementType,
            @Nonnull HytaleLogger logger
    ) {
        this.persistence = Objects.requireNonNull(
                persistence, "persistence"
        );
        this.identities = Objects.requireNonNull(identities, "identities");
        this.holders = new ExactCheckpointHolderFactory(retirementType);
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public CompletionStage<RecoveryOutcome> recover(RecallFailure failure) {
        CompletionStage<RecoveryOutcome> recovery = tryRecover(failure);
        return recovery.whenComplete((outcome, problem) -> {
            if (problem != null && failure != null) {
                logger.at(Level.WARNING).withCause(problem).log(
                        "Exact companion checkpoint recovery failed for "
                                + "npc=%s",
                        failure.npcUuid()
                );
            }
        });
    }

    /** Reconciles one exact returned original after its checkpoint commits. */
    public void recoverReturnedOriginal(
            CompanionEntityCheckpoint checkpoint
    ) {
        if (checkpoint == null || checkpoint.boundary()
                != CompanionEntityCheckpoint.CaptureBoundary
                .RETURNED_RETIRED_ORIGINAL) {
            return;
        }
        UUID targetAlias = checkpoint.alias().value();
        if (!returnedReconciliations.add(targetAlias)) {
            return;
        }
        long failedAtMs = Math.max(
                checkpoint.capturedAtMs(), System.currentTimeMillis()
        );
        RecallFailure failure = new RecallFailure(
                checkpoint.alias().value(),
                checkpoint.ownerId().value(),
                checkpoint.capturedAtMs(),
                failedAtMs,
                checkpoint.worldKey(),
                new RecallDestination(
                        checkpoint.worldKey(),
                        checkpoint.x(),
                        checkpoint.y(),
                        checkpoint.z()
                ),
                Set.of()
        );
        recoverCheckpoint(checkpoint, failure).whenComplete(
                (outcome, problem) -> {
                    if (problem != null) {
                        logger.at(Level.WARNING).withCause(problem).log(
                                "Returned original reconciliation failed for "
                                        + "npc=%s",
                                checkpoint.sourceAlias().value()
                        );
                    }
                    returnedReconciliations.remove(targetAlias);
                }
        );
    }

    /** Returns whether an internal drain must not replace source authority. */
    public boolean suppressesCheckpoint(NpcAlias alias) {
        return alias != null
                && returnedReconciliations.contains(alias.value());
    }

    private CompletionStage<RecoveryOutcome> recoverCheckpoint(
            CompanionEntityCheckpoint checkpoint,
            RecallFailure failure
    ) {
        return persistence.queries().findProfile(checkpoint.alias())
                .thenCompose(read -> {
                    if (read instanceof PersistenceReadResult.Failed<
                            CompanionProfileReadModel> failed) {
                        return readFailure(failed);
                    }
                    if (!(read instanceof PersistenceReadResult.Found<
                            CompanionProfileReadModel> found)) {
                        return CompletableFuture.completedFuture(
                                RecoveryOutcome.NONE
                        );
                    }
                    ExactCheckpointRecallRecoveryAuthor.RecoveryPlan plan =
                            author.author(
                                    found.value(), checkpoint, failure
                            );
                    return plan == null
                            ? CompletableFuture.completedFuture(
                                    RecoveryOutcome.NONE
                            )
                            : loadAndRestore(plan);
                });
    }

    private CompletionStage<RecoveryOutcome> tryRecover(
            RecallFailure failure
    ) {
        if (failure == null || failure.destination() == null) {
            return CompletableFuture.completedFuture(RecoveryOutcome.NONE);
        }
        NpcAlias alias = new NpcAlias(failure.npcUuid());
        return persistence.queries().findProfile(alias).thenCompose(read -> {
            if (read instanceof PersistenceReadResult.Failed<
                    CompanionProfileReadModel> failed) {
                return readFailure(failed);
            }
            if (!(read instanceof PersistenceReadResult.Found<
                    CompanionProfileReadModel> found)) {
                return CompletableFuture.completedFuture(
                        RecoveryOutcome.NONE
                );
            }
            return readCheckpoint(found.value(), alias, failure);
        });
    }

    private CompletionStage<RecoveryOutcome> readCheckpoint(
            CompanionProfileReadModel profile,
            NpcAlias alias,
            RecallFailure failure
    ) {
        return persistence.queries().findExtension(
                ReplacementCompanionEntityCheckpointSink.key(
                        profile.identity().profileId(), alias
                )
        ).thenCompose(read -> {
            if (read instanceof PersistenceReadResult.Failed<
                    ProfileExtensionData> failed) {
                return readFailure(failed);
            }
            if (!(read instanceof PersistenceReadResult.Found<
                    ProfileExtensionData> found)) {
                return CompletableFuture.completedFuture(
                        RecoveryOutcome.NONE
                );
            }
            CompanionEntityCheckpoint checkpoint;
            try {
                checkpoint = codec.decode(found.value().jsonPayload());
            } catch (RuntimeException corrupt) {
                return CompletableFuture.failedFuture(corrupt);
            }
            ExactCheckpointRecallRecoveryAuthor.RecoveryPlan plan =
                    author.author(profile, checkpoint, failure);
            return plan == null
                    ? CompletableFuture.completedFuture(
                            RecoveryOutcome.NONE
                    )
                    : loadAndRestore(plan);
        });
    }

    private CompletionStage<RecoveryOutcome> loadAndRestore(
            ExactCheckpointRecallRecoveryAuthor.RecoveryPlan plan
    ) {
        Universe universe = Universe.get();
        World source = world(universe, plan.sourceSection().worldName());
        World destination = world(
                universe, plan.destination().worldName()
        );
        if (source == null || destination == null) {
            return CompletableFuture.completedFuture(RecoveryOutcome.NONE);
        }
        return loadSource(source, plan).thenCompose(loaded -> {
            if (!loaded) {
                return CompletableFuture.completedFuture(
                        RecoveryOutcome.NONE
                );
            }
            LoadedNpcIdentityIndex.Probe probe = identities.probe(
                    plan.checkpoint().alias().value()
            );
            if (probe.status() == LoadedNpcIdentityIndex.ProbeStatus.UNKNOWN) {
                return CompletableFuture.completedFuture(
                        RecoveryOutcome.NONE
                );
            }
            boolean returnedOriginal = returnedOriginal(plan.checkpoint());
            if (!returnedOriginal && (probe.isKnownLive() || live(
                            source,
                            plan.checkpoint().sourceAlias().value()
                    ))) {
                return CompletableFuture.completedFuture(
                        RecoveryOutcome.RETRY_REQUIRED
                );
            }
            if (!returnedOriginal) {
                return restore(source, destination, plan);
            }
            return returnedTargets.drain(universe, plan, probe)
                    .thenCompose(drained -> restore(
                            source, destination, plan
                    ).handle(RestoreAttempt::new).thenCompose(attempt -> {
                        if (attempt.problem() == null && attempt.outcome()
                                == RecoveryOutcome.RECOVERED) {
                            return CompletableFuture.completedFuture(
                                    attempt.outcome()
                            );
                        }
                        return returnedTargets.rollback(drained)
                                .thenCompose(ignored ->
                                        attempt.problem() == null
                                                ? CompletableFuture
                                                .completedFuture(
                                                        attempt.outcome()
                                                )
                                                : CompletableFuture
                                                .failedFuture(
                                                        attempt.problem()
                                                )
                                );
                    }));
        });
    }

    private CompletionStage<Boolean> loadSource(
            World source,
            ExactCheckpointRecallRecoveryAuthor.RecoveryPlan plan
    ) {
        if (plan.sourceAlreadyProbed()) {
            return CompletableFuture.completedFuture(true);
        }
        CompanionEntityCheckpoint checkpoint = plan.checkpoint();
        ChunkStore chunks = source.getChunkStore();
        if (chunks == null) {
            return CompletableFuture.completedFuture(false);
        }
        int flags = GetChunkFlags.SET_TICKING | GetChunkFlags.NO_GENERATE;
        CompletionStage<?> sectionLoad =
                chunks.getChunkSectionReferenceAtBlockAsync(
                        floor(checkpoint.x()),
                        floor(checkpoint.y()),
                        floor(checkpoint.z()),
                        flags
                );
        return sourceProbeCompleted(
                sectionLoad,
                () -> source.isAlive() && !chunks.getStore().isShutdown()
        );
    }

    /** Accepts clean absence while rejecting load and shutdown failures. */
    static CompletionStage<Boolean> sourceProbeCompleted(
            CompletionStage<?> sectionLoad,
            BooleanSupplier sourceAvailable
    ) {
        Objects.requireNonNull(sectionLoad, "sectionLoad");
        Objects.requireNonNull(sourceAvailable, "sourceAvailable");
        return sectionLoad.handle((section, failure) ->
                failure == null && sourceAvailable.getAsBoolean()
        );
    }

    private CompletionStage<RecoveryOutcome> restore(
            World source,
            World destination,
            ExactCheckpointRecallRecoveryAuthor.RecoveryPlan plan
    ) {
        CompletableFuture<RecoveryOutcome> completion =
                new CompletableFuture<>();
        LeaseBoundWorldDispatcher.execute(
                destination,
                () -> completeRestore(
                        source, destination, plan, completion
                ),
                () -> completion.complete(RecoveryOutcome.NONE)
        );
        return completion;
    }

    private void completeRestore(
            World source,
            World destination,
            ExactCheckpointRecallRecoveryAuthor.RecoveryPlan plan,
            CompletableFuture<RecoveryOutcome> completion
    ) {
        UUID alias = plan.checkpoint().alias().value();
        try {
            LoadedNpcIdentityIndex.Probe current = identities.probe(alias);
            if (current.status()
                    != LoadedNpcIdentityIndex.ProbeStatus.ABSENT
                    || live(destination, alias)) {
                completion.complete(RecoveryOutcome.RETRY_REQUIRED);
                return;
            }
            EntityStore external = destination.getEntityStore();
            Store<EntityStore> store = external == null
                    ? null : external.getStore();
            Holder<EntityStore> holder = holders.prepare(plan);
            if (store == null || holder == null) {
                completion.complete(RecoveryOutcome.NONE);
                return;
            }
            store.assertThread();
            Ref<EntityStore> restored = store.addEntity(
                    holder, AddReason.LOAD
            );
            if (restored == null || !restored.isValid()
                    || !live(destination, alias)) {
                completion.complete(RecoveryOutcome.NONE);
                return;
            }
            CompanionSpawnAuthorityService.detach(restored, store);
            removeReturnedSource(source, destination, plan.checkpoint());
            logger.at(Level.INFO).log(
                    "Restored the exact saved companion state after Recall "
                            + "confirmed its source body was absent: npc=%s",
                    alias
            );
            completion.complete(RecoveryOutcome.RECOVERED);
        } catch (RuntimeException | LinkageError failure) {
            completion.completeExceptionally(failure);
        }
    }

    private void removeReturnedSource(
            World source,
            World destination,
            CompanionEntityCheckpoint checkpoint
    ) {
        if (!returnedOriginal(checkpoint)) {
            return;
        }
        Runnable removal = () -> {
            Ref<EntityStore> sourceRef = source.getEntityRef(
                    checkpoint.sourceAlias().value()
            );
            EntityStore external = source.getEntityStore();
            Store<EntityStore> store = external == null
                    ? null : external.getStore();
            if (sourceRef != null && sourceRef.isValid() && store != null) {
                store.removeEntity(sourceRef, RemoveReason.REMOVE);
            }
        };
        if (source == destination) {
            removal.run();
            return;
        }
        LeaseBoundWorldDispatcher.execute(source, removal);
    }

    private static boolean returnedOriginal(
            CompanionEntityCheckpoint checkpoint
    ) {
        return !checkpoint.alias().equals(checkpoint.sourceAlias())
                && checkpoint.boundary()
                == CompanionEntityCheckpoint.CaptureBoundary
                .RETURNED_RETIRED_ORIGINAL;
    }

    private static boolean live(World world, UUID alias) {
        if (world == null || alias == null) {
            return false;
        }
        Ref<EntityStore> reference = world.getEntityRef(alias);
        return reference != null && reference.isValid();
    }

    @Nullable
    private static World world(@Nullable Universe universe, String name) {
        return universe == null || name == null ? null
                : universe.getWorld(name);
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static <T> CompletionStage<RecoveryOutcome> readFailure(
            PersistenceReadResult.Failed<T> failed
    ) {
        return CompletableFuture.failedFuture(new IllegalStateException(
                failed.failure().code(), failed.failure().cause()
        ));
    }

    private record RestoreAttempt(
            @Nullable RecoveryOutcome outcome,
            @Nullable Throwable problem
    ) {
    }
}
