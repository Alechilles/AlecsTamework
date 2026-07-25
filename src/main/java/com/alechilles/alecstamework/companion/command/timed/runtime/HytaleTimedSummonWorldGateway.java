package com.alechilles.alecstamework.companion.command.timed.runtime;

import com.alechilles.alecstamework.companion.command.timed
        .TimedSummonTransitionRequest;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.items
        .HytaleCompanionProjectionSpawnExecutor;
import com.alechilles.alecstamework.npc.components
        .TameworkPersistenceRetirementComponent;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.runtime
        .HytaleAsyncWorldOperationGateway;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Current-world Hytale gateway for timed live work and post-durable cleanup. */
public final class HytaleTimedSummonWorldGateway
        implements HytaleAsyncWorldOperationGateway<
        TimedSummonTransitionRequest> {
    private final SnapshotCodecRegistry snapshotCodecs;
    private final HytaleCompanionProjectionSpawnExecutor projections;
    private final ComponentType<
            EntityStore,
            TameworkPersistenceRetirementComponent> retirementType;
    private final TimedSummonWorldExecutor liveExecutor;
    private final TimedSummonDurableCleanupExecutor cleanupExecutor;

    public HytaleTimedSummonWorldGateway(
            @Nonnull SnapshotCodecRegistry snapshotCodecs,
            @Nonnull HytaleCompanionProjectionSpawnExecutor projections,
            @Nonnull ComponentType<
                    EntityStore,
                    TameworkPersistenceRetirementComponent> retirementType
    ) {
        this(
                snapshotCodecs,
                projections,
                retirementType,
                new TimedSummonWorldExecutor(),
                new TimedSummonDurableCleanupExecutor()
        );
    }

    HytaleTimedSummonWorldGateway(
            SnapshotCodecRegistry snapshotCodecs,
            HytaleCompanionProjectionSpawnExecutor projections,
            ComponentType<
                    EntityStore,
                    TameworkPersistenceRetirementComponent> retirementType,
            TimedSummonWorldExecutor liveExecutor,
            TimedSummonDurableCleanupExecutor cleanupExecutor
    ) {
        this.snapshotCodecs = Objects.requireNonNull(
                snapshotCodecs, "snapshotCodecs"
        );
        this.projections = Objects.requireNonNull(
                projections, "projections"
        );
        this.retirementType = Objects.requireNonNull(
                retirementType, "retirementType"
        );
        this.liveExecutor = Objects.requireNonNull(
                liveExecutor, "liveExecutor"
        );
        this.cleanupExecutor = Objects.requireNonNull(
                cleanupExecutor, "cleanupExecutor"
        );
    }

    @Override
    @Nonnull
    public CompletionStage<LiveOperationResult> applyOrResolveAsync(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull TimedSummonTransitionRequest request,
            @Nonnull OperationEnvelope operation
    ) {
        LiveOperationResult invalid = validate(world, store);
        return invalid == null
                ? liveExecutor.execute(
                        request,
                        operation,
                        attempts(world, store, request, operation)
                )
                : invalid.completed();
    }

    @Nonnull
    CompletionStage<LiveOperationResult> cleanupAfterDurable(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull TimedSummonTransitionRequest request,
            @Nonnull OperationEnvelope operation
    ) {
        LiveOperationResult invalid = validate(world, store);
        return invalid == null
                ? cleanupExecutor.execute(
                        request,
                        operation,
                        attempts(world, store, request, operation)
                )
                : invalid.completed();
    }

    private HytaleTimedSummonAttemptGateway attempts(
            World world,
            Store<EntityStore> store,
            TimedSummonTransitionRequest request,
            OperationEnvelope operation
    ) {
        return new HytaleTimedSummonAttemptGateway(
                world,
                store,
                request,
                operation,
                snapshotCodecs,
                projections,
                retirementType
        );
    }

    private LiveOperationResult validate(
            World world,
            Store<EntityStore> store
    ) {
        if (world == null || store == null) {
            return LiveOperationResult.retryable(
                    "timed_summon_world_context_missing", null
            );
        }
        try {
            store.assertThread();
            return null;
        } catch (RuntimeException | LinkageError failure) {
            return LiveOperationResult.retryable(
                    "timed_summon_world_thread_unavailable", failure
            );
        }
    }
}
