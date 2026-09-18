package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.Nullable;

/** Converts the active generic command roster selection into a compact HUD status. */
final class CommandHotswapHudGroupStatusResolver {
    private static final String ALL_COLOR = "#c8d1db";
    private static final String CUSTOM_COLOR = "#c9a653";
    private static final String NONE_COLOR = "#6e7c8b";
    private static final CommandHotswapHudViewModel.GroupStatus ALL_STATUS =
            new CommandHotswapHudViewModel.GroupStatus(true, "All Companions", ALL_COLOR);
    private static final CommandHotswapHudViewModel.GroupStatus NONE_STATUS =
            new CommandHotswapHudViewModel.GroupStatus(true, "No Active Companions", NONE_COLOR);
    private static final CommandHotswapHudViewModel.GroupStatus CUSTOM_STATUS =
            new CommandHotswapHudViewModel.GroupStatus(true, "Custom Selection", CUSTOM_COLOR);

    CommandHotswapHudViewModel.GroupStatus resolve(@Nullable List<LinkedNpcEntry> entries,
                                                   @Nullable List<CommandGroupService.GroupRecord> groups) {
        return resolveSelection(resolveSelectionValue(entries, groups), groups);
    }

    /**
     * Resolves a status from the bounded selected-record set without constructing panel rows.
     * An incomplete owned-roster view intentionally reports Custom rather than All Companions.
     */
    CommandHotswapHudViewModel.GroupStatus resolveSelectedKeys(
            @Nullable Set<String> selectedKeys,
            @Nullable List<CommandGroupService.GroupRecord> groups,
            @Nullable Function<String, Set<String>> membersForGroup) {
        if (selectedKeys == null || selectedKeys.isEmpty()) {
            return NONE_STATUS;
        }
        if (groups != null && membersForGroup != null) {
            for (CommandGroupService.GroupRecord group : groups) {
                if (group == null || group.groupId == null || group.groupId.isBlank()) continue;
                Set<String> members = membersForGroup.apply(group.groupId);
                if (members != null && !members.isEmpty() && selectedKeys.equals(members)) {
                    return resolveSelection(group.groupId, groups);
                }
            }
        }
        return CUSTOM_STATUS;
    }

    CommandHotswapHudViewModel.GroupStatus customStatus() {
        return CUSTOM_STATUS;
    }

    /** Retains legacy per-item group status for owner-family command items. */
    CommandHotswapHudViewModel.GroupStatus resolveLegacy(@Nullable ItemStack stack) {
        CommandLinkedNpcRecordStore records = new CommandLinkedNpcRecordStore();
        CommandGroupService groups = new CommandGroupService();
        CommandGroupActivationService activation = new CommandGroupActivationService(records, groups);
        List<CommandGroupService.GroupRecord> definitions = groups.readGroups(stack);
        return resolveSelection(activation.resolveSelectionValue(records.read(stack), definitions), definitions);
    }

    private CommandHotswapHudViewModel.GroupStatus resolveSelection(
            String selection,
            @Nullable List<CommandGroupService.GroupRecord> groups) {
        if (CommandGroupActivationService.ALL_VALUE.equals(selection)) {
            return ALL_STATUS;
        }
        if (CommandGroupActivationService.NONE_VALUE.equals(selection)) {
            return NONE_STATUS;
        }
        if (CommandGroupActivationService.CUSTOM_VALUE.equals(selection)) {
            return CUSTOM_STATUS;
        }
        CommandGroupService.GroupRecord group = findGroup(groups, selection);
        if (group == null) {
            return CUSTOM_STATUS;
        }
        String label = group.name == null || group.name.isBlank() ? group.groupId : group.name.trim();
        return new CommandHotswapHudViewModel.GroupStatus(true, label, safeColor(group.colorHex));
    }

    private String resolveSelectionValue(@Nullable List<LinkedNpcEntry> entries,
                                         @Nullable List<CommandGroupService.GroupRecord> groups) {
        Set<String> selected = selected(entries);
        if (selected.isEmpty()) return CommandGroupActivationService.NONE_VALUE;
        Set<String> selectable = selectable(entries);
        if (!selectable.isEmpty() && selected.equals(selectable)) {
            return CommandGroupActivationService.ALL_VALUE;
        }
        if (groups != null) for (CommandGroupService.GroupRecord group : groups) {
            if (group != null && group.groupId != null && !group.groupId.isBlank()
                    && selected.equals(members(entries, group.groupId))
                    && !selected.isEmpty()) return group.groupId;
        }
        return CommandGroupActivationService.CUSTOM_VALUE;
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

    private Set<String> members(@Nullable List<LinkedNpcEntry> entries, String groupId) {
        Set<String> result = new HashSet<>();
        if (entries == null) return result;
        for (LinkedNpcEntry entry : entries) {
            if (entry != null && entry.selectionSupported() && entry.groupIds().contains(groupId)) result.add(key(entry));
        }
        return result;
    }

    private String key(LinkedNpcEntry entry) {
        return entry.companionKey() == null || entry.companionKey().isBlank()
                ? CommandCompanionGroups.entityKey(entry.npcUuid()) : entry.companionKey();
    }

    @Nullable
    private CommandGroupService.GroupRecord findGroup(
            @Nullable List<CommandGroupService.GroupRecord> groups,
            @Nullable String selection) {
        if (groups == null || selection == null || selection.isBlank()) {
            return null;
        }
        for (CommandGroupService.GroupRecord group : groups) {
            if (group != null && group.groupId != null
                    && group.groupId.trim().equalsIgnoreCase(selection.trim())) {
                return group;
            }
        }
        return null;
    }

    private String safeColor(@Nullable String colorHex) {
        if (colorHex == null || !colorHex.matches("#[0-9a-fA-F]{6}")) {
            return ALL_COLOR;
        }
        return colorHex;
    }
}
