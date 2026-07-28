package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import java.util.List;
import java.util.UUID;

/**
 * Resolves group options and applies one linked-panel NPC group assignment.
 */
final class CommandGroupAssignPageService {
    private final CommandPanelActionService panelActionService;
    private final CommandToolInventoryService toolInventoryService;
    private final CommandGroupActivationService groupActivationService;

    CommandGroupAssignPageService(CommandPanelActionService panelActionService,
                                  CommandToolInventoryService toolInventoryService,
                                  CommandGroupActivationService groupActivationService) {
        this.panelActionService = panelActionService;
        this.toolInventoryService = toolInventoryService;
        this.groupActivationService = groupActivationService != null
                ? groupActivationService
                : new CommandGroupActivationService(null, null);
    }

    List<DropdownEntryInfo> resolveGroupDropdownEntries(Player player, String toolId) {
        if (toolInventoryService == null) {
            return List.of();
        }
        return toolInventoryService.resolveGroupDropdownEntriesForTool(player, toolId);
    }

    List<DropdownEntryInfo> resolveGroupActivationDropdownEntries(Player player, String toolId) {
        ItemStack stack = toolInventoryService != null ? toolInventoryService.findToolStack(player, toolId) : null;
        return groupActivationService.resolveDropdownEntries(stack, resolveLanguage(player));
    }

    String resolveGroupActivationValue(Player player, String toolId) {
        ItemStack stack = toolInventoryService != null ? toolInventoryService.findToolStack(player, toolId) : null;
        return groupActivationService.resolveSelectionValue(stack);
    }

    void applyGroupActivation(Player player,
                              String toolId,
                              TwCommandItemConfig config,
                              String selectorValue) {
        if (!CommandRosterStorageBoundary.allowsGenericRosterActions(config)
                || player == null || toolId == null || toolId.isBlank()
                || toolInventoryService == null) {
            return;
        }
        toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> groupActivationService.applySelection(stack, selectorValue)
        );
    }

    void applyGroupAssignment(Player player,
                              String toolId,
                              TwCommandItemConfig config,
                              UUID npcUuid,
                              String groupId) {
        if (!CommandRosterStorageBoundary.allowsGenericRosterActions(config)
                || player == null || toolId == null || toolId.isBlank()
                || npcUuid == null) {
            return;
        }
        if (panelActionService == null) {
            return;
        }
        if (groupId != null && !isNpcLinkedToTool(player, toolId, config, npcUuid)) {
            panelActionService.applyLink(player, toolId, config, npcUuid);
        }
        panelActionService.applySetLinkedNpcGroup(player, toolId, config, npcUuid, groupId);
    }

    private boolean isNpcLinkedToTool(Player player,
                                      String toolId,
                                      TwCommandItemConfig config,
                                      UUID npcUuid) {
        if (toolInventoryService == null || npcUuid == null) {
            return false;
        }
        List<LinkedNpcEntry> entries = toolInventoryService.buildLinkedPanelEntriesForTool(player, toolId, config);
        if (entries == null || entries.isEmpty()) {
            return false;
        }
        for (LinkedNpcEntry entry : entries) {
            if (entry == null || entry.npcUuid() == null) {
                continue;
            }
            if (entry.npcUuid().equals(npcUuid)) {
                return entry.linked();
            }
        }
        return false;
    }

    private String resolveLanguage(Player player) {
        return player != null && player.getPlayerRef() != null ? player.getPlayerRef().getLanguage() : null;
    }
}
