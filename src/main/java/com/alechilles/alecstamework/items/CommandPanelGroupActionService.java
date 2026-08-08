package com.alechilles.alecstamework.items;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;

/** Owns command-panel group CRUD and linked-companion assignment mutations. */
final class CommandPanelGroupActionService {
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
        String groupName = groupService.normalizeDisplayName(name);
        boolean updated = toolInventoryService.mutateToolStack(
                player, toolId, stack -> groupService.createGroup(stack, name, colorHex)
        );
        sendResult(player, updated,
                "tamework.ui.notifications.command.group.createFailed",
                "tamework.ui.notifications.command.group.created",
                groupName);
    }

    void applyRenameGroup(Player player, String toolId, String groupId, String name) {
        String groupName = groupService.normalizeDisplayName(name);
        boolean updated = toolInventoryService.mutateToolStack(
                player, toolId, stack -> groupService.renameGroup(stack, groupId, name)
        );
        sendResult(player, updated,
                "tamework.ui.notifications.command.group.renameFailed",
                "tamework.ui.notifications.command.group.renamed",
                groupName);
    }

    void applyRecolorGroup(Player player, String toolId, String groupId, String colorHex) {
        String[] groupName = { groupId };
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> {
                    CommandGroupService.GroupRecord group = groupService.findGroup(stack, groupId);
                    if (group != null) {
                        groupName[0] = group.name;
                    }
                    return groupService.recolorGroup(stack, groupId, colorHex);
                }
        );
        sendResult(player, updated,
                "tamework.ui.notifications.command.group.recolorFailed",
                "tamework.ui.notifications.command.group.recolored",
                groupName[0]);
    }

    void applyDeleteGroup(Player player, String toolId, String groupId) {
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> {
                    ItemStack updatedStack = groupService.deleteGroup(stack, groupId);
                    return updatedStack == stack ? stack : clearGroupAssignments(updatedStack, groupId);
                }
        );
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

    private void sendResult(Player player,
                            boolean updated,
                            String failureKey,
                            String successKey,
                            Object... successArgs) {
        if (player == null) {
            return;
        }
        if (updated) {
            feedbackService.showSuccessKey(player, successKey, successArgs);
        } else {
            feedbackService.showWarningKey(player, failureKey);
        }
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
