package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.InventorySetActiveSlotEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Marks the command-target HUD activation cache dirty when a player's selected hotbar slot changes.
 */
public final class CommandTargetHudActiveSlotSystem extends EntityEventSystem<EntityStore, InventorySetActiveSlotEvent> {
    private final CommandHudDirtySink dirtySink;

    public CommandTargetHudActiveSlotSystem(@Nonnull CommandHudDirtySink dirtySink) {
        super(InventorySetActiveSlotEvent.class);
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
                       @Nonnull InventorySetActiveSlotEvent event) {
        if (event.getInventorySectionId() != InventoryComponent.HOTBAR_SECTION_ID) {
            return;
        }
        Player player = archetypeChunk.getComponent(index, Player.getComponentType());
        UUID playerUuid = player != null ? player.getUuid() : null;
        dirtySink.markDirty(store, playerUuid);
    }
}
