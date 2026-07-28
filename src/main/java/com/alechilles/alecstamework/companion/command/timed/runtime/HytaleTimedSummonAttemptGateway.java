package com.alechilles.alecstamework.companion.command.timed.runtime;

import com.alechilles.alecstamework.companion.command.timed
        .TimedSummonTransitionRequest;
import com.alechilles.alecstamework.companion.command.timed.runtime
        .TimedSummonWorldAttempt.AttemptGateway;
import com.alechilles.alecstamework.companion.command.timed.runtime
        .TimedSummonWorldAttempt.ChunkPersistence;
import com.alechilles.alecstamework.companion.command.timed.runtime
        .TimedSummonWorldAttempt.MutationAttempt;
import com.alechilles.alecstamework.companion.command.timed.runtime
        .TimedSummonWorldAttempt.ProjectionProbe;
import com.alechilles.alecstamework.companion.command.timed.runtime
        .TimedSummonWorldAttempt.StoreProbe;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.items
        .HytaleCompanionProjectionSpawnExecutor;
import com.alechilles.alecstamework.npc.components
        .TameworkPersistenceRetirementComponent;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.runtime.chunk
        .HytaleChunkSaveSupport;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Thin adapter joining projection, marked-source, and shared chunk durability bridges. */
final class HytaleTimedSummonAttemptGateway implements AttemptGateway {
    private final HytaleTimedSummonProjectionGateway projection;
    private final HytaleTimedSummonStoreGateway storage;
    private final HytaleTimedSummonDurabilityBarrier durability;

    HytaleTimedSummonAttemptGateway(
            World world,
            Store<EntityStore> store,
            TimedSummonTransitionRequest request,
            OperationEnvelope operation,
            SnapshotCodecRegistry snapshotCodecs,
            HytaleCompanionProjectionSpawnExecutor projections,
            ComponentType<
                    EntityStore,
                    TameworkPersistenceRetirementComponent> retirementType
    ) {
        projection = new HytaleTimedSummonProjectionGateway(
                world, store, snapshotCodecs, projections
        );
        storage = new HytaleTimedSummonStoreGateway(
                world,
                store,
                operation,
                snapshotCodecs,
                retirementType
        );
        durability = new HytaleTimedSummonDurabilityBarrier(
                world, store, request.worldKey()
        );
    }

    @Override
    public ProjectionProbe probeStart(
            TimedSummonWorldAuthority.Start authority
    ) {
        return projection.probe(authority);
    }

    @Override
    public MutationAttempt spawnExact(
            TimedSummonWorldAuthority.Start authority
    ) {
        return projection.spawnExact(authority);
    }

    @Override
    public MutationAttempt releaseStartHold(
            TimedSummonWorldAuthority.Start authority
    ) {
        return projection.releaseHold(authority);
    }

    @Override
    public StoreProbe probeStore(
            TimedSummonWorldAuthority.Store authority
    ) {
        return storage.probe(authority);
    }

    @Override
    public MutationAttempt installRetirementReceipt(
            TimedSummonWorldAuthority.Store authority
    ) {
        return storage.installReceipt(authority);
    }

    @Override
    public MutationAttempt retireExactSource(
            TimedSummonWorldAuthority.Store authority
    ) {
        return storage.retireExact(authority);
    }

    @Override
    public CompletionStage<ChunkPersistence> persistChunkAndReadBack(
            long chunkIndex
    ) {
        return durability.save(chunkIndex).thenApply(
                outcome -> map(outcome, chunkIndex)
        );
    }

    @Override
    public CompletionStage<LiveOperationResult> resumeOnWorldThread(
            Supplier<CompletionStage<LiveOperationResult>> continuation
    ) {
        return durability.resume(continuation);
    }

    private ChunkPersistence map(
            HytaleChunkSaveSupport.Outcome outcome,
            long chunkIndex
    ) {
        return outcome.saved()
                ? ChunkPersistence.saved(chunkIndex)
                : ChunkPersistence.retryable(outcome.failure());
    }
}
