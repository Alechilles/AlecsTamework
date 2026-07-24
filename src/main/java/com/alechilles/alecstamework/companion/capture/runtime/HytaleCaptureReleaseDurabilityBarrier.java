package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseRequest;
import com.alechilles.alecstamework.companion.capture.runtime.CaptureReleaseWorldAttempt.ReceiptPersistence;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.storage.IChunkSaver;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;

/**
 * Starts exact player and target-chunk saves on the owning world thread.
 *
 * <p>Save callbacks map only future results. They never resolve entities, components, chunks, or
 * worlds; all such work occurs before the save starts or after explicit world-thread re-entry.</p>
 */
final class HytaleCaptureReleaseDurabilityBarrier {
    private final World world;
    private final Store<EntityStore> store;
    private final CompanionCaptureReleaseRequest request;

    HytaleCaptureReleaseDurabilityBarrier(
            World world,
            Store<EntityStore> store,
            CompanionCaptureReleaseRequest request
    ) {
        this.world = world;
        this.store = store;
        this.request = request;
    }

    CompletionStage<ReceiptPersistence> saveActorReceipt() {
        try {
            store.assertThread();
            Ref<EntityStore> actor =
                    world.getEntityRef(request.source().actorUuid());
            ComponentType<EntityStore, Player> playerType =
                    Player.getComponentType();
            if (actor == null || !actor.isValid() || playerType == null) {
                return completed(ReceiptPersistence.retryable(null));
            }
            Player player = store.getComponent(actor, playerType);
            if (player == null) {
                return completed(ReceiptPersistence.retryable(null));
            }
            CompletableFuture<Void> save = player.saveConfig(
                    world,
                    store.copySerializableEntity(actor),
                    true
            );
            return mapPlayerSave(save);
        } catch (RuntimeException | LinkageError failure) {
            return completed(ReceiptPersistence.retryable(failure));
        }
    }

    CompletionStage<ReceiptPersistence> saveTargetChunkReceipt(
            long expectedChunkIndex
    ) {
        try {
            store.assertThread();
            ChunkStore chunkStore = world.getChunkStore();
            Ref<EntityStore> target =
                    world.getEntityRef(request.targetAlias().value());
            ComponentType<EntityStore, TransformComponent> transformType =
                    TransformComponent.getComponentType();
            if (chunkStore == null || target == null || !target.isValid()
                    || transformType == null) {
                return completed(ReceiptPersistence.retryable(null));
            }
            TransformComponent transform =
                    store.getComponent(target, transformType);
            Ref<ChunkStore> chunkRef =
                    transform == null ? null : transform.getChunkRef();
            Store<ChunkStore> chunkComponents =
                    chunkStore == null ? null : chunkStore.getStore();
            WorldChunk chunk = chunkRef == null || !chunkRef.isValid()
                    || chunkComponents == null
                    ? null
                    : chunkComponents.getComponent(
                            chunkRef,
                            WorldChunk.getComponentType()
                    );
            if (chunk == null
                    || chunk.getWorld() != world) {
                return completed(ReceiptPersistence.retryable(null));
            }
            if (chunk.getIndex() != expectedChunkIndex) {
                return completed(ReceiptPersistence.retryable(null));
            }
            IChunkSaver saver = chunkStore.getSaver();
            if (saver == null) {
                return completed(ReceiptPersistence.retryable(null));
            }
            CompletableFuture<Void> save = saver.saveHolder(
                    chunk.getX(),
                    chunk.getZ(),
                    chunk.toHolder()
            );
            return mapChunkSave(
                    save,
                    saver::flush,
                    expectedChunkIndex
            );
        } catch (RuntimeException | LinkageError failure) {
            return completed(ReceiptPersistence.retryable(failure));
        }
    }

    CompletionStage<LiveOperationResult> resumeOnWorldThread(
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
                        "capture_release_world_instance_changed",
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
                                "World continuation returned no result"
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

    private static CompletionStage<ReceiptPersistence> mapPlayerSave(
            CompletionStage<Void> save
    ) {
        if (save == null) {
            return completed(ReceiptPersistence.retryable(null));
        }
        CompletableFuture<ReceiptPersistence> completion =
                new CompletableFuture<>();
        save.whenComplete((ignored, failure) -> completion.complete(
                failure == null
                        ? ReceiptPersistence.saved()
                        : ReceiptPersistence.retryable(failure)
        ));
        return completion;
    }

    static CompletionStage<ReceiptPersistence> mapChunkSave(
            CompletionStage<Void> save,
            ChunkFlusher flusher,
            long targetChunkIndex
    ) {
        return mapChunkSave(
                save,
                flusher,
                targetChunkIndex,
                ForkJoinPool.commonPool()
        );
    }

    static CompletionStage<ReceiptPersistence> mapChunkSave(
            CompletionStage<Void> save,
            ChunkFlusher flusher,
            long targetChunkIndex,
            Executor ioExecutor
    ) {
        if (save == null || flusher == null || ioExecutor == null) {
            return completed(ReceiptPersistence.retryable(null));
        }
        CompletableFuture<ReceiptPersistence> completion =
                new CompletableFuture<>();
        save.whenComplete((ignored, failure) -> {
            if (failure != null) {
                completion.complete(ReceiptPersistence.retryable(failure));
                return;
            }
            CompletableFuture.runAsync(() -> {
                try {
                    flusher.flush();
                } catch (Exception | LinkageError flushFailure) {
                    throw new CompletionException(flushFailure);
                }
            }, ioExecutor).whenComplete((unused, flushFailure) ->
                    completion.complete(
                            flushFailure == null
                                    ? ReceiptPersistence.savedTargetChunk(
                                            targetChunkIndex
                                    )
                                    : ReceiptPersistence.retryable(
                                            flushFailure
                                    )
                    )
            );
        });
        return completion;
    }

    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }

    @FunctionalInterface
    interface ChunkFlusher {
        void flush() throws Exception;
    }
}
