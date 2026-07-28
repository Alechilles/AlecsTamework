package com.alechilles.alecstamework.persistence.runtime.player;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Saves exact player ECS evidence and re-enters its originating world safely.
 *
 * <p>The barrier retains only stable actor/world identity across the async
 * save. Live components are resolved again on the owning world thread.</p>
 */
public final class HytalePlayerDurabilityBarrier {
    private final World world;
    private final Store<EntityStore> store;
    private final String worldKey;
    private final UUID actorUuid;

    public HytalePlayerDurabilityBarrier(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull String worldKey,
            @Nonnull UUID actorUuid
    ) {
        this.world = Objects.requireNonNull(world, "world");
        this.store = Objects.requireNonNull(store, "store");
        if (worldKey.isBlank()) {
            throw new IllegalArgumentException("Player world key is required");
        }
        this.worldKey = worldKey.trim();
        this.actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
    }

    /** Starts one exact player save on the owning world thread. */
    @Nonnull
    public CompletionStage<SaveResult> saveActor() {
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

    /**
     * Re-enters the exact originating world/store before resolving live state.
     *
     * @param unavailable value returned when that exact world instance changed
     */
    @Nonnull
    public <T> CompletionStage<T> resumeOnWorldThread(
            @Nonnull Supplier<CompletionStage<T>> continuation,
            @Nonnull Supplier<T> unavailable
    ) {
        Objects.requireNonNull(continuation, "continuation");
        Objects.requireNonNull(unavailable, "unavailable");
        CompletableFuture<T> completion = new CompletableFuture<>();
        try {
            world.execute(() -> resume(
                    continuation, unavailable, completion
            ));
        } catch (Throwable failure) {
            completion.completeExceptionally(failure);
        }
        return completion;
    }

    private <T> void resume(
            Supplier<CompletionStage<T>> continuation,
            Supplier<T> unavailable,
            CompletableFuture<T> completion
    ) {
        try {
            World current = Universe.get().getWorld(worldKey);
            if (current != world
                    || world.getEntityStore().getStore() != store) {
                completion.complete(unavailable.get());
                return;
            }
            store.assertThread();
            CompletionStage<T> result = continuation.get();
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

    /** Player-save proof resolved without accessing components off-thread. */
    public record SaveResult(
            boolean saved,
            @Nullable Throwable failure
    ) {
        public static SaveResult success() {
            return new SaveResult(true, null);
        }

        public static SaveResult retryable(@Nullable Throwable failure) {
            return new SaveResult(false, failure);
        }
    }
}
