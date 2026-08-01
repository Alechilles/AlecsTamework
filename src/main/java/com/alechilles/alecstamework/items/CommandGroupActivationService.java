package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Applies linked-panel group selector choices to command-tool active flags.
 */
final class CommandGroupActivationService {
    static final String ALL_VALUE = "__all__";
    static final String NONE_VALUE = "__none__";

    private final CommandLinkedNpcRecordStore linkedNpcRecordStore;
    private final CommandGroupService groupService;
    /**
     * DropdownEntryInfo uses identity equality, so retain the last equivalent
     * presentation list instead of making an unchanged linked-panel safety
     * refresh appear dirty.
     */
    @Nullable
    private GroupDropdownCache dropdownCache;

    CommandGroupActivationService(CommandLinkedNpcRecordStore linkedNpcRecordStore,
                                  CommandGroupService groupService) {
        this.linkedNpcRecordStore = linkedNpcRecordStore != null
                ? linkedNpcRecordStore
                : new CommandLinkedNpcRecordStore();
        this.groupService = groupService != null ? groupService : new CommandGroupService();
    }

    List<DropdownEntryInfo> resolveDropdownEntries(@Nullable ItemStack stack, @Nullable String language) {
        List<GroupDropdownOption> options = resolveDropdownOptions(stack);
        GroupDropdownCache cached = dropdownCache;
        if (cached != null && cached.matches(language, options)) {
            return cached.entries();
        }
        ArrayList<DropdownEntryInfo> entries = new ArrayList<>();
        entries.add(new DropdownEntryInfo(
                LocalizableString.fromString(LocalizedText.resolve(language, "tamework.ui.linkedPanel.groupSelector.all")),
                ALL_VALUE
        ));
        entries.add(new DropdownEntryInfo(
                LocalizableString.fromString(LocalizedText.resolve(language, "tamework.ui.linkedPanel.groupSelector.none")),
                NONE_VALUE
        ));
        for (GroupDropdownOption option : options) {
            entries.add(new DropdownEntryInfo(LocalizableString.fromString(option.label()), option.value()));
        }
        List<DropdownEntryInfo> resolved = List.copyOf(entries);
        dropdownCache = new GroupDropdownCache(language, options, resolved);
        return resolved;
    }

