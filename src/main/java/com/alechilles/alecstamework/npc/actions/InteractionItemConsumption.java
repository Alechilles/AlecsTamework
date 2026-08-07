package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.inventory.PlayerInventoryAccess;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;

/** Handles removing consumed items from a player's held slot. */
final class InteractionItemConsumption {
    // Utility container for item consumption helpers.
    private InteractionItemConsumption() {
    }

    // Removes a quantity of the currently held item from the player's hotbar.
    static boolean removeHeldItemQuantity(Player player, int quantity) {
        ItemStack active = PlayerInventoryAccess.getActiveHotbarItem(player);
        String expectedItemId = active != null && !active.isEmpty() ? active.getItemId() : null;
        return removeHeldItemQuantity(player, expectedItemId, quantity);
    }

    // Removes a quantity only when the live active item still matches the interaction input.
    static boolean removeHeldItemQuantity(Player player, String expectedItemId, int quantity) {
        return PlayerInventoryAccess.removeActiveHotbarItem(player, expectedItemId, quantity);
    }
}
