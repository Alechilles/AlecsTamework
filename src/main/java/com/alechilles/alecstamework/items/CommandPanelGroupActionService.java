package com.alechilles.alecstamework.items;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/** Owns command-panel group CRUD and linked-companion assignment mutations. */
final class CommandPanelGroupActionService {
    private static final Logger LOGGER = Logger.getLogger(CommandPanelGroupActionService.class.getName());
    private final CommandLinkMutationService linkMutationService;
    private final CommandToolInventoryService toolInventoryService;
    private final CommandFeedbackService feedbackService;
    private final CommandGroupService groupService;

    CommandPanelGroupActionService(CommandLinkMutationService linkMutationService,
                                   CommandToolInventoryService toolInventoryService,
                                   CommandFeedbackService feedbackService,
                                   CommandGroupService groupService) {
        this.linkMutationService = linkMutationService;
        this.toolInventoryService = toolInventoryService;
        this.feedbackService = feedbackService;
        this.groupService = groupService != null ? groupService : new CommandGroupService();
    }

    void applySetLinkedNpcGroup(Player player, String toolId, UUID npcUuid, String groupId) {
        if (player == null || toolId == null || toolId.isBlank() || npcUuid == null) {
            return;
        }
        String normalizedGroupId = normalizeOptionalGroupId(groupId);
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> {
                    if (normalizedGroupId != null && groupService.findGroup(stack, normalizedGroupId) == null) {
                        return stack;
                    }
                    return linkMutationService.setLinkedNpcGroup(stack, npcUuid, normalizedGroupId);
                }
        );
        if (!updated && normalizedGroupId != null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.group.assignFailed");
        }
    }

    void applyCreateGroup(Player player, String toolId, String name, String colorHex) {
        logRequest("create", toolId, name, colorHex);
        boolean updated = toolInventoryService.mutateToolStack(
                player, toolId, stack -> groupService.createGroup(stack, name, colorHex)
        );
        logResult("create", toolId, null, updated);
        sendResult(player, updated,
                "tamework.ui.notifications.command.group.createFailed",
                "tamework.ui.notifications.command.group.created");
    }

    void applyRenameGroup(Player player, String toolId, String groupId, String name) {
        logRequest("rename", toolId, groupId, name);
        boolean updated = toolInventoryService.mutateToolStack(
                player, toolId, stack -> groupService.renameGroup(stack, groupId, name)
        );
        logResult("rename", toolId, groupId, updated);
        sendResult(player, updated,
                "tamework.ui.notifications.command.group.renameFailed",
                "tamework.ui.notifications.command.group.renamed");
    }

    void applyRecolorGroup(Player player, String toolId, String groupId, String colorHex) {
        logRequest("recolor", toolId, groupId, colorHex);
        boolean updated = toolInventoryService.mutateToolStack(
                player, toolId, stack -> groupService.recolorGroup(stack, groupId, colorHex)
        );
        logResult("recolor", toolId, groupId, updated);
        sendResult(player, updated,
                "tamework.ui.notifications.command.group.recolorFailed",
                "tamework.ui.notifications.command.group.recolored");
    }

    void applyDeleteGroup(Player player, String toolId, String groupId) {
        logRequest("delete", toolId, groupId, null);
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> {
                    ItemStack updatedStack = groupService.deleteGroup(stack, groupId);
                    return updatedStack == stack ? stack : clearGroupAssignments(updatedStack, groupId);
                }
        );
        logResult("delete", toolId, groupId, updated);
        sendResult(player, updated,
                "tamework.ui.notifications.command.group.deleteFailed",
                "tamework.ui.notifications.command.group.deleted");
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
                updated.add(withoutGroup(record));
                changed = true;
            } else {
                updated.add(record);
            }
        }
        return changed ? linkMutationService.writeLinkedNpcRecords(stack, updated) : stack;
    }

    private static LinkedNpcRecord withoutGroup(LinkedNpcRecord record) {
        return new LinkedNpcRecord(
                record.npcUuid,
                record.lastKnownPosition,
                record.lastKnownWorldName,
                record.homePosition,
                record.cachedDisplayName,
                record.cachedNameKey,
                record.cachedRoleId,
                record.cachedCommandState,
                record.active,
                record.breedingEnabled,
                null
        );
    }

    private void sendResult(Player player, boolean updated, String failureKey, String successKey) {
        if (player == null) {
            return;
        }
        if (updated) {
            feedbackService.showSuccessKey(player, successKey);
        } else {
            feedbackService.showWarningKey(player, failureKey);
        }
    }

    private static void logRequest(String action, String toolId, String first, String second) {
        LOGGER.log(
                Level.INFO,
                "Group {0} mutation requested: toolId={1} value1={2} value2={3}",
                new Object[] { action, safeForLog(toolId), safeForLog(first), safeForLog(second) }
        );
    }

    private static void logResult(String action, String toolId, String groupId, boolean updated) {
        LOGGER.log(
                Level.INFO,
                "Group {0} mutation result: toolId={1} groupId={2} updated={3}",
                new Object[] { action, safeForLog(toolId), safeForLog(groupId), updated }
        );
    }

    private static String safeForLog(String value) {
        if (value == null) {
            return "<null>";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "<empty>";
        }
        return trimmed.length() <= 48 ? trimmed : trimmed.substring(0, 45) + "...";
    }

    @Nullable
    private static String normalizeOptionalGroupId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
