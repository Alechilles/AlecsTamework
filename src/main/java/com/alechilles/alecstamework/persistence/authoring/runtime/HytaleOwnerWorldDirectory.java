package com.alechilles.alecstamework.persistence.authoring.runtime;

import com.alechilles.alecstamework.runtime.dispatch.LeaseBoundWorldDispatcher;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Tracks only stable owner IDs and their current world references.
 *
 * <p>Every consumer re-resolves the current entity reference and player
 * component on the selected world thread. Transfers are protected from stale
 * remove callbacks by matching the exact recorded world reference.</p>
 */
public final class HytaleOwnerWorldDirectory
        implements OwnerWorldSnapshotExecutor, AutoCloseable {
    private final ConcurrentHashMap<UUID, Entry> entries =
            new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final TrackingSystem trackingSystem;

    public HytaleOwnerWorldDirectory(
            @Nonnull ComponentType<EntityStore, PlayerRef> playerRefType
    ) {
        trackingSystem = new TrackingSystem(
                Objects.requireNonNull(playerRefType, "playerRefType"),
                this
        );
    }

    /** Returns the one ECS lifecycle hook that central composition must register. */
    @Nonnull
    public RefSystem<EntityStore> trackingSystem() {
        return trackingSystem;
    }

    @Override
    @Nonnull
    public <T> CompletionStage<T> read(
            @Nonnull UUID ownerUuid,
            @Nullable String expectedWorldKey,
            @Nonnull WorldSnapshotRead<T> read
    ) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(read, "read");
        if (closed.get()) {
            return CompletableFuture.completedFuture(null);
        }
        Entry selected = entries.get(ownerUuid);
        if (!matches(selected, expectedWorldKey)) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<T> completion = new CompletableFuture<>();
        LeaseBoundWorldDispatcher.execute(
                selected.world(),
                () -> completeOnWorldThread(
                        ownerUuid, selected, read, completion
                ),
                () -> completion.complete(null)
        );
        return completion;
    }

    @Override
    public void close() {
        closed.set(true);
        entries.clear();
    }

    private <T> void completeOnWorldThread(
            UUID ownerUuid,
            Entry selected,
            WorldSnapshotRead<T> read,
            CompletableFuture<T> completion
    ) {
        try {
            if (!selected.equals(entries.get(ownerUuid))
                    || !selected.world().isAlive()) {
                completion.complete(null);
                return;
            }
            World world = selected.world();
            Store<EntityStore> store = world.getEntityStore() == null
                    ? null : world.getEntityStore().getStore();
            Ref<EntityStore> actor = world.getEntityRef(ownerUuid);
            if (store == null || actor == null || !actor.isValid()
                    || actor.getStore() != store
                    || Player.getComponentType() == null
                    || PlayerRef.getComponentType() == null) {
                completion.complete(null);
                return;
            }
            Player player = store.getComponent(
                    actor, Player.getComponentType()
            );
            PlayerRef playerRef = store.getComponent(
                    actor, PlayerRef.getComponentType()
            );
            if (player == null || playerRef == null
                    || !ownerUuid.equals(playerRef.getUuid())) {
                completion.complete(null);
                return;
            }
            completion.complete(read.read(new HytaleOwnerWorldAccess(
                    ownerUuid,
                    selected.worldKey(),
                    world,
                    store,
                    actor,
                    player
            )));
        } catch (RuntimeException | LinkageError failure) {
            completion.complete(null);
        }
    }

    private boolean matches(
            @Nullable Entry entry,
            @Nullable String expectedWorldKey
    ) {
        return entry != null
                && entry.world().isAlive()
                && (expectedWorldKey == null
                || expectedWorldKey.trim().equals(entry.worldKey()));
    }

    private void record(
            UUID ownerUuid,
            String worldKey,
            World world
    ) {
        if (!closed.get()
                && ownerUuid != null && worldKey != null && !worldKey.isBlank()
                && world != null && world.isAlive()) {
            entries.put(
                    ownerUuid,
                    new Entry(worldKey.trim(), world)
            );
        }
    }

    private void remove(UUID ownerUuid, World world) {
        if (ownerUuid == null || world == null) {
            return;
        }
        entries.computeIfPresent(ownerUuid, (ignored, current) ->
                current.world() == world ? null : current
        );
    }

    private record Entry(
            @Nonnull String worldKey,
            @Nonnull World world
    ) {
    }

    /** Read-only player add/remove observer used as the directory lifecycle hook. */
    private static final class TrackingSystem extends RefSystem<EntityStore> {
        private final ComponentType<EntityStore, PlayerRef> playerRefType;
        private final HytaleOwnerWorldDirectory directory;

        private TrackingSystem(
                ComponentType<EntityStore, PlayerRef> playerRefType,
                HytaleOwnerWorldDirectory directory
        ) {
            this.playerRefType = playerRefType;
            this.directory = directory;
        }

        @Override
        public Query<EntityStore> getQuery() {
            return playerRefType;
        }

        @Override
        public void onEntityAdded(
                @Nonnull Ref<EntityStore> ref,
                @Nonnull com.hypixel.hytale.component.AddReason reason,
                @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer
        ) {
            PlayerRef playerRef = store.getComponent(ref, playerRefType);
            World world = resolveWorld(store);
            if (playerRef != null && world != null) {
                directory.record(
                        playerRef.getUuid(), world.getName(), world
                );
            }
        }

        @Override
        public void onEntityRemove(
                @Nonnull Ref<EntityStore> ref,
                @Nonnull com.hypixel.hytale.component.RemoveReason reason,
                @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer
        ) {
            PlayerRef playerRef = store.getComponent(ref, playerRefType);
            World world = resolveWorld(store);
            if (playerRef != null && world != null) {
                directory.remove(playerRef.getUuid(), world);
            }
        }

        @Nullable
        private World resolveWorld(Store<EntityStore> store) {
            return store.getExternalData() == null
                    ? null : store.getExternalData().getWorld();
        }
    }
}
