package com.alechilles.alecstamework.avatarflight;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.event.events.ecs.InventoryActiveSlotRequestEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Rejects client utility and tools selection while an avatar-flight control lock is active. */
public final class AvatarFlightInventoryGuardSystem
        extends EntityEventSystem<EntityStore, InventoryActiveSlotRequestEvent> {
    private final ComponentType<EntityStore, AvatarFlightComponent> flightType;
    private final Query<EntityStore> query;

    public AvatarFlightInventoryGuardSystem(
            @Nonnull ComponentType<EntityStore, AvatarFlightComponent> flightType) {
        super(InventoryActiveSlotRequestEvent.class);
        this.flightType = flightType;
        this.query = Query.and(flightType);
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void handle(int index,
                       @Nonnull ArchetypeChunk<EntityStore> chunk,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull InventoryActiveSlotRequestEvent event) {
        AvatarFlightComponent flight = chunk.getComponent(index, flightType);
        if (shouldCancel(flight, event)) {
            event.setCancelled(true);
        }
    }

    static boolean shouldCancel(@Nullable AvatarFlightComponent flight,
                                @Nonnull InventoryActiveSlotRequestEvent event) {
        if (flight == null || !event.isClientRequest()) {
            return false;
        }
        return event.getInventorySectionId() == InventoryComponent.UTILITY_SECTION_ID
                || event.getInventorySectionId() == InventoryComponent.TOOLS_SECTION_ID;
    }
}
