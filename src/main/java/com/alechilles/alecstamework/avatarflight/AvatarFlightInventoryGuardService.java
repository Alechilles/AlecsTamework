package com.alechilles.alecstamework.avatarflight;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Keeps avatar-flight inventory selection on the talisman and suppresses utility equipment. */
public final class AvatarFlightInventoryGuardService {
    static final int NO_SLOT_CAPTURED = -2;
    static final String TALISMAN_ITEM_ID = "Tamework_Flightmasters_Talisman";

    public boolean isTalismanSelected(@Nonnull Store<EntityStore> store,
                                      @Nonnull Ref<EntityStore> playerRef) {
        InventoryComponent.Hotbar hotbar = store.getComponent(
                playerRef, InventoryComponent.Hotbar.getComponentType());
        return hotbar != null && isTalismanSelected(
                hotbar.getInventory(), hotbar.getActiveSlot());
    }

    public void engage(@Nonnull Store<EntityStore> store,
                       @Nonnull Ref<EntityStore> playerRef,
                       @Nonnull AvatarFlightComponent flight) {
        InventoryComponent.Hotbar hotbar = store.getComponent(
                playerRef, InventoryComponent.Hotbar.getComponentType());
        byte talismanSlot = selectedTalismanSlot(
                hotbar == null ? null : hotbar.getInventory(),
                hotbar == null ? InventoryComponent.INACTIVE_SLOT_INDEX : hotbar.getActiveSlot());
        if (talismanSlot < 0) {
            talismanSlot = findTalismanSlot(hotbar == null ? null : hotbar.getInventory());
        }
        flight.setLockedHotbarSlot((int) talismanSlot);
        if (hotbar != null && talismanSlot >= 0 && hotbar.getActiveSlot() != talismanSlot) {
            hotbar.setActiveSlot(talismanSlot, playerRef, store);
        }
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

    static byte findTalismanSlot(@Nullable ItemContainer hotbar) {
        if (hotbar == null) {
            return InventoryComponent.INACTIVE_SLOT_INDEX;
        }
        for (short slot = 0; slot < hotbar.getCapacity(); slot++) {
            ItemStack stack = hotbar.getItemStack(slot);
            if (stack != null && !stack.isEmpty() && TALISMAN_ITEM_ID.equals(stack.getItemId())) {
                return (byte) slot;
            }
        }
        return InventoryComponent.INACTIVE_SLOT_INDEX;
    }

    static boolean isTalismanSelected(@Nullable ItemContainer hotbar, int activeSlot) {
        return selectedTalismanSlot(hotbar, activeSlot) >= 0;
    }

    private static byte selectedTalismanSlot(@Nullable ItemContainer hotbar, int activeSlot) {
        if (hotbar == null || activeSlot < 0 || activeSlot >= hotbar.getCapacity()) {
            return InventoryComponent.INACTIVE_SLOT_INDEX;
        }
        ItemStack stack = hotbar.getItemStack((short) activeSlot);
        return stack != null && !stack.isEmpty() && TALISMAN_ITEM_ID.equals(stack.getItemId())
                ? (byte) activeSlot
                : InventoryComponent.INACTIVE_SLOT_INDEX;
    }

    private static boolean validUtilitySlot(@Nonnull ItemContainer utility, int slot) {
        return slot >= InventoryComponent.INACTIVE_SLOT_INDEX && slot < utility.getCapacity();
    }
}
