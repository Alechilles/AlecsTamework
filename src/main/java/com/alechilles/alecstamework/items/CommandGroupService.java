package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Reads and normalizes command-panel group metadata persisted on command tools.
 */
final class CommandGroupService {
    private static final int MAX_GROUPS = 24;
    private static final int MAX_GROUP_NAME_LENGTH = 24;
    private static final String RECORD_SEPARATOR = "\n";
    private static final String PART_SEPARATOR = "\\|";
    private static final String DEFAULT_COLOR = "#4b657f";

    List<GroupRecord> readGroups(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return List.of();
        }
        String raw = stack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_GROUPS, Codec.STRING);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String[] lines = raw.split(RECORD_SEPARATOR);
        ArrayList<GroupRecord> out = new ArrayList<>(Math.min(lines.length, MAX_GROUPS));
        for (String line : lines) {
            GroupRecord group = parse(line);
            if (group == null) {
                continue;
            }
            out.add(group);
            if (out.size() >= MAX_GROUPS) {
                break;
            }
        }
        return out;
    }

    GroupRecord findGroup(ItemStack stack, String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return null;
        }
        String key = normalizeKey(groupId);
        for (GroupRecord group : readGroups(stack)) {
            if (group == null || group.groupId == null) {
                continue;
            }
            if (normalizeKey(group.groupId).equals(key)) {
                return group;
            }
        }
        return null;
    }

    private GroupRecord parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.trim().split(PART_SEPARATOR, -1);
        if (parts.length < 1) {
            return null;
        }
        String id = normalizeId(parts[0]);
        if (id == null) {
            return null;
        }
        String name = parts.length > 1 ? normalizeName(decodeText(parts[1])) : id;
        String color = parts.length > 2 ? normalizeColor(parts[2]) : DEFAULT_COLOR;
        int displayOrder = parts.length > 3 ? parseOrder(parts[3]) : Integer.MAX_VALUE;
        return new GroupRecord(id, name, color, displayOrder);
    }

    private String normalizeId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String normalizeName(String value) {
        if (value == null || value.isBlank()) {
            return "Group";
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_GROUP_NAME_LENGTH) {
            return trimmed.substring(0, MAX_GROUP_NAME_LENGTH);
        }
        return trimmed;
    }

    private String normalizeColor(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_COLOR;
        }
        String trimmed = value.trim();
        if (!trimmed.matches("^#[0-9A-Fa-f]{6}$")) {
            return DEFAULT_COLOR;
        }
        return "#" + trimmed.substring(1).toUpperCase(Locale.ROOT);
    }

    private int parseOrder(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private String decodeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String normalizeKey(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    static final class GroupRecord {
        final String groupId;
        final String name;
        final String colorHex;
        final int displayOrder;

        GroupRecord(String groupId, String name, String colorHex, int displayOrder) {
            this.groupId = groupId;
            this.name = name;
            this.colorHex = colorHex;
            this.displayOrder = displayOrder;
        }
    }
}
