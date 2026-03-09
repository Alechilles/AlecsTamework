package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Handles linked panel row/header actions that mutate command tool state.
 */
final class CommandPanelActionService {
    private final CommandLinkMutationService linkMutationService;
    private final CommandToolInventoryService toolInventoryService;
    private final CommandPanelPreferenceService panelPreferenceService;
    private final CommandFeedbackService feedbackService;
    private final CommandGroupService groupService;

    CommandPanelActionService(CommandLinkMutationService linkMutationService,
                              CommandToolInventoryService toolInventoryService,
                              CommandPanelPreferenceService panelPreferenceService,
                              CommandFeedbackService feedbackService,
                              CommandGroupService groupService) {
        this.linkMutationService = linkMutationService;
        this.toolInventoryService = toolInventoryService;
        this.panelPreferenceService = panelPreferenceService;
        this.feedbackService = feedbackService;
        this.groupService = groupService != null ? groupService : new CommandGroupService();
    }

    void applyLink(Player player,
                   String toolId,
                   TwCommandItemConfig config,
                   UUID npcUuid) {
        if (player == null || toolId == null || toolId.isBlank() || config == null || npcUuid == null) {
            return;
        }
        World world = player.getWorld();
        if (world == null) {
            feedbackService.showWarning(player, "Unable to link right now.");
            return;
        }
        Ref<EntityStore> npcRef = world.getEntityRef(npcUuid);
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (npcRef == null || !npcRef.isValid() || store == null) {
            feedbackService.showWarning(player, "That companion must be loaded to link.");
            return;
        }
        LinkToggleResult[] resultHolder = new LinkToggleResult[1];
        toolInventoryService.mutateToolStack(player, toolId, stack -> {
            LinkToggleResult result = linkMutationService.tryToggleLink(
                    player,
                    store,
                    npcRef,
                    toolId,
                    config,
                    stack
            );
            resultHolder[0] = result;
            return result.updatedItem != null ? result.updatedItem : stack;
        });
        LinkToggleResult result = resultHolder[0];
        if (result == null || !result.toggled) {
            feedbackService.showWarning(player, "Unable to link that companion.");
            return;
        }
        if (!result.linked) {
            feedbackService.showWarning(player, "That companion is already linked.");
            return;
        }
        feedbackService.showSuccess(player, "Linked " + result.npcName + ".");
    }

    void applyToggleActive(Player player,
                           String toolId,
                           UUID npcUuid) {
        if (player == null || toolId == null || toolId.isBlank() || npcUuid == null) {
            return;
        }
        CommandLinkMutationService.ActiveToggleResult[] resultHolder =
                new CommandLinkMutationService.ActiveToggleResult[1];
        toolInventoryService.mutateToolStack(player, toolId, stack -> {
            CommandLinkMutationService.ActiveToggleResult result =
                    linkMutationService.toggleLinkedNpcActive(stack, npcUuid);
            resultHolder[0] = result;
            return result.updatedItem;
        });
        CommandLinkMutationService.ActiveToggleResult result = resultHolder[0];
        if (result == null || !result.toggled) {
            feedbackService.showWarning(player, "That NPC is not linked to this tool.");
            return;
        }
        feedbackService.showSuccess(player, result.active ? "Companion activated." : "Companion set inactive.");
    }

