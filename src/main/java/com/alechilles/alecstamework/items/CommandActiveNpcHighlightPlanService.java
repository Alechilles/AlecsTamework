package com.alechilles.alecstamework.items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Builds the finite highlight emissions for active generic command links. */
final class CommandActiveNpcHighlightPlanService {
    private static final String UNGROUPED_COLOR = "#C9A653";

    @Nonnull
    List<HighlightTarget> build(@Nullable List<LinkedNpcRecord> records,
                                @Nullable List<CommandGroupService.GroupRecord> groups) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        Map<String, String> colorsByGroup = groupColors(groups);
        ArrayList<HighlightTarget> targets = new ArrayList<>();
        for (LinkedNpcRecord record : records) {
            if (record == null || !record.active || record.npcUuid == null) {
                continue;
            }
            targets.add(new HighlightTarget(
                    record.npcUuid,
                    record.profileId,
                    colorsByGroup.getOrDefault(normalize(record.groupId), UNGROUPED_COLOR)
            ));
        }
        return List.copyOf(targets);
    }

    @Nonnull
    private Map<String, String> groupColors(
            @Nullable List<CommandGroupService.GroupRecord> groups) {
        if (groups == null || groups.isEmpty()) {
            return Map.of();
        }
        HashMap<String, String> colors = new HashMap<>();
        for (CommandGroupService.GroupRecord group : groups) {
            if (group == null || group.groupId == null || group.groupId.isBlank()
                    || group.colorHex == null || group.colorHex.isBlank()) {
                continue;
            }
            colors.put(normalize(group.groupId), group.colorHex);
        }
        return colors;
    }

    @Nonnull
    private String normalize(@Nullable String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    record HighlightTarget(@Nonnull UUID npcUuid,
                           @Nullable String profileId,
                           @Nonnull String colorHex) {
    }
}
