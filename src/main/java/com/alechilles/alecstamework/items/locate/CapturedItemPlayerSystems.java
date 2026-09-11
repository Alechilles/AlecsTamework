package com.alechilles.alecstamework.items.locate;

import com.alechilles.alecstamework.items.locate.CapturedItemLocationIndex.Kind;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.InventoryChangeEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/** Player sightings on load and capture-related changes only. No tick or player-list scan. */
public final class CapturedItemPlayerSystems {
    private CapturedItemPlayerSystems() { }

    public static final class Lifecycle extends RefSystem<EntityStore> {
        private final CapturedItemTracker tracker;
        public Lifecycle(CapturedItemTracker tracker) { this.tracker = tracker; }
        @Override public Query<EntityStore> getQuery() { return Player.getComponentType(); }
        @Override public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {
            Player player = buffer.getComponent(ref, Player.getComponentType());
            if (player != null && player.getUuid() != null) tracker.queue(CapturedItemTracker.entityHolder(
                    Kind.PLAYER, store.getExternalData().getWorld().getName(), player.getUuid()));
        }
        @Override public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {
            Player player = buffer.getComponent(ref, Player.getComponentType());
            if (player != null && player.getUuid() != null) tracker.index().unload(CapturedItemTracker.entityHolder(
                    Kind.PLAYER, store.getExternalData().getWorld().getName(), player.getUuid()));
        }
    }

    public static final class Changes extends EntityEventSystem<EntityStore, InventoryChangeEvent> {
        private final CapturedItemTracker tracker;
        public Changes(CapturedItemTracker tracker) { super(InventoryChangeEvent.class); this.tracker = tracker; }
        @Override public Query<EntityStore> getQuery() { return Player.getComponentType(); }
        @Override public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer,
                @Nonnull InventoryChangeEvent event) {
            if (!CapturedItemMetadata.affectsCapture(event.getTransaction())) return;
            Player player = chunk.getComponent(index, Player.getComponentType());
            if (player != null && player.getUuid() != null) tracker.queue(CapturedItemTracker.entityHolder(
                    Kind.PLAYER, store.getExternalData().getWorld().getName(), player.getUuid()));
        }
    }
}
