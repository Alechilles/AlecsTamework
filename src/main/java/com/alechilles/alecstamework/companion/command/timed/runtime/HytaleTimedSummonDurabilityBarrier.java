package com.alechilles.alecstamework.companion.command.timed.runtime;

import com.alechilles.alecstamework.compat.HytaleChunkAccess;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.runtime.chunk
        .HytaleChunkSaveSupport;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.storage.IChunkSaver;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;

/**
 * Saves one exact loaded chunk and resumes only in the same named world instance.
 *
 * <p>No ECS object is touched by save or flush callbacks. The continuation resolves all live
 * evidence again after explicit world-thread re-entry.</p>
 */
final class HytaleTimedSummonDurabilityBarrier {
    private final World world;
    private final Store<EntityStore> store;
    private final String worldKey;

    HytaleTimedSummonDurabilityBarrier(
            World world,
            Store<EntityStore> store,
            String worldKey
    ) {
        this.world = world;
        this.store = store;
        this.worldKey = worldKey;
    }

    CompletionStage<HytaleChunkSaveSupport.Outcome> save(
            long chunkIndex
    ) {
        try {
            store.assertThread();
            WorldChunk chunk = world.getChunkIfInMemory(chunkIndex);
            ChunkStore chunkStore = world.getChunkStore();
            IChunkSaver saver =
                    chunkStore == null ? null : chunkStore.getSaver();
            if (chunk == null || chunk.getWorld() != world
                    || chunk.getIndex() != chunkIndex || saver == null) {
                return completed(
                        HytaleChunkSaveSupport.Outcome.retryable(null)
                );
            }
            CompletableFuture<Void> save = HytaleChunkAccess.saveColumn(
                    saver,
                    chunk,
                    world
            );
            return HytaleChunkSaveSupport.saveAndFlush(
                    save,
                    saver::flush,
                    ForkJoinPool.commonPool()
            );
        } catch (RuntimeException | LinkageError failure) {
            return completed(
                    HytaleChunkSaveSupport.Outcome.retryable(failure)
            );
        }
    }

    CompletionStage<LiveOperationResult> resume(
            Supplier<CompletionStage<LiveOperationResult>> continuation
    ) {
        CompletableFuture<LiveOperationResult> completion =
                new CompletableFuture<>();
        try {
            world.execute(() -> {
                try {
                    World current = Universe.get().getWorld(worldKey);
                    if (current != world
                            || current.getEntityStore().getStore()
                            != store) {
                        completion.complete(
                                LiveOperationResult.retryable(
                                        "timed_summon_world_instance_changed",
                                        null
                                )
                        );
                        return;
                    }
                    store.assertThread();
                    CompletionStage<LiveOperationResult> resumed =
                            continuation.get();
                    if (resumed == null) {
                        completion.complete(
                                LiveOperationResult.retryable(
                                        "timed_summon_world_resume_missing",
                                        null
                                )
                        );
                        return;
                    }
                    resumed.whenComplete((result, failure) ->
                            completion.complete(failure == null
                                    && result != null
                                    ? result
                                    : LiveOperationResult.retryable(
                                            "timed_summon_world_resume_failed",
                                            failure
                                    ))
                    );
                } catch (Throwable failure) {
                    completion.complete(
                            LiveOperationResult.retryable(
                                    "timed_summon_world_resume_failed",
                                    failure
                            )
                    );
                }
            });
        } catch (Throwable failure) {
            completion.complete(
                    LiveOperationResult.retryable(
                            "timed_summon_world_resume_failed", failure
                    )
            );
        }
        return completion;
    }

    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }
}
