package com.alechilles.alecstamework.companion.provisioning.runtime;

import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationRequest;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.runtime.chunk.HytaleChunkSaveSupport;
import com.alechilles.alecstamework.persistence.runtime.chunk.HytaleEntityChunkDurabilityBarrier;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Thin Hytale bridge for one exact provisioning activation attempt. */
final class HytaleProvisioningActivationWorldAttempt
        implements ProvisioningActivationWorldAttempt {
    private final World world;
    private final Store<EntityStore> store;
    private final ProvisioningActivationRequest request;
    private final HytaleProvisioningActivationProjectionGateway projection;
    private final HytaleEntityChunkDurabilityBarrier durability;

    HytaleProvisioningActivationWorldAttempt(
            World world,
            Store<EntityStore> store,
            ProvisioningActivationRequest request,
            HytaleProvisioningActivationProjectionGateway projection
    ) {
        this.world = world;
        this.store = store;
        this.request = request;
        this.projection = projection;
        this.durability = new HytaleEntityChunkDurabilityBarrier(
                world, store, request.targetAlias().value()
        );
    }

    @Override
    public ProjectionProbe probe() {
        return projection.probe();
    }

    @Override
    public ProjectionProbe probeInTargetChunk(long expectedChunkIndex) {
        return projection.probeInChunk(expectedChunkIndex);
    }

    @Override
    public ProjectionAttempt applyOrResolveExactProjection() {
        return projection.applyOrResolve();
    }

    @Override
    public CompletionStage<ChunkPersistence> persistTargetChunk(
            long chunkIndex
    ) {
        ProjectionProbe exact = projection.probeInChunk(chunkIndex);
        if (exact.status() == ProjectionStatus.UNAVAILABLE) {
            return completed(ChunkPersistence.retryable(exact.cause()));
        }
        if (exact.status() != ProjectionStatus.EXACT) {
            return completed(ChunkPersistence.conflict(exact.cause()));
        }
        return durability.saveTarget().thenApply(outcome ->
                persistence(outcome, chunkIndex)
        );
    }

    @Override
    public CompletionStage<LiveOperationResult> resumeOnWorldThread(
            Supplier<CompletionStage<LiveOperationResult>> continuation
    ) {
        CompletableFuture<LiveOperationResult> completion =
                new CompletableFuture<>();
        try {
            world.execute(() -> resume(continuation, completion));
        } catch (Throwable failure) {
            completion.completeExceptionally(failure);
        }
        return completion;
    }

    private void resume(
            Supplier<CompletionStage<LiveOperationResult>> continuation,
            CompletableFuture<LiveOperationResult> completion
    ) {
        try {
            World current = Universe.get().getWorld(
                    request.targetWorldKey()
            );
            if (current != world
                    || world.getEntityStore().getStore() != store) {
                completion.complete(LiveOperationResult.retryable(
                        "provisioning_activation_world_instance_changed",
                        null
                ));
                return;
            }
            store.assertThread();
            CompletionStage<LiveOperationResult> result =
                    continuation.get();
            if (result == null) {
                completion.completeExceptionally(
                        new IllegalStateException(
                                "Provisioning continuation returned no result"
                        )
                );
                return;
            }
            result.whenComplete((resolved, failure) -> {
                if (failure != null) {
                    completion.completeExceptionally(failure);
                } else {
                    completion.complete(resolved);
                }
            });
        } catch (Throwable failure) {
            completion.completeExceptionally(failure);
        }
    }

    private ChunkPersistence persistence(
            HytaleChunkSaveSupport.Outcome outcome,
            long chunkIndex
    ) {
        return outcome.saved()
                ? ChunkPersistence.saved(chunkIndex)
                : ChunkPersistence.retryable(outcome.failure());
    }

    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }
}
