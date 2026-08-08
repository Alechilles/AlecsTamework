package com.alechilles.alecstamework.items;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/** Advances a command flute through its dispatchable active companion groups. */
final class CommandGroupCycleService {
    private final CommandLinkedNpcRecordStore linkedNpcRecordStore;
    private final CommandGroupService groupService;
    private final CommandGroupActivationService activationService;

    CommandGroupCycleService(@Nullable CommandLinkedNpcRecordStore linkedNpcRecordStore,
                             @Nullable CommandGroupService groupService,
                             @Nullable CommandGroupActivationService activationService) {
        this.linkedNpcRecordStore = linkedNpcRecordStore != null
                ? linkedNpcRecordStore : new CommandLinkedNpcRecordStore();
        this.groupService = groupService != null ? groupService : new CommandGroupService();
        this.activationService = activationService != null
                ? activationService : new CommandGroupActivationService(
                        this.linkedNpcRecordStore, this.groupService);
    }

    ItemStack applyNext(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return stack;
        }
        List<LinkedNpcRecord> records = linkedNpcRecordStore.read(stack);
        String next = nextSelectorValue(records, groupService.readGroups(stack));
        return activationService.applySelection(stack, next);
    }

    String nextSelectorValue(@Nullable List<LinkedNpcRecord> records,
                             @Nullable List<CommandGroupService.GroupRecord> groups) {
        List<CommandGroupService.GroupRecord> eligibleGroups = eligibleGroups(records, groups);
        if (eligibleGroups.isEmpty()) {
            return CommandGroupActivationService.ALL_VALUE;
        }
        String current = activationService.resolveSelectionValue(records, groups);
        if (CommandGroupActivationService.ALL_VALUE.equals(current)) {
            return eligibleGroups.getFirst().groupId;
        }
        for (int index = 0; index < eligibleGroups.size(); index++) {
            CommandGroupService.GroupRecord group = eligibleGroups.get(index);
            if (groupIdMatches(group.groupId, current)) {
                return index + 1 < eligibleGroups.size()
                        ? eligibleGroups.get(index + 1).groupId
                        : CommandGroupActivationService.ALL_VALUE;
            }
        }
        return CommandGroupActivationService.ALL_VALUE;
    }

    private List<CommandGroupService.GroupRecord> eligibleGroups(
            @Nullable List<LinkedNpcRecord> records,
            @Nullable List<CommandGroupService.GroupRecord> groups) {
        if (records == null || records.isEmpty() || groups == null || groups.isEmpty()) {
            return List.of();
        }
        ArrayList<CommandGroupService.GroupRecord> eligible = new ArrayList<>(groups.size());
        for (CommandGroupService.GroupRecord group : groups) {
            if (group == null || group.groupId == null || group.groupId.isBlank()
                    || !hasMember(records, group.groupId)) {
                continue;
            }
            eligible.add(group);
        }
        return eligible;
    }

    private boolean hasMember(List<LinkedNpcRecord> records, String groupId) {
        for (LinkedNpcRecord record : records) {
            if (record != null && record.groupId != null
                    && record.groupId.trim().equalsIgnoreCase(groupId.trim())) {
                return true;
            }
        }
        return false;
    }

    private boolean groupIdMatches(@Nullable String left, @Nullable String right) {
        return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
    }
}
