package com.alechilles.alecstamework.items.scarecrow;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

/** Bridges native scarecrow block placement and breaking to its paired spawn suppressor. */
public final class ScarecrowBlockEventSystems {
    private ScarecrowBlockEventSystems() {
    }

    /** Reconciles the suppressor after native placement has completed. */
    public static final class Placed extends EntityEventSystem<EntityStore, PlaceBlockEvent> {
        private final ScarecrowSuppressorService suppressors = new ScarecrowSuppressorService();

        public Placed() {
            super(PlaceBlockEvent.class);
        }

        @Override
        public void handle(
                int index,
                @Nonnull ArchetypeChunk<EntityStore> chunk,
                @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer,
                @Nonnull PlaceBlockEvent event
        ) {
            ItemStack itemInHand = event.getItemInHand();
            if (itemInHand == null || !ScarecrowIds.ITEM_ID.equals(itemInHand.getItemId())) {
                return;
            }
            World world = store.getExternalData().getWorld();
            Vector3i blockPosition = new Vector3i(event.getTargetBlock());
            world.execute(() -> suppressors.reconcilePlaced(world, blockPosition));
        }

        @Override
        @Nullable
        public Query<EntityStore> getQuery() {
            return Archetype.empty();
        }
    }

    /** Reconciles the suppressor after native breaking has completed. */
    public static final class Broken extends EntityEventSystem<EntityStore, BreakBlockEvent> {
        private final ScarecrowSuppressorService suppressors = new ScarecrowSuppressorService();

        public Broken() {
            super(BreakBlockEvent.class);
        }

        @Override
        public void handle(
                int index,
                @Nonnull ArchetypeChunk<EntityStore> chunk,
                @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer,
                @Nonnull BreakBlockEvent event
        ) {
            if (!ScarecrowIds.ITEM_ID.equals(event.getBlockType().getId())) {
                return;
            }
            World world = store.getExternalData().getWorld();
            Vector3i blockPosition = new Vector3i(event.getTargetBlock());
            world.execute(() -> suppressors.reconcileBroken(world, blockPosition));
        }

        @Override
        @Nullable
        public Query<EntityStore> getQuery() {
            return Archetype.empty();
        }
    }
}
