package com.alechilles.alecstamework.items.persistence;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Returns asynchronous gameplay completion to a current player on its world thread.
 *
 * <p>Only a world key and actor UUID cross the asynchronous boundary. The current world, entity
 * reference, store, and Player component are all resolved inside {@link World#execute(Runnable)}.
 * If the player moved, disconnected, or the named world was replaced, no stale completion runs.</p>
 */
public final class HytaleUuidCompletionDispatcher {
    private final WorldAccess worlds;

    public HytaleUuidCompletionDispatcher() {
        this(new HytaleWorldAccess(
                worldKey -> Universe.get().getWorld(worldKey)
        ));
    }

    HytaleUuidCompletionDispatcher(
            @Nonnull Function<String, World> worldLookup
    ) {
        this(new HytaleWorldAccess(
                Objects.requireNonNull(
                        worldLookup, "World lookup is required"
                )
        ));
    }

    HytaleUuidCompletionDispatcher(@Nonnull WorldAccess worlds) {
        this.worlds = Objects.requireNonNull(
                worlds, "World access is required"
        );
    }

    /**
     * Schedules one completion for a stable actor identity.
     *
     * @return true when accepted by a current world executor; false when it could not be scheduled
     */
    public boolean dispatch(
            @Nullable String worldKey,
            @Nullable UUID actorUuid,
            @Nullable Completion completion
    ) {
        if (worldKey == null || worldKey.isBlank()
                || actorUuid == null || completion == null) {
            return false;
        }
        String targetWorld = worldKey.trim();
        World scheduled = worlds.findWorld(targetWorld);
        if (scheduled == null) {
            return false;
        }
        try {
            worlds.execute(scheduled, () -> completeOnWorldThread(
                    scheduled,
                    targetWorld,
                    actorUuid,
                    completion
            ));
            return true;
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private void completeOnWorldThread(
            World scheduled,
            String worldKey,
            UUID actorUuid,
            Completion completion
    ) {
        World current = worlds.findWorld(worldKey);
        if (current == null || current != scheduled) {
            return;
        }
        try {
            ActorState actor = worlds.resolveActor(current, actorUuid);
            if (actor == null) {
                return;
            }
            completion.complete(
                    current,
                    actor.store(),
                    actor.reference(),
                    actor.player()
            );
        } catch (RuntimeException | LinkageError ignored) {
            // Completion feedback is best-effort after the durable operation resolves.
        }
    }

    interface WorldAccess {
        @Nullable
        World findWorld(@Nonnull String worldKey);

        void execute(@Nonnull World world, @Nonnull Runnable task);

        @Nullable
        ActorState resolveActor(
                @Nonnull World world,
                @Nonnull UUID actorUuid
        );
    }

    record ActorState(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> reference,
            @Nonnull Player player
    ) {
        ActorState {
            Objects.requireNonNull(store, "Actor store is required");
            Objects.requireNonNull(reference, "Actor reference is required");
            Objects.requireNonNull(player, "Actor player is required");
        }
    }

    private static final class HytaleWorldAccess implements WorldAccess {
        private final Function<String, World> worldLookup;

        private HytaleWorldAccess(Function<String, World> worldLookup) {
            this.worldLookup = worldLookup;
        }

        @Override
        @Nullable
        public World findWorld(@Nonnull String worldKey) {
            try {
                return worldLookup.apply(worldKey);
            } catch (RuntimeException | LinkageError failure) {
                return null;
            }
        }

        @Override
        public void execute(
                @Nonnull World world,
                @Nonnull Runnable task
        ) {
            world.execute(task);
        }

        @Override
        @Nullable
        public ActorState resolveActor(
                @Nonnull World world,
                @Nonnull UUID actorUuid
        ) {
            if (!world.isAlive() || world.getEntityStore() == null
                    || Player.getComponentType() == null) {
                return null;
            }
            Store<EntityStore> store =
                    world.getEntityStore().getStore();
            Ref<EntityStore> actorRef = world.getEntityRef(actorUuid);
            if (store == null || actorRef == null || !actorRef.isValid()) {
                return null;
            }
            Player actor = store.getComponent(
                    actorRef, Player.getComponentType()
            );
            return actor == null
                    ? null
                    : new ActorState(store, actorRef, actor);
        }
    }

    /** Runs only on the current world thread with freshly resolved live actor state. */
    @FunctionalInterface
    public interface Completion {
        void complete(
                @Nonnull World world,
                @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> actorRef,
                @Nonnull Player actor
        );
    }
}
