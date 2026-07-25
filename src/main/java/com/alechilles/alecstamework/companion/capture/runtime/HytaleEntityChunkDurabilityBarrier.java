package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureTameWorldAttempt.ReceiptPersistence;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.storage.IChunkSaver;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ForkJoinPool;

/** Saves and flushes the exact current chunk containing one stable live entity UUID. */
final class HytaleEntityChunkDurabilityBarrier {
    private final World world;
    private final Store<EntityStore> store;
    private final UUID targetUuid;

    HytaleEntityChunkDurabilityBarrier(
            World world,
            Store<EntityStore> store,
            UUID targetUuid
    ) {
        this.world = world;
        this.store = store;
        this.targetUuid = targetUuid;
    }

    CompletionStage<ReceiptPersistence> saveTarget() {
        try {
            store.assertThread();
            SaveContext context = resolve();
            if (context == null) {
                return completed(ReceiptPersistence.retryable(null));
            }
            CompletableFuture<Void> save = context.saver().saveHolder(
                    context.chunk().getX(),
                    context.chunk().getZ(),
                    context.chunk().toHolder()
            );
            return HytaleChunkSaveSupport.saveAndFlush(
                    save,
                    context.saver()::flush,
                    ForkJoinPool.commonPool()
            ).thenApply(outcome -> outcome.saved()
                    ? ReceiptPersistence.saved()
                    : ReceiptPersistence.retryable(outcome.failure()));
        } catch (RuntimeException | LinkageError failure) {
            return completed(ReceiptPersistence.retryable(failure));
        }
    }

    private SaveContext resolve() {
        Ref<EntityStore> target = world.getEntityRef(targetUuid);
        ComponentType<EntityStore, TransformComponent> transformType =
                TransformComponent.getComponentType();
        if (target == null || !target.isValid() || transformType == null) {
            return null;
        }
        TransformComponent transform =
                store.getComponent(target, transformType);
        Ref<ChunkStore> chunkRef =
                transform == null ? null : transform.getChunkRef();
        ChunkStore chunkStore = world.getChunkStore();
        Store<ChunkStore> chunks =
                chunkStore == null ? null : chunkStore.getStore();
        WorldChunk chunk = chunkRef == null || !chunkRef.isValid()
                || chunks == null
                ? null
                : chunks.getComponent(
                        chunkRef, WorldChunk.getComponentType()
                );
        IChunkSaver saver =
                chunkStore == null ? null : chunkStore.getSaver();
        return chunk == null || chunk.getWorld() != world || saver == null
                ? null
                : new SaveContext(chunk, saver);
    }

    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }

    private record SaveContext(WorldChunk chunk, IChunkSaver saver) {
    }
}
