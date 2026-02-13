package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.util.UUID;

/**
 * Snapshot of frequently accessed interaction context data to avoid repeated lookups.
 */
final class InteractionContextSnapshot {
    final Player player;
    final Inventory inventory;
    final CombinedItemContainer combinedInventory;
    final ItemStack activeItem;
    final String activeItemId;
    final UUID playerId;
    final StdScope[] roleScopes;

    private InteractionContextSnapshot(Player player,
                                       Inventory inventory,
                                       CombinedItemContainer combinedInventory,
                                       ItemStack activeItem,
                                       String activeItemId,
                                       UUID playerId,
                                       StdScope[] roleScopes) {
        this.player = player;
        this.inventory = inventory;
        this.combinedInventory = combinedInventory;
        this.activeItem = activeItem;
        this.activeItemId = activeItemId;
        this.playerId = playerId;
        this.roleScopes = roleScopes;
    }

    static InteractionContextSnapshot from(Player player, StdScope[] roleScopes) {
        if (player == null) {
            return new InteractionContextSnapshot(null, null, null, null, null, null, roleScopes);
        }
        Inventory inventory = player.getInventory();
        CombinedItemContainer combined = inventory != null ? inventory.getCombinedBackpackStorageHotbar() : null;
        ItemStack active = inventory != null ? inventory.getActiveHotbarItem() : null;
        String activeId = (active != null && !active.isEmpty()) ? active.getItemId() : null;
        return new InteractionContextSnapshot(
                player,
                inventory,
                combined,
                active,
                activeId,
                player.getUuid(),
                roleScopes
        );
    }
}
