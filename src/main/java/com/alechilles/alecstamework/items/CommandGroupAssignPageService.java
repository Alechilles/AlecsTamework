package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import java.util.List;
import java.util.UUID;

/**
 * Resolves group options and applies one linked-panel NPC group assignment.
 */
final class CommandGroupAssignPageService {
    private final CommandPanelActionService panelActionService;
    private final CommandToolInventoryService toolInventoryService;

    CommandGroupAssignPageService(CommandPanelActionService panelActionService,
                                  CommandToolInventoryService toolInventoryService) {
        this.panelActionService = panelActionService;
        this.toolInventoryService = toolInventoryService;
    }

    List<DropdownEntryInfo> resolveGroupDropdownEntries(Player player, String toolId) {
        if (toolInventoryService == null) {
            return List.of();
        }
        return toolInventoryService.resolveGroupDropdownEntriesForTool(player, toolId);
    }

    void applyGroupAssignment(Player player,
                              String toolId,
                              TwCommandItemConfig config,
                              UUID npcUuid,
                              String groupId) {
        if (player == null || config == null || toolId == null || toolId.isBlank() || npcUuid == null) {
            return;
        }
        if (panelActionService == null) {
            return;
        }
        if (groupId != null && !isNpcLinkedToTool(player, toolId, config, npcUuid)) {
            panelActionService.applyLink(player, toolId, config, npcUuid);
        }
        panelActionService.applySetLinkedNpcGroup(player, toolId, npcUuid, groupId);
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
}
