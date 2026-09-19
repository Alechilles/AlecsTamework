package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

/** Advances a command flute through its dispatchable active companion groups. */
final class CommandGroupCycleService {
    String nextSelectorValue(@Nullable List<LinkedNpcEntry> entries,
                             @Nullable List<CommandGroupService.GroupRecord> groups) {
        List<CommandGroupService.GroupRecord> eligible = eligibleGroups(entries, groups);
        if (eligible.isEmpty()) return CommandGroupActivationService.ALL_VALUE;
        String current = resolveSelectionValue(entries, eligible);
        if (CommandGroupActivationService.ALL_VALUE.equals(current)) return eligible.getFirst().groupId;
        for (int index = 0; index < eligible.size(); index++) {
            if (matches(eligible.get(index).groupId, current)) {
                return index + 1 < eligible.size()
                        ? eligible.get(index + 1).groupId
                        : CommandGroupActivationService.ALL_VALUE;
            }
        }
        return CommandGroupActivationService.ALL_VALUE;
    }

    private String resolveSelectionValue(@Nullable List<LinkedNpcEntry> entries,
                                         List<CommandGroupService.GroupRecord> eligible) {
        Set<String> selected = selected(entries);
        if (selected.isEmpty()) return CommandGroupActivationService.NONE_VALUE;
        Set<String> selectable = selectable(entries);
        if (!selectable.isEmpty() && selected.equals(selectable)) return CommandGroupActivationService.ALL_VALUE;
        for (CommandGroupService.GroupRecord group : eligible) {
            if (selected.equals(members(entries, group.groupId))) return group.groupId;
        }
        return CommandGroupActivationService.CUSTOM_VALUE;
    }

    private List<CommandGroupService.GroupRecord> eligibleGroups(
            @Nullable List<LinkedNpcEntry> entries,
            @Nullable List<CommandGroupService.GroupRecord> groups) {
        if (entries == null || entries.isEmpty() || groups == null || groups.isEmpty()) return List.of();
        ArrayList<CommandGroupService.GroupRecord> eligible = new ArrayList<>(groups.size());
        for (CommandGroupService.GroupRecord group : groups) {
            if (group == null || group.groupId == null || group.groupId.isBlank()
                    || members(entries, group.groupId).isEmpty()) {
                continue;
            }
            eligible.add(group);
        }
        return eligible;
    }

    private Set<String> selected(@Nullable List<LinkedNpcEntry> entries) {
        Set<String> result = new HashSet<>();
        if (entries == null) return result;
        for (LinkedNpcEntry entry : entries) {
            if (entry != null && entry.selectionSupported() && entry.active()) result.add(key(entry));
        }
        return result;
    }

    private Set<String> selectable(@Nullable List<LinkedNpcEntry> entries) {
        Set<String> result = new HashSet<>();
        if (entries == null) return result;
        for (LinkedNpcEntry entry : entries) {
            if (entry != null && entry.selectionSupported()) result.add(key(entry));
        }
        return result;
    }

    private Set<String> members(List<LinkedNpcEntry> entries, String groupId) {
        Set<String> result = new HashSet<>();
        for (LinkedNpcEntry entry : entries) {
            if (entry != null && entry.selectionSupported() && entry.groupIds().contains(groupId)) result.add(key(entry));
        }
        return result;
    }

    private String key(LinkedNpcEntry entry) {
        return entry.companionKey() == null || entry.companionKey().isBlank()
                ? CommandCompanionGroups.entityKey(entry.npcUuid()) : entry.companionKey();
    }

    private boolean matches(@Nullable String left, @Nullable String right) {
        return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
    }
}