    private List<GroupDropdownOption> resolveDropdownOptions(@Nullable ItemStack stack) {
        Set<String> seenValues = new HashSet<>();
        seenValues.add(ALL_VALUE.toLowerCase(Locale.ROOT));
        seenValues.add(NONE_VALUE.toLowerCase(Locale.ROOT));
        ArrayList<GroupDropdownOption> options = new ArrayList<>();
        for (CommandGroupService.GroupRecord group : groupService.readGroups(stack)) {
            if (group == null || group.groupId == null || group.groupId.isBlank()) {
                continue;
            }
            String value = group.groupId.trim();
            if (!seenValues.add(value.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String label = group.name != null && !group.name.isBlank() ? group.name.trim() : value;
            options.add(new GroupDropdownOption(value, label));
        }
        return List.copyOf(options);
    }

    private record GroupDropdownOption(String value, String label) { }

    private record GroupDropdownCache(@Nullable String language,
                                      List<GroupDropdownOption> options,
                                      List<DropdownEntryInfo> entries) {
        private boolean matches(@Nullable String candidateLanguage,
                                List<GroupDropdownOption> candidateOptions) {
            return java.util.Objects.equals(language, candidateLanguage)
                    && options.equals(candidateOptions);
        }
    }

    String resolveSelectionValue(@Nullable ItemStack stack) {
        return resolveSelectionValue(linkedNpcRecordStore.read(stack), groupService.readGroups(stack));
    }

    String resolveSelectionValue(@Nullable List<LinkedNpcRecord> records,
                                 @Nullable List<CommandGroupService.GroupRecord> groups) {
        if (records == null || records.isEmpty()) {
            return NONE_VALUE;
        }
        int activeCount = countActive(records);
        if (activeCount <= 0) {
            return NONE_VALUE;
        }
        if (activeCount == records.size()) {
            return ALL_VALUE;
        }
        String matchingGroupId = resolveExactActiveGroup(groups, records);
        return matchingGroupId != null ? matchingGroupId : ALL_VALUE;
    }

    ItemStack applySelection(@Nullable ItemStack stack, @Nullable String selectorValue) {
        List<LinkedNpcRecord> records = linkedNpcRecordStore.read(stack);
        if (stack == null || stack.isEmpty() || records.isEmpty()) {
            return stack;
        }
        List<LinkedNpcRecord> updated = applySelection(records, groupService.readGroups(stack), selectorValue);
        return updated == records ? stack : linkedNpcRecordStore.write(stack, updated);
    }

    List<LinkedNpcRecord> applySelection(@Nullable List<LinkedNpcRecord> records,
                                         @Nullable List<CommandGroupService.GroupRecord> groups,
                                         @Nullable String selectorValue) {
        String normalizedValue = normalizeSelectorValue(selectorValue);
        if (records == null || records.isEmpty() || normalizedValue == null) {
            return records;
        }
        String groupId = resolveTargetGroupId(groups, normalizedValue);
        if (!ALL_VALUE.equals(normalizedValue) && !NONE_VALUE.equals(normalizedValue) && groupId == null) {
            return records;
        }
        ArrayList<LinkedNpcRecord> updated = new ArrayList<>(records.size());
        boolean changed = false;
        for (LinkedNpcRecord record : records) {
            if (record == null) {
                continue;
            }
            boolean nextActive = shouldActivate(record, normalizedValue, groupId);
            updated.add(withActive(record, nextActive));
            changed |= record.active != nextActive;
        }
        return changed ? updated : records;
    }

    private String resolveExactActiveGroup(@Nullable List<CommandGroupService.GroupRecord> groups,
                                           List<LinkedNpcRecord> records) {
        if (groups == null || groups.isEmpty()) {
            return null;
        }
        for (CommandGroupService.GroupRecord group : groups) {
            if (group == null || group.groupId == null || group.groupId.isBlank()) {
                continue;
            }
            if (isExactActiveGroup(records, group.groupId)) {
                return group.groupId;
            }
        }
        return null;
    }

    private boolean isExactActiveGroup(List<LinkedNpcRecord> records, String groupId) {
        boolean hasGroupMember = false;
        for (LinkedNpcRecord record : records) {
            boolean member = groupMatches(record.groupId, groupId);
            hasGroupMember |= member;
            if (record.active != member) {
                return false;
            }
        }
        return hasGroupMember;
    }

    private int countActive(List<LinkedNpcRecord> records) {
        int count = 0;
        for (LinkedNpcRecord record : records) {
            if (record != null && record.active) {
                count++;
            }
        }
        return count;
    }

    private boolean shouldActivate(LinkedNpcRecord record, String selectorValue, @Nullable String groupId) {
        if (ALL_VALUE.equals(selectorValue)) {
            return true;
        }
        if (NONE_VALUE.equals(selectorValue)) {
            return false;
        }
        return groupMatches(record.groupId, groupId);
    }

    @Nullable
    private String resolveTargetGroupId(@Nullable List<CommandGroupService.GroupRecord> groups, String selectorValue) {
        if (ALL_VALUE.equals(selectorValue) || NONE_VALUE.equals(selectorValue)) {
            return null;
        }
        if (groups == null || groups.isEmpty()) {
            return null;
        }
        for (CommandGroupService.GroupRecord group : groups) {
            if (group != null && group.groupId != null && group.groupId.equalsIgnoreCase(selectorValue)) {
                return group.groupId;
            }
        }
        return null;
    }

    @Nullable
    private String normalizeSelectorValue(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (ALL_VALUE.equalsIgnoreCase(trimmed)) {
            return ALL_VALUE;
        }
        if (NONE_VALUE.equalsIgnoreCase(trimmed)) {
            return NONE_VALUE;
        }
        return trimmed;
    }

    private boolean groupMatches(@Nullable String recordGroupId, @Nullable String targetGroupId) {
        if (recordGroupId == null || targetGroupId == null) {
            return false;
        }
        return recordGroupId.trim().equalsIgnoreCase(targetGroupId.trim());
    }

    private LinkedNpcRecord withActive(LinkedNpcRecord record, boolean active) {
        return new LinkedNpcRecord(
                record.npcUuid,
                record.profileId,
                record.lastKnownPosition,
                record.lastKnownWorldName,
                record.homePosition,
                record.cachedDisplayName,
                record.cachedNameKey,
                record.cachedRoleId,
                record.cachedCommandState,
                active,
                record.breedingEnabled,
                record.groupId
        );
    }
}
