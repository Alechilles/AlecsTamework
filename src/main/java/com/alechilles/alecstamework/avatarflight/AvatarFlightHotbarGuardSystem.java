package com.alechilles.alecstamework.avatarflight;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.protocol.packets.inventory.SetActiveSlot;
import com.hypixel.hytale.server.core.event.events.ecs.InventorySetActiveSlotEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Restores the talisman after hotbar swap interactions while avatar flight is active. */
public final class AvatarFlightHotbarGuardSystem
        extends EntityEventSystem<EntityStore, InventorySetActiveSlotEvent> {
    private final ComponentType<EntityStore, AvatarFlightComponent> flightType;
    private final Query<EntityStore> query;

    public AvatarFlightHotbarGuardSystem(
            @Nonnull ComponentType<EntityStore, AvatarFlightComponent> flightType) {
        super(InventorySetActiveSlotEvent.class);
        this.flightType = flightType;
        this.query = Query.and(
                flightType,
                InventoryComponent.Hotbar.getComponentType(),
                PlayerRef.getComponentType()
        );
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
                       @Nonnull InventorySetActiveSlotEvent event) {
        AvatarFlightComponent flight = chunk.getComponent(index, flightType);
        if (!shouldRestore(flight, event)) {
            return;
        }
        InventoryComponent.Hotbar hotbar = chunk.getComponent(
                index, InventoryComponent.Hotbar.getComponentType());
        PlayerRef playerRef = chunk.getComponent(index, PlayerRef.getComponentType());
        Ref<EntityStore> ref = chunk.getReferenceTo(index);
        byte lockedSlot = (byte) flight.getLockedHotbarSlot();
        if (hotbar == null || lockedSlot >= hotbar.getInventory().getCapacity()) {
            return;
        }
        hotbar.setActiveSlot(lockedSlot, ref, commandBuffer);
        if (playerRef != null) {
            playerRef.getPacketHandler().writeNoCache(
                    new SetActiveSlot(InventoryComponent.HOTBAR_SECTION_ID, lockedSlot));
        }
    }

    static boolean shouldRestore(@Nullable AvatarFlightComponent flight,
                                 @Nonnull InventorySetActiveSlotEvent event) {
        return flight != null
                && flight.getLockedHotbarSlot() >= 0
                && event.getInventorySectionId() == InventoryComponent.HOTBAR_SECTION_ID
                && event.getNewSlot() != flight.getLockedHotbarSlot();
    }
}
