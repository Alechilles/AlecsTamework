package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Holds the mutable item state for one already-validated command-item use.
 */
final class CommandPreparedUse {
    final Player player;
    final Ref<EntityStore> playerRef;
    final Store<EntityStore> store;
    final TwCommandItemConfig config;
    final String toolId;
    private final CommandToolInventoryService toolInventoryService;
    ItemStack workingItem;
    private boolean updateHeldItem;

    CommandPreparedUse(Player player,
                       Ref<EntityStore> playerRef,
                       Store<EntityStore> store,
                       TwCommandItemConfig config,
                       String toolId,
                       ItemStack workingItem,
                       boolean updateHeldItem,
                       CommandToolInventoryService toolInventoryService) {
        this.player = player;
        this.playerRef = playerRef;
        this.store = store;
        this.config = config;
        this.toolId = toolId;
        this.workingItem = workingItem;
        this.updateHeldItem = updateHeldItem;
        this.toolInventoryService = toolInventoryService;
    }

    void replaceWorkingItem(ItemStack updated) {
        workingItem = updated;
        updateHeldItem = true;
    }

    void acceptCommittedItem(ItemStack committed) {
        workingItem = committed;
        updateHeldItem = false;
    }

    void synchronizeFrom(Context context) {
        if (context.workingItem != workingItem) {
            replaceWorkingItem(context.workingItem);
        }
    }

    void flushHeldItem() {
        if (updateHeldItem) {
            toolInventoryService.updateHeldItem(player, workingItem);
        }
    }
}
