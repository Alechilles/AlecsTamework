package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseRequest;
import com.alechilles.alecstamework.companion.capture.runtime.CaptureReleaseWorldAttempt.ReceiptPersistence;
import com.alechilles.alecstamework.compat.HytaleChunkAccess;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.runtime.chunk
        .HytaleChunkSaveSupport;
import com.alechilles.alecstamework.persistence.runtime.player
        .HytalePlayerDurabilityBarrier;
import com.alechilles.alecstamework.persistence.runtime.player
        .HytalePlayerDurabilityBarrier.SaveResult;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.storage.IChunkSaver;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import java.util.concurrent.CompletableFuture;
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
    private final HytalePlayerDurabilityBarrier playerDurability;

    HytaleCaptureReleaseDurabilityBarrier(
            World world,
            Store<EntityStore> store,
            CompanionCaptureReleaseRequest request
    ) {
        this.world = world;
        this.store = store;
        this.request = request;
        this.playerDurability = new HytalePlayerDurabilityBarrier(
                world,
                store,
                request.targetWorldKey(),
                request.source().actorUuid()
        );
    }

    CompletionStage<ReceiptPersistence> saveActorReceipt() {
        return playerDurability.saveActor().thenApply(
                HytaleCaptureReleaseDurabilityBarrier::mapPlayerSave
        );
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
            WorldChunk chunk = HytaleChunkAccess.currentWorldChunk(transform, world);
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
            CompletableFuture<Void> save = HytaleChunkAccess.saveColumn(
                    saver,
                    chunk,
                    world
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
        return playerDurability.resumeOnWorldThread(
                continuation,
                () -> LiveOperationResult.retryable(
                        "capture_release_world_instance_changed", null
                )
        );
    }

    private static ReceiptPersistence mapPlayerSave(SaveResult saved) {
        return saved.saved()
                ? ReceiptPersistence.saved()
                : ReceiptPersistence.retryable(saved.failure());
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
        return HytaleChunkSaveSupport.saveAndFlush(
                save,
                flusher::flush,
                ioExecutor
        ).thenApply(outcome -> outcome.saved()
                ? ReceiptPersistence.savedTargetChunk(targetChunkIndex)
                : ReceiptPersistence.retryable(outcome.failure()));
    }

    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }

    @FunctionalInterface
    interface ChunkFlusher {
        void flush() throws Exception;
    }
}
