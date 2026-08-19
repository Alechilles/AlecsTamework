package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.InventoryChangeEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Marks the command-target HUD activation cache dirty when the player's hotbar contents change.
 */
public final class CommandTargetHudInventoryChangeSystem extends EntityEventSystem<EntityStore, InventoryChangeEvent> {
    private final CommandHudDirtySink dirtySink;

    public CommandTargetHudInventoryChangeSystem(@Nonnull CommandHudDirtySink dirtySink) {
        super(InventoryChangeEvent.class);
        this.dirtySink = dirtySink;
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Query.and(Player.getComponentType(), InventoryComponent.Hotbar.getComponentType());
    }

    @Override
    public void handle(int index,
                       @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull InventoryChangeEvent event) {
        InventoryComponent.Hotbar hotbar = archetypeChunk.getComponent(index, InventoryComponent.Hotbar.getComponentType());
        if (hotbar == null || event.getItemContainer() == null || !event.getItemContainer().equals(hotbar.getInventory())) {
            return;
        }
        Player player = archetypeChunk.getComponent(index, Player.getComponentType());
        UUID playerUuid = player != null ? player.getUuid() : null;
        dirtySink.markDirty(store, playerUuid);
    }
}
