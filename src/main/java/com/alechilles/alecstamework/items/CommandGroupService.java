package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads and normalizes command-panel group metadata persisted on command tools.
 */
final class CommandGroupService {
    private static final int MAX_GROUPS = 24;
    private static final int MAX_GROUP_ID_LENGTH = 32;
    private static final int MAX_GROUP_NAME_LENGTH = 24;
    private static final int MAX_METADATA_BYTES = 16 * 1024;
    private static final String DEFAULT_GROUP_NAME = "Group";
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
        return normalizeForRead(out);
    }

    ItemStack createGroup(ItemStack stack, String requestedName, String requestedColorHex) {
        if (stack == null || stack.isEmpty()) {
            return stack;
        }
        ArrayList<GroupRecord> groups = new ArrayList<>(readGroups(stack));
        if (groups.size() >= MAX_GROUPS) {
            return stack;
        }
        String normalizedName = normalizeName(requestedName);
        String groupId = buildUniqueGroupId(groups, normalizedName);
        if (groupId == null) {
            return stack;
        }
        int nextOrder = resolveNextDisplayOrder(groups);
        groups.add(new GroupRecord(
                groupId,
                normalizedName,
                normalizeColor(requestedColorHex),
                nextOrder
        ));
        return writeGroups(stack, groups);
    }

    ItemStack renameGroup(ItemStack stack, String groupId, String requestedName) {
        if (stack == null || stack.isEmpty()) {
            return stack;
        }
        String normalizedId = normalizeId(groupId);
        if (normalizedId == null) {
            return stack;
        }
        ArrayList<GroupRecord> groups = new ArrayList<>(readGroups(stack));
        boolean changed = false;
        for (int i = 0; i < groups.size(); i++) {
            GroupRecord group = groups.get(i);
            if (!group.matches(normalizedId)) {
                continue;
            }
            String nextName = normalizeName(requestedName);
            if (group.name.equals(nextName)) {
                return stack;
            }
            groups.set(i, group.withName(nextName));
            changed = true;
            break;
        }
        return changed ? writeGroups(stack, groups) : stack;
    }

    ItemStack recolorGroup(ItemStack stack, String groupId, String requestedColorHex) {
        if (stack == null || stack.isEmpty()) {
            return stack;
        }
        String normalizedId = normalizeId(groupId);
        if (normalizedId == null) {
            return stack;
        }
        ArrayList<GroupRecord> groups = new ArrayList<>(readGroups(stack));
        boolean changed = false;
        for (int i = 0; i < groups.size(); i++) {
            GroupRecord group = groups.get(i);
            if (!group.matches(normalizedId)) {
                continue;
            }
            String nextColor = normalizeColor(requestedColorHex);
            if (group.colorHex.equals(nextColor)) {
                return stack;
            }
            groups.set(i, group.withColor(nextColor));
            changed = true;
            break;
        }
        return changed ? writeGroups(stack, groups) : stack;
    }

    ItemStack deleteGroup(ItemStack stack, String groupId) {
        if (stack == null || stack.isEmpty()) {
            return stack;
        }
        String normalizedId = normalizeId(groupId);
        if (normalizedId == null) {
            return stack;
        }
        ArrayList<GroupRecord> groups = new ArrayList<>(readGroups(stack));
        int originalSize = groups.size();
        groups.removeIf(group -> group != null && group.matches(normalizedId));
        if (groups.size() == originalSize) {
            return stack;
        }
        return writeGroups(stack, groups);
    }

    ItemStack writeGroups(ItemStack stack, List<GroupRecord> groups) {
        if (stack == null || stack.isEmpty()) {
            return stack;
        }
        List<GroupRecord> normalized = normalizeForWrite(groups);
        if (normalized.isEmpty()) {
            return stack.withMetadata(TameworkMetadataKeys.COMMAND_GROUPS, Codec.STRING, "");
        }
        StringBuilder out = new StringBuilder();
        for (GroupRecord group : normalized) {
            if (group == null) {
                continue;
            }
            if (out.length() > 0) {
                out.append(RECORD_SEPARATOR);
            }
            out.append(group.groupId);
            out.append('|').append(encodeText(group.name));
            out.append('|').append(group.colorHex);
            out.append('|').append(group.displayOrder);
        }
        byte[] bytes = out.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_METADATA_BYTES) {
            return stack;
        }
        return stack.withMetadata(TameworkMetadataKeys.COMMAND_GROUPS, Codec.STRING, out.toString());
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

    String normalizeDisplayName(String value) {
        return normalizeName(value);
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
        String trimmed = value.trim();
        if (trimmed.length() > MAX_GROUP_ID_LENGTH) {
            return trimmed.substring(0, MAX_GROUP_ID_LENGTH);
        }
        return trimmed;
    }

    private String normalizeName(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_GROUP_NAME;
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

    private String encodeText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private List<GroupRecord> normalizeForRead(List<GroupRecord> groups) {
        if (groups == null || groups.isEmpty()) {
            return List.of();
        }
        ArrayList<GroupRecord> normalized = new ArrayList<>(Math.min(groups.size(), MAX_GROUPS));
        Set<String> seenIds = new HashSet<>();
        for (GroupRecord group : groups) {
            GroupRecord safe = sanitize(group);
            if (safe == null) {
                continue;
            }
            String idKey = normalizeKey(safe.groupId);
            if (!seenIds.add(idKey)) {
                continue;
            }
            normalized.add(safe);
            if (normalized.size() >= MAX_GROUPS) {
                break;
            }
        }
        normalized.sort(Comparator
                .comparingInt((GroupRecord value) -> value.displayOrder)
                .thenComparing(value -> value.name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(value -> value.groupId, String.CASE_INSENSITIVE_ORDER));
        return normalized;
    }

    private List<GroupRecord> normalizeForWrite(List<GroupRecord> groups) {
        List<GroupRecord> normalized = normalizeForRead(groups);
        if (normalized.isEmpty()) {
            return normalized;
        }
        ArrayList<GroupRecord> ordered = new ArrayList<>(normalized.size());
        int index = 0;
        for (GroupRecord group : normalized) {
            ordered.add(new GroupRecord(
                    group.groupId,
                    group.name,
                    group.colorHex,
                    index
            ));
            index++;
        }
        return ordered;
    }

    private GroupRecord sanitize(GroupRecord group) {
        if (group == null) {
            return null;
        }
        String id = normalizeId(group.groupId);
        if (id == null) {
            return null;
        }
        return new GroupRecord(
                id,
                normalizeName(group.name),
                normalizeColor(group.colorHex),
                Math.max(0, group.displayOrder)
        );
    }

    private int resolveNextDisplayOrder(List<GroupRecord> groups) {
        int max = -1;
        if (groups == null) {
            return 0;
        }
        for (GroupRecord group : groups) {
            if (group == null) {
                continue;
            }
            max = Math.max(max, group.displayOrder);
        }
        return max + 1;
    }

    private String buildUniqueGroupId(List<GroupRecord> groups, String requestedName) {
        String base = slugify(requestedName);
        if (base == null || base.isBlank()) {
            base = "group";
        }
        if (base.length() > MAX_GROUP_ID_LENGTH) {
            base = base.substring(0, MAX_GROUP_ID_LENGTH);
        }
        Set<String> existing = new HashSet<>();
        if (groups != null) {
            for (GroupRecord group : groups) {
                if (group == null || group.groupId == null) {
                    continue;
                }
                existing.add(normalizeKey(group.groupId));
            }
        }
        String candidate = base;
        int suffix = 2;
        while (existing.contains(normalizeKey(candidate))) {
            String suffixText = "-" + suffix;
            int maxBaseLength = Math.max(1, MAX_GROUP_ID_LENGTH - suffixText.length());
            String truncatedBase = base.length() > maxBaseLength ? base.substring(0, maxBaseLength) : base;
            candidate = truncatedBase + suffixText;
            suffix++;
            if (suffix > 9999) {
                return null;
            }
        }
        return candidate;
    }

    private String slugify(String input) {
        if (input == null || input.isBlank()) {
            return "group";
        }
        String raw = input.trim().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(raw.length());
        boolean prevDash = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (allowed) {
                out.append(c);
                prevDash = false;
                continue;
            }
            if (!prevDash && out.length() > 0) {
                out.append('-');
                prevDash = true;
            }
        }
        String slug = out.toString();
        while (slug.endsWith("-")) {
            slug = slug.substring(0, slug.length() - 1);
        }
        return slug.isBlank() ? "group" : slug;
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

        private boolean matches(String candidateId) {
            if (candidateId == null || candidateId.isBlank()) {
                return false;
            }
            return groupId != null && groupId.equalsIgnoreCase(candidateId.trim());
        }

        private GroupRecord withName(String nextName) {
            return new GroupRecord(groupId, nextName, colorHex, displayOrder);
        }

        private GroupRecord withColor(String nextColorHex) {
            return new GroupRecord(groupId, name, nextColorHex, displayOrder);
        }
    }
}
