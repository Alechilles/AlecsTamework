package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Saves exact player ECS evidence and re-enters its current world safely. */
final class HytalePlayerDurabilityBarrier {
    private final World world;
    private final Store<EntityStore> store;
    private final String worldKey;
    private final UUID actorUuid;

    HytalePlayerDurabilityBarrier(
            World world,
            Store<EntityStore> store,
            String worldKey,
            UUID actorUuid
    ) {
        this.world = world;
        this.store = store;
        this.worldKey = worldKey;
        this.actorUuid = actorUuid;
    }

    CompletionStage<SaveResult> saveActor() {
        try {
            store.assertThread();
            Ref<EntityStore> actor = world.getEntityRef(actorUuid);
            ComponentType<EntityStore, Player> playerType =
                    Player.getComponentType();
            if (actor == null || !actor.isValid()
                    || playerType == null) {
                return completed(SaveResult.retryable(null));
            }
            Player player = store.getComponent(actor, playerType);
            if (player == null) {
                return completed(SaveResult.retryable(null));
            }
            CompletionStage<Void> save = player.saveConfig(
                    world,
                    HytalePlayerSaveHolderFactory.create(store, actor),
                    true
            );
            if (save == null) {
                return completed(SaveResult.retryable(null));
            }
            CompletableFuture<SaveResult> completion =
                    new CompletableFuture<>();
            save.whenComplete((ignored, failure) ->
                    completion.complete(failure == null
                            ? SaveResult.success()
                            : SaveResult.retryable(failure)));
            return completion;
        } catch (RuntimeException | LinkageError failure) {
            return completed(SaveResult.retryable(failure));
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
            World current = Universe.get().getWorld(worldKey);
            if (current != world
                    || world.getEntityStore().getStore() != store) {
                completion.complete(LiveOperationResult.retryable(
                        "capture_world_instance_changed", null
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

    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }

    record SaveResult(boolean saved, Throwable failure) {
        static SaveResult success() {
            return new SaveResult(true, null);
        }

        static SaveResult retryable(Throwable failure) {
            return new SaveResult(false, failure);
        }
    }
}
