package com.alechilles.alecstamework.avatarflight;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Keeps the hotbar on the slot selected when flight began and suppresses tools and utility
 * equipment. Flight's controls are entity bindings, so the held item itself does nothing.
 */
public final class AvatarFlightInventoryGuardService {
    static final int NO_SLOT_CAPTURED = -2;

    public void engage(@Nonnull Store<EntityStore> store,
                       @Nonnull Ref<EntityStore> playerRef,
                       @Nonnull AvatarFlightComponent flight) {
        InventoryComponent.Hotbar hotbar = store.getComponent(
                playerRef, InventoryComponent.Hotbar.getComponentType());
        flight.setLockedHotbarSlot((int) (hotbar == null ? InventoryComponent.INACTIVE_SLOT_INDEX : hotbar.getActiveSlot()));
        InventoryComponent.Tool tools = store.getComponent(
                playerRef, InventoryComponent.Tool.getComponentType());
        if (tools != null) {
            tools.setUsingToolsItem(false);
        }

        InventoryComponent.Utility utility = store.getComponent(
                playerRef, InventoryComponent.Utility.getComponentType());
        if (utility == null) {
            return;
        }
        flight.setPreviousUtilitySlot((int) utility.getActiveSlot());
        if (utility.getActiveSlot() != InventoryComponent.INACTIVE_SLOT_INDEX) {
            utility.setActiveSlot(InventoryComponent.INACTIVE_SLOT_INDEX, playerRef, store);
        }
    }

    public void disengage(@Nonnull Store<EntityStore> store,
                          @Nonnull Ref<EntityStore> playerRef,
                          @Nullable AvatarFlightComponent flight) {
        if (flight == null) {
            return;
        }
        InventoryComponent.Utility utility = store.getComponent(
                playerRef, InventoryComponent.Utility.getComponentType());
        int previousSlot = flight.getPreviousUtilitySlot();
        if (utility != null && validUtilitySlot(utility.getInventory(), previousSlot)
                && utility.getActiveSlot() != previousSlot) {
            utility.setActiveSlot((byte) previousSlot, playerRef, store);
        }
    }

    private static boolean validUtilitySlot(@Nonnull ItemContainer utility, int slot) {
        return slot >= InventoryComponent.INACTIVE_SLOT_INDEX && slot < utility.getCapacity();
    }
}
