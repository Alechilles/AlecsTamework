package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.inventory.PlayerInventoryAccess;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;

/** Handles removing consumed items from a player's held slot. */
final class InteractionItemConsumption {
    // Utility container for item consumption helpers.
    private InteractionItemConsumption() {
    }

    // Removes a quantity of the currently held item from the player's hotbar.
    static boolean removeHeldItemQuantity(Player player, int quantity) {
        if (player == null) {
            return false;
        }
        if (quantity <= 0) {
            return false;
        }
        byte slot = PlayerInventoryAccess.getActiveHotbarSlot(player);
        if (slot < 0) {
            return false;
        }
        ItemContainer hotbar = PlayerInventoryAccess.getHotbar(player);
        ItemStack stack = hotbar != null ? hotbar.getItemStack(slot) : null;
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        int removeCount = Math.min(quantity, stack.getQuantity());
        if (removeCount <= 0) {
            return false;
        }
        hotbar.removeItemStackFromSlot((short) slot, removeCount);
        return true;
    }
}
