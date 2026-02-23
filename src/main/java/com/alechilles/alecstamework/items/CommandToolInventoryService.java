package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.ui.TameworkCommandSelectionPage;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.UUID;

/**
 * Handles command-item hotbar lookups and metadata writes.
 *
 * <p>This isolates inventory-scanning and tool-id mutation concerns from command orchestration.
 */
final class CommandToolInventoryService {
    private final CommandLinkedPanelEntryService panelEntryService;

    CommandToolInventoryService(CommandLinkedPanelEntryService panelEntryService) {
        this.panelEntryService = panelEntryService;
    }

    ToolResolution ensureToolId(ItemStack itemStack) {
        String toolId = itemStack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_TOOL_ID, Codec.STRING);
        if (toolId != null && !toolId.isBlank()) {
            return new ToolResolution(itemStack, toolId, false);
        }
        String generated = UUID.randomUUID().toString();
        return new ToolResolution(
                itemStack.withMetadata(TameworkMetadataKeys.COMMAND_TOOL_ID, Codec.STRING, generated),
                generated,
                true
        );
    }

    boolean updateHeldItem(Player player, ItemStack updated) {
        Inventory inventory = player.getInventory();
        if (inventory == null) {
            return false;
        }
        ItemContainer hotbar = inventory.getHotbar();
        if (hotbar == null) {
            return false;
        }
        byte slot = inventory.getActiveHotbarSlot();
        if (slot == Inventory.INACTIVE_SLOT_INDEX) {
            return false;
        }
        hotbar.setItemStackForSlot((short) slot, updated);
        inventory.markChanged();
        player.sendInventory();
        return true;
    }

    boolean setSelectedCommandOnTool(Player player, String toolId, String commandId) {
        Inventory inventory = player != null ? player.getInventory() : null;
        if (inventory == null) {
            return false;
        }
        ItemContainer hotbar = inventory.getHotbar();
        if (hotbar == null) {
            return false;
        }
        short capacity = hotbar.getCapacity();
        for (short slot = 0; slot < capacity; slot++) {
            ItemStack stack = hotbar.getItemStack(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String stackToolId = stack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_TOOL_ID, Codec.STRING);
            if (stackToolId == null || !stackToolId.equals(toolId)) {
                continue;
            }
            ItemStack updated = stack.withMetadata(TameworkMetadataKeys.COMMAND_SELECTED_ID, Codec.STRING, commandId);
            hotbar.setItemStackForSlot(slot, updated);
            inventory.markChanged();
            player.sendInventory();
            return true;
        }
        return false;
    }

    List<TameworkCommandSelectionPage.LinkedNpcEntry> buildLinkedPanelEntriesForTool(Player player, String toolId) {
        if (player == null || toolId == null || toolId.isBlank()) {
            return List.of();
        }
        Inventory inventory = player.getInventory();
        if (inventory == null || inventory.getHotbar() == null) {
            return List.of();
        }
        ItemContainer hotbar = inventory.getHotbar();
        short capacity = hotbar.getCapacity();
        for (short slot = 0; slot < capacity; slot++) {
            ItemStack stack = hotbar.getItemStack(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String stackToolId = stack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_TOOL_ID, Codec.STRING);
            if (stackToolId == null || !stackToolId.equals(toolId)) {
                continue;
            }
            World world = player.getWorld();
            if (world == null) {
                return List.of();
            }
            Store<EntityStore> store = world.getEntityStore().getStore();
            if (store == null) {
                return List.of();
            }
            return panelEntryService.buildEntries(player, store, stack, toolId);
        }
        return List.of();
    }

    static final class ToolResolution {
        final ItemStack stack;
        final String toolId;
        final boolean changed;

        ToolResolution(ItemStack stack, String toolId, boolean changed) {
            this.stack = stack;
            this.toolId = toolId;
            this.changed = changed;
        }
    }
}
