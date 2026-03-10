package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.alechilles.alecstamework.ui.TameworkCommandGroupAssignPage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.UUID;

/**
 * Opens the modal used to assign one linked-panel NPC to a command group.
 */
final class CommandGroupAssignPageService {
    private static final String GROUP_NONE_VALUE = "None";

    private final CommandPanelActionService panelActionService;
    private final CommandToolInventoryService toolInventoryService;
    private final CommandFeedbackService feedbackService;

    CommandGroupAssignPageService(CommandPanelActionService panelActionService,
                                  CommandToolInventoryService toolInventoryService,
                                  CommandFeedbackService feedbackService) {
        this.panelActionService = panelActionService;
        this.toolInventoryService = toolInventoryService;
        this.feedbackService = feedbackService;
    }

    void openGroupAssignPage(Player player,
                             String toolId,
                             TwCommandItemConfig config,
                             UUID npcUuid,
                             Runnable returnToSelectionCallback) {
        if (player == null || toolId == null || toolId.isBlank() || npcUuid == null) {
            return;
        }
        if (player.getPageManager() == null) {
            return;
        }
        World world = player.getWorld();
        if (world == null) {
            if (feedbackService != null) {
                feedbackService.showWarning(player, "Unable to open group assignment.");
            }
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        Ref<EntityStore> playerRef = player.getReference();
        PlayerRef uiPlayerRef = player.getPlayerRef();
        if (store == null || playerRef == null || !playerRef.isValid() || uiPlayerRef == null || !uiPlayerRef.isValid()) {
            if (feedbackService != null) {
                feedbackService.showWarning(player, "Unable to open group assignment.");
            }
            return;
        }
        LinkedNpcEntry targetEntry = resolveTargetEntry(player, toolId, config, npcUuid);
        if (targetEntry == null) {
            if (feedbackService != null) {
                feedbackService.showWarning(player, "Unable to find that NPC.");
            }
            if (returnToSelectionCallback != null) {
                returnToSelectionCallback.run();
            }
            return;
        }
        List<DropdownEntryInfo> dropdownEntries =
                toolInventoryService != null ? toolInventoryService.resolveGroupDropdownEntriesForTool(player, toolId) : List.of();
        String selectedGroupValue = normalizeSelectedGroupValue(targetEntry.groupId());
        TameworkCommandGroupAssignPage page = new TameworkCommandGroupAssignPage(
                uiPlayerRef,
                npcUuid,
                targetEntry.displayName(),
                dropdownEntries,
                selectedGroupValue,
                (targetNpcUuid, groupId) -> {
                    applyGroupAssignment(player, toolId, config, targetNpcUuid, groupId);
                    if (returnToSelectionCallback != null) {
                        returnToSelectionCallback.run();
                    }
                },
                () -> {
                    if (returnToSelectionCallback != null) {
                        returnToSelectionCallback.run();
                    }
                }
        );
        player.getPageManager().openCustomPage(playerRef, store, page);
    }

    private void applyGroupAssignment(Player player,
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

    private LinkedNpcEntry resolveTargetEntry(Player player,
                                              String toolId,
                                              TwCommandItemConfig config,
                                              UUID npcUuid) {
        if (toolInventoryService == null || npcUuid == null) {
            return null;
        }
        List<LinkedNpcEntry> entries = toolInventoryService.buildLinkedPanelEntriesForTool(player, toolId, config);
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        for (LinkedNpcEntry entry : entries) {
            if (entry == null || entry.npcUuid() == null) {
                continue;
            }
            if (entry.npcUuid().equals(npcUuid)) {
                return entry;
            }
        }
        return null;
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

    private String normalizeSelectedGroupValue(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return GROUP_NONE_VALUE;
        }
        String trimmed = groupId.trim();
        return trimmed.isBlank() ? GROUP_NONE_VALUE : trimmed;
    }
}
