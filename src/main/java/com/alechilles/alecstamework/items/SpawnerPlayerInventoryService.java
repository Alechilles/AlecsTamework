package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;

/**
 * Encapsulates player hotbar updates and target-entity ref resolution for spawner flows.
 */
final class SpawnerPlayerInventoryService {

    Ref<EntityStore> resolveEntityRef(Player player, Integer entityId, UUID entityUuid) {
        if (player == null) {
            return null;
        }
        World world = player.getWorld();
        if (world == null) {
            return null;
        }
        if (entityUuid != null) {
            return world.getEntityRef(entityUuid);
        }
        if (entityId == null || entityId <= 0) {
            return null;
        }
        return world.getEntityStore().getRefFromNetworkId(entityId);
    }

    ItemStack getHotbarItem(Player player, int slot) {
        Inventory inventory = player.getInventory();
        if (inventory == null) {
            return null;
        }
        ItemContainer hotbar = inventory.getHotbar();
        if (hotbar == null) {
            return null;
        }
        return hotbar.getItemStack((short) slot);
    }

    boolean updateHeldItem(Player player, ItemStack updated) {
        if (player == null) {
            return false;
        }
        Inventory inventory = player.getInventory();
        if (inventory == null) {
            return false;
        }
        ItemContainer hotbar = inventory.getHotbar();
        if (hotbar == null) {
            return false;
        }
        byte activeSlot = inventory.getActiveHotbarSlot();
        hotbar.setItemStackForSlot((short) activeSlot, updated);
        inventory.markChanged();
        player.sendInventory();
        return true;
    }

    boolean updateHotbarSlot(Player player, int slot, ItemStack updated) {
        if (player == null || slot < 0) {
            return false;
        }
        Inventory inventory = player.getInventory();
        if (inventory == null) {
            return false;
        }
        ItemContainer hotbar = inventory.getHotbar();
        if (hotbar == null) {
            return false;
        }
        hotbar.setItemStackForSlot((short) slot, updated);
        inventory.markChanged();
        player.sendInventory();
        return true;
    }
}
