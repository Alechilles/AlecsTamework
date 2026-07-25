package com.alechilles.alecstamework.companion.revival.runtime;

import com.alechilles.alecstamework.companion.revival.PaidRevivalLiveResult;
import com.alechilles.alecstamework.companion.revival.PaidRevivalRequest;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldAttempt.ActorPersistence;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldAttempt.AttemptGateway;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldAttempt.ChargeAttempt;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldAttempt.ProjectionAttempt;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldAttempt.ReceiptInstall;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldAttempt.TargetPersistence;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldEvidence.CompositeProbe;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldEvidence.SpawnProbe;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldEvidence.SpawnStatus;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.items.HytaleCompanionProjectionSpawnExecutor;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.runtime.chunk.HytaleChunkSaveSupport;
import com.alechilles.alecstamework.persistence.runtime.chunk.HytaleEntityChunkDurabilityBarrier;
import com.alechilles.alecstamework.persistence.runtime.player.HytalePlayerDurabilityBarrier;
import com.alechilles.alecstamework.persistence.runtime.player.TameworkInventoryOperationReceiptsComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Thin Hytale bridge for one exact paid-revival live attempt. */
final class HytalePaidRevivalAttemptGateway implements AttemptGateway {
    private final HytalePaidRevivalInventoryGateway inventory;
    private final HytalePaidRevivalProjectionGateway projection;
    private final HytalePlayerDurabilityBarrier actorDurability;
    private final HytaleEntityChunkDurabilityBarrier targetDurability;

    HytalePaidRevivalAttemptGateway(
            World world,
            Store<EntityStore> store,
            PaidRevivalRequest request,
            OperationEnvelope operation,
            ComponentType<
                    EntityStore,
                    TameworkInventoryOperationReceiptsComponent> receiptType,
            SnapshotCodecRegistry snapshotCodecs,
            HytaleCompanionProjectionSpawnExecutor projections
    ) {
        inventory = new HytalePaidRevivalInventoryGateway(
                world, store, request, operation, receiptType
        );
        projection = new HytalePaidRevivalProjectionGateway(
                world,
                store,
                request,
                operation,
                snapshotCodecs,
                projections
        );
        actorDurability = new HytalePlayerDurabilityBarrier(
                world,
                store,
                request.targetWorldKey(),
                request.familyKey().ownerId().value()
        );
        targetDurability = new HytaleEntityChunkDurabilityBarrier(
                world, store, request.targetAlias().value()
        );
    }

    @Override
    public CompositeProbe probeComposite() {
        return CompositeProbe.of(
                inventory.probeReceipt(),
                inventory.probeCharge(),
                projection.probe()
        );
    }

    @Override
    public CompositeProbe probeCompositeInTargetChunk(long chunkIndex) {
        return CompositeProbe.of(
                inventory.probeReceipt(),
                inventory.probeCharge(),
                projection.probeInChunk(chunkIndex)
        );
    }

    @Override
    public ReceiptInstall installExactReceipt() {
        return inventory.installPendingReceipt();
    }

    @Override
    public ChargeAttempt consumeExactRecipe() {
        return inventory.consumeExactRecipe();
    }

    @Override
    public ProjectionAttempt applyOrResolveProjection() {
        return projection.applyOrResolve();
    }

    @Override
    public CompletionStage<ActorPersistence> persistActor() {
        return actorDurability.saveActor().thenApply(result ->
                result.saved()
                        ? ActorPersistence.saved()
                        : ActorPersistence.retryable(result.failure())
        );
    }

    @Override
    public CompletionStage<TargetPersistence> persistTargetChunk(
            long chunkIndex
    ) {
        SpawnProbe exact = projection.probeInChunk(chunkIndex);
        if (exact.status() == SpawnStatus.UNAVAILABLE) {
            return completed(TargetPersistence.retryable(exact.cause()));
        }
        if (exact.status() != SpawnStatus.EXACT) {
            return completed(TargetPersistence.conflict(exact.cause()));
        }
        return targetDurability.saveTarget().thenApply(outcome ->
                targetPersistence(outcome, chunkIndex)
        );
    }

    @Override
    public CompletionStage<PaidRevivalLiveResult> resumeOnWorldThread(
            Supplier<CompletionStage<PaidRevivalLiveResult>> continuation
    ) {
        return actorDurability.resumeOnWorldThread(
                continuation,
                () -> PaidRevivalLiveResult.retryable(
                        "paid_revival_world_instance_changed", null
                )
        );
    }

    private TargetPersistence targetPersistence(
            HytaleChunkSaveSupport.Outcome outcome,
            long chunkIndex
    ) {
        return outcome.saved()
                ? TargetPersistence.saved(chunkIndex)
                : TargetPersistence.retryable(outcome.failure());
    }

    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }
}
