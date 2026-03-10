package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.alechilles.alecstamework.ui.LinkedNpcGroupPickerOption;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Handles command-item hotbar lookups and metadata writes.
 *
 * <p>This isolates inventory-scanning and tool-id mutation concerns from command orchestration.
 */
final class CommandToolInventoryService {
    private static final String GROUP_NONE_VALUE = "None";
    private static final String GROUP_NONE_COLOR = "#4B657F";

    private final CommandLinkedPanelEntryService panelEntryService;
    private final CommandPanelEntrySourceService panelEntrySourceService;
    private final CommandPanelPreferenceService panelPreferenceService;
    private final CommandGroupService groupService;

    CommandToolInventoryService(CommandLinkedPanelEntryService panelEntryService,
                                CommandPanelEntrySourceService panelEntrySourceService,
                                CommandPanelPreferenceService panelPreferenceService,
                                CommandGroupService groupService) {
        this.panelEntryService = panelEntryService;
        this.panelEntrySourceService = panelEntrySourceService;
        this.panelPreferenceService = panelPreferenceService != null
                ? panelPreferenceService
                : new CommandPanelPreferenceService();
        this.groupService = groupService != null ? groupService : new CommandGroupService();
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

    List<LinkedNpcEntry> buildLinkedPanelEntriesForTool(Player player, String toolId) {
        return buildLinkedPanelEntriesForTool(player, toolId, null);
    }

    List<LinkedNpcEntry> buildLinkedPanelEntriesForTool(Player player,
                                                        String toolId,
                                                        com.alechilles.alecstamework.config.assets.TwCommandItemConfig config) {
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
            if (panelEntrySourceService != null) {
                return panelEntrySourceService.buildEntries(player, store, stack, config, toolId);
            }
            return panelEntryService.buildEntries(player, store, stack, toolId);
        }
        return List.of();
    }

    boolean mutateToolStack(Player player, String toolId, UnaryOperator<ItemStack> mutator) {
        if (player == null || toolId == null || toolId.isBlank() || mutator == null) {
            return false;
        }
        Inventory inventory = player.getInventory();
        if (inventory == null || inventory.getHotbar() == null) {
            return false;
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
            ItemStack updated = mutator.apply(stack);
            if (updated == null || updated == stack) {
                return false;
            }
            hotbar.setItemStackForSlot(slot, updated);
            inventory.markChanged();
            player.sendInventory();
            return true;
        }
        return false;
    }

    String resolvePanelModeValueForTool(Player player,
                                        String toolId,
                                        com.alechilles.alecstamework.config.assets.TwCommandItemConfig config) {
        ItemStack stack = findToolStack(player, toolId);
        return panelPreferenceService.resolveModeValue(stack, config);
    }

    String resolvePanelRadiusLabelForTool(Player player,
                                          String toolId,
                                          com.alechilles.alecstamework.config.assets.TwCommandItemConfig config) {
        ItemStack stack = findToolStack(player, toolId);
        return panelPreferenceService.resolveRadiusLabel(stack, config);
    }

    String resolvePanelSortLabelForTool(Player player, String toolId) {
        ItemStack stack = findToolStack(player, toolId);
        return panelPreferenceService.resolveSortLabel(stack);
    }

    String resolvePanelSortValueForTool(Player player, String toolId) {
        ItemStack stack = findToolStack(player, toolId);
        return panelPreferenceService.resolveSortValue(stack);
    }

    String resolvePanelFilterSummaryForTool(Player player, String toolId) {
        ItemStack stack = findToolStack(player, toolId);
        return panelPreferenceService.resolveFilterSummaryLabel(stack);
    }

    String resolvePanelFilterModeValueForTool(Player player, String toolId) {
        ItemStack stack = findToolStack(player, toolId);
        return panelPreferenceService.resolveFilterModeValue(stack);
    }

    String resolvePanelFilterInputForTool(Player player, String toolId) {
        ItemStack stack = findToolStack(player, toolId);
        return panelPreferenceService.resolveSelectedFilterInput(stack);
    }

    List<DropdownEntryInfo> resolveGroupDropdownEntriesForTool(Player player, String toolId) {
        ArrayList<DropdownEntryInfo> entries = new ArrayList<>();
        entries.add(new DropdownEntryInfo(LocalizableString.fromString("None"), GROUP_NONE_VALUE));
        ItemStack stack = findToolStack(player, toolId);
        if (stack == null || stack.isEmpty()) {
            return entries;
        }
        List<CommandGroupService.GroupRecord> groups = groupService.readGroups(stack);
        if (groups == null || groups.isEmpty()) {
            return entries;
        }
        Set<String> seenValues = new HashSet<>();
        seenValues.add(GROUP_NONE_VALUE.toLowerCase(Locale.ROOT));
        for (CommandGroupService.GroupRecord group : groups) {
            if (group == null || group.groupId == null || group.groupId.isBlank()) {
                continue;
            }
            String value = group.groupId.trim();
            String key = value.toLowerCase(Locale.ROOT);
            if (!seenValues.add(key)) {
                continue;
            }
            String label = (group.name != null && !group.name.isBlank()) ? group.name.trim() : value;
            entries.add(new DropdownEntryInfo(LocalizableString.fromString(label), value));
        }
        return entries;
    }

    List<LinkedNpcGroupPickerOption> resolveGroupPickerOptionsForTool(Player player, String toolId) {
        ArrayList<LinkedNpcGroupPickerOption> options = new ArrayList<>();
        options.add(new LinkedNpcGroupPickerOption(GROUP_NONE_VALUE, "None", GROUP_NONE_COLOR));
        ItemStack stack = findToolStack(player, toolId);
        if (stack == null || stack.isEmpty()) {
            return options;
        }
        List<CommandGroupService.GroupRecord> groups = groupService.readGroups(stack);
        if (groups == null || groups.isEmpty()) {
            return options;
        }
        Set<String> seenValues = new HashSet<>();
        seenValues.add(GROUP_NONE_VALUE.toLowerCase(Locale.ROOT));
        for (CommandGroupService.GroupRecord group : groups) {
            if (group == null || group.groupId == null || group.groupId.isBlank()) {
                continue;
            }
            String value = group.groupId.trim();
            String key = value.toLowerCase(Locale.ROOT);
            if (!seenValues.add(key)) {
                continue;
            }
            String label = (group.name != null && !group.name.isBlank()) ? group.name.trim() : value;
            String color = (group.colorHex != null && !group.colorHex.isBlank())
                    ? group.colorHex.trim()
                    : GROUP_NONE_COLOR;
            options.add(new LinkedNpcGroupPickerOption(value, label, color));
        }
        return options;
    }

    private ItemStack findToolStack(Player player, String toolId) {
        if (player == null || toolId == null || toolId.isBlank()) {
            return null;
        }
        Inventory inventory = player.getInventory();
        if (inventory == null || inventory.getHotbar() == null) {
            return null;
        }
        ItemContainer hotbar = inventory.getHotbar();
        short capacity = hotbar.getCapacity();
        for (short slot = 0; slot < capacity; slot++) {
            ItemStack stack = hotbar.getItemStack(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String stackToolId = stack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_TOOL_ID, Codec.STRING);
            if (stackToolId != null && stackToolId.equals(toolId)) {
                return stack;
            }
        }
        return null;
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