    void applyTogglePanelMode(Player player,
                              String toolId,
                              TwCommandItemConfig config) {
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> panelPreferenceService.togglePanelMode(stack, config)
        );
        if (!updated && player != null) {
            feedbackService.showWarning(player, "Unable to update panel mode.");
        }
    }

    void applyAdjustPanelRadius(Player player,
                                String toolId,
                                TwCommandItemConfig config,
                                boolean increase) {
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> panelPreferenceService.stepNearbyRadius(stack, config, increase)
        );
        if (!updated && player != null) {
            feedbackService.showWarning(player, "Unable to update panel radius.");
        }
    }

    void applyCycleSort(Player player, String toolId) {
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                panelPreferenceService::cycleSort
        );
        if (!updated && player != null) {
            feedbackService.showWarning(player, "Unable to update sort mode.");
        }
    }

    void applySetSort(Player player, String toolId, String sortValue) {
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> panelPreferenceService.setSort(stack, sortValue)
        );
        if (!updated && player != null) {
            feedbackService.showWarning(player, "Unable to update sort mode.");
        }
    }

    void applySetFilterMode(Player player, String toolId, String filterModeValue) {
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> panelPreferenceService.setFilterMode(stack, filterModeValue)
        );
        if (!updated && player != null) {
            feedbackService.showWarning(player, "Unable to update filter mode.");
        }
    }

    void applySetSelectedFilterText(Player player, String toolId, String value) {
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> panelPreferenceService.applySelectedFilterText(stack, value)
        );
        if (!updated && player != null) {
            feedbackService.showWarning(player, "Unable to update filter.");
        }
    }

    void applyClearFilters(Player player, String toolId) {
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                panelPreferenceService::clearFilters
        );
        if (!updated && player != null) {
            feedbackService.showWarning(player, "Unable to clear filters.");
        }
    }

    void applySetNameFilter(Player player, String toolId, String value) {
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> panelPreferenceService.setNameFilter(stack, value)
        );
        if (!updated && player != null) {
            feedbackService.showWarning(player, "Unable to update name filter.");
        }
    }

    void applySetSpeciesFilter(Player player, String toolId, String value) {
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> panelPreferenceService.setSpeciesFilter(stack, value)
        );
        if (!updated && player != null) {
            feedbackService.showWarning(player, "Unable to update species filter.");
        }
    }

    void applySetGroupFilter(Player player, String toolId, String value) {
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> panelPreferenceService.setGroupFilter(stack, value)
        );
        if (!updated && player != null) {
            feedbackService.showWarning(player, "Unable to update group filter.");
        }
    }

    void applyCreateGroup(Player player, String toolId, String name, String colorHex) {
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> groupService.createGroup(stack, name, colorHex)
        );
        if (!updated && player != null) {
            feedbackService.showWarning(player, "Unable to create group.");
            return;
        }
        if (player != null) {
            feedbackService.showSuccess(player, "Group created.");
        }
    }

    void applyRenameGroup(Player player, String toolId, String groupId, String name) {
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> groupService.renameGroup(stack, groupId, name)
        );
        if (!updated && player != null) {
            feedbackService.showWarning(player, "Unable to rename group.");
            return;
        }
        if (player != null) {
            feedbackService.showSuccess(player, "Group renamed.");
        }
    }

    void applyRecolorGroup(Player player, String toolId, String groupId, String colorHex) {
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> groupService.recolorGroup(stack, groupId, colorHex)
        );
        if (!updated && player != null) {
            feedbackService.showWarning(player, "Unable to update group color.");
            return;
        }
        if (player != null) {
            feedbackService.showSuccess(player, "Group color updated.");
        }
    }

    void applyDeleteGroup(Player player, String toolId, String groupId) {
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> {
                    ItemStack updatedStack = groupService.deleteGroup(stack, groupId);
                    if (updatedStack == stack) {
                        return stack;
                    }
                    return clearGroupAssignments(updatedStack, groupId);
                }
        );
        if (!updated && player != null) {
            feedbackService.showWarning(player, "Unable to delete group.");
            return;
        }
        if (player != null) {
            feedbackService.showSuccess(player, "Group deleted.");
        }
    }

    private ItemStack clearGroupAssignments(ItemStack stack, String groupId) {
        if (stack == null || stack.isEmpty() || groupId == null || groupId.isBlank()) {
            return stack;
        }
        List<LinkedNpcRecord> records = linkMutationService.readLinkedNpcRecords(stack);
        if (records.isEmpty()) {
            return stack;
        }
        ArrayList<LinkedNpcRecord> updated = new ArrayList<>(records.size());
        boolean changed = false;
        for (LinkedNpcRecord record : records) {
            if (record == null || record.npcUuid == null) {
                continue;
            }
            if (record.groupId != null && record.groupId.equalsIgnoreCase(groupId.trim())) {
                updated.add(new LinkedNpcRecord(
                        record.npcUuid,
                        record.lastKnownPosition,
                        record.homePosition,
                        record.cachedDisplayName,
                        record.cachedNameKey,
                        record.cachedRoleId,
                        record.active,
                        null
                ));
                changed = true;
                continue;
            }
            updated.add(record);
        }
        if (!changed) {
            return stack;
        }
        return linkMutationService.writeLinkedNpcRecords(stack, updated);
    }
}
