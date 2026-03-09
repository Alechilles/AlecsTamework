package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Handles command-item linked NPC record serialization and persistence.
 *
 * <p>This keeps string encoding/parsing concerns out of orchestration handlers so command flows
 * remain focused on gameplay behavior.
 */
final class CommandLinkedNpcRecordStore {
    private static final String LINK_RECORD_SEPARATOR = "\n";
    private static final String LINK_RECORD_PARTS_SEPARATOR = "\\|";
    private static final String TOKEN_DISPLAY_NAME = "dn=";
    private static final String TOKEN_NAME_KEY = "nk=";
    private static final String TOKEN_ROLE_ID = "rid=";
    private static final String TOKEN_ACTIVE = "ac=";
    private static final String TOKEN_GROUP_ID = "gid=";

    List<LinkedNpcRecord> read(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return List.of();
        }
        String encoded = stack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_LINKED_NPCS, Codec.STRING);
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        String[] lines = encoded.split(LINK_RECORD_SEPARATOR);
        ArrayList<LinkedNpcRecord> records = new ArrayList<>(lines.length);
        for (String line : lines) {
            LinkedNpcRecord record = parse(line);
            if (record == null || record.npcUuid == null) {
                continue;
            }
            records.add(record);
        }
        return records;
    }

    ItemStack write(ItemStack stack, List<LinkedNpcRecord> records) {
        if (stack == null || stack.isEmpty()) {
            return stack;
        }
        if (records == null || records.isEmpty()) {
            return stack.withMetadata(TameworkMetadataKeys.COMMAND_LINKED_NPCS, Codec.STRING, "");
        }
        StringBuilder builder = new StringBuilder();
        Set<UUID> seen = new HashSet<>();
        for (LinkedNpcRecord record : records) {
            if (record == null || record.npcUuid == null || seen.contains(record.npcUuid)) {
                continue;
            }
            seen.add(record.npcUuid);
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(record.npcUuid);
            Vector3d encodedLastKnown = record.lastKnownPosition != null
                    ? record.lastKnownPosition
                    : record.homePosition;
            if (encodedLastKnown != null) {
                builder.append('|').append(encodedLastKnown.x);
                builder.append('|').append(encodedLastKnown.y);
                builder.append('|').append(encodedLastKnown.z);
            }
            if (record.homePosition != null) {
                builder.append('|').append(record.homePosition.x);
                builder.append('|').append(record.homePosition.y);
                builder.append('|').append(record.homePosition.z);
            }
            if (record.cachedDisplayName != null && !record.cachedDisplayName.isBlank()) {
                builder.append('|').append(TOKEN_DISPLAY_NAME).append(encodeRecordText(record.cachedDisplayName));
            }
            if (record.cachedNameKey != null && !record.cachedNameKey.isBlank()) {
                builder.append('|').append(TOKEN_NAME_KEY).append(encodeRecordText(record.cachedNameKey));
            }
            if (record.cachedRoleId != null && !record.cachedRoleId.isBlank()) {
                builder.append('|').append(TOKEN_ROLE_ID).append(encodeRecordText(record.cachedRoleId));
            }
            if (!record.active) {
                builder.append('|').append(TOKEN_ACTIVE).append('0');
            }
            if (record.groupId != null && !record.groupId.isBlank()) {
                builder.append('|').append(TOKEN_GROUP_ID).append(encodeRecordText(record.groupId));
            }
        }
        return stack.withMetadata(TameworkMetadataKeys.COMMAND_LINKED_NPCS, Codec.STRING, builder.toString());
    }

    ItemStack upsert(ItemStack stack,
                     UUID npcUuid,
                     Vector3d position,
                     Vector3d homePosition,
                     String cachedDisplayName,
                     String cachedNameKey,
                     String cachedRoleId) {
        if (stack == null || stack.isEmpty() || npcUuid == null) {
            return stack;
        }
        List<LinkedNpcRecord> records = new ArrayList<>(read(stack));
        String key = npcUuid.toString().toLowerCase(Locale.ROOT);
        boolean updated = false;
        for (int i = 0; i < records.size(); i++) {
            LinkedNpcRecord record = records.get(i);
            if (record == null || record.npcUuid == null) {
                continue;
            }
            if (!key.equals(record.npcUuid.toString().toLowerCase(Locale.ROOT))) {
                continue;
            }
            Vector3d mergedLastKnown = position != null ? position : record.lastKnownPosition;
            Vector3d mergedHome = homePosition != null ? homePosition : record.homePosition;
            String mergedDisplayName = firstNonBlank(cachedDisplayName, record.cachedDisplayName);
            String mergedNameKey = firstNonBlank(cachedNameKey, record.cachedNameKey);
            String mergedRoleId = firstNonBlank(cachedRoleId, record.cachedRoleId);
            records.set(i, new LinkedNpcRecord(
                    npcUuid,
                    mergedLastKnown,
                    mergedHome,
                    mergedDisplayName,
                    mergedNameKey,
                    mergedRoleId,
                    record.active,
                    record.groupId
            ));
            updated = true;
            break;
        }
        if (!updated) {
            records.add(new LinkedNpcRecord(
                    npcUuid,
                    position,
                    homePosition,
                    firstNonBlank(cachedDisplayName, null),
                    firstNonBlank(cachedNameKey, null),
                    firstNonBlank(cachedRoleId, null),
                    true,
                    null
            ));
        }
        return write(stack, records);
    }

    ItemStack setActive(ItemStack stack, UUID npcUuid, boolean active) {
        if (stack == null || stack.isEmpty() || npcUuid == null) {
            return stack;
        }
        List<LinkedNpcRecord> records = new ArrayList<>(read(stack));
        boolean changed = false;
        String key = npcUuid.toString().toLowerCase(Locale.ROOT);
        for (int i = 0; i < records.size(); i++) {
            LinkedNpcRecord record = records.get(i);
            if (record == null || record.npcUuid == null) {
                continue;
            }
            if (!key.equals(record.npcUuid.toString().toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (record.active == active) {
                break;
            }
            records.set(i, new LinkedNpcRecord(
                    record.npcUuid,
                    record.lastKnownPosition,
                    record.homePosition,
                    record.cachedDisplayName,
                    record.cachedNameKey,
                    record.cachedRoleId,
                    active,
                    record.groupId
            ));
            changed = true;
            break;
        }
        return changed ? write(stack, records) : stack;
    }

    ItemStack setGroup(ItemStack stack, UUID npcUuid, String groupId) {
        if (stack == null || stack.isEmpty() || npcUuid == null) {
            return stack;
        }
        List<LinkedNpcRecord> records = new ArrayList<>(read(stack));
        boolean changed = false;
        String key = npcUuid.toString().toLowerCase(Locale.ROOT);
        for (int i = 0; i < records.size(); i++) {
            LinkedNpcRecord record = records.get(i);
            if (record == null || record.npcUuid == null) {
                continue;
            }
            if (!key.equals(record.npcUuid.toString().toLowerCase(Locale.ROOT))) {
                continue;
            }
            String normalizedGroupId = normalizeOptionalValue(groupId);
            if (equalsIgnoreCase(record.groupId, normalizedGroupId)) {
                break;
            }
            records.set(i, new LinkedNpcRecord(
                    record.npcUuid,
                    record.lastKnownPosition,
                    record.homePosition,
                    record.cachedDisplayName,
                    record.cachedNameKey,
                    record.cachedRoleId,
                    record.active,
                    normalizedGroupId
            ));
            changed = true;
            break;
        }
        return changed ? write(stack, records) : stack;
    }

    ItemStack remove(ItemStack stack, UUID npcUuid) {
        if (stack == null || stack.isEmpty() || npcUuid == null) {
            return stack;
        }
        List<LinkedNpcRecord> records = read(stack);
        if (records.isEmpty()) {
            return stack;
        }
        String key = npcUuid.toString().toLowerCase(Locale.ROOT);
        ArrayList<LinkedNpcRecord> filtered = new ArrayList<>(records.size());
        for (LinkedNpcRecord record : records) {
            if (record == null || record.npcUuid == null) {
                continue;
            }
            if (key.equals(record.npcUuid.toString().toLowerCase(Locale.ROOT))) {
                continue;
            }
            filtered.add(record);
        }
        return write(stack, filtered);
    }

    LinkedNpcRecord find(List<LinkedNpcRecord> records, UUID npcUuid) {
        if (records == null || records.isEmpty() || npcUuid == null) {
            return null;
        }
        String key = npcUuid.toString().toLowerCase(Locale.ROOT);
        for (LinkedNpcRecord record : records) {
            if (record == null || record.npcUuid == null) {
                continue;
            }
            if (key.equals(record.npcUuid.toString().toLowerCase(Locale.ROOT))) {
                return record;
            }
        }
        return null;
    }

    private LinkedNpcRecord parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.trim().split(LINK_RECORD_PARTS_SEPARATOR);
        if (parts.length == 0) {
            return null;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(parts[0].trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
        Vector3d position = null;
        Vector3d homePosition = null;
        String cachedDisplayName = null;
        String cachedNameKey = null;
        String cachedRoleId = null;
        boolean active = true;
        String groupId = null;
        int index = 1;
        if (parts.length >= 4) {
            try {
                position = new Vector3d(
                        Double.parseDouble(parts[1]),
                        Double.parseDouble(parts[2]),
                        Double.parseDouble(parts[3])
                );
                index = 4;
            } catch (NumberFormatException ignored) {
                position = null;
                index = 1;
            }
        }
        if (parts.length >= index + 3) {
            try {
                homePosition = new Vector3d(
                        Double.parseDouble(parts[index]),
                        Double.parseDouble(parts[index + 1]),
                        Double.parseDouble(parts[index + 2])
                );
                index += 3;
            } catch (NumberFormatException ignored) {
                homePosition = null;
            }
        }
        for (int i = index; i < parts.length; i++) {
            String token = parts[i];
            if (token == null || token.isBlank()) {
                continue;
            }
            if (token.startsWith(TOKEN_DISPLAY_NAME)) {
                cachedDisplayName = decodeRecordText(token.substring(TOKEN_DISPLAY_NAME.length()));
                continue;
            }
            if (token.startsWith(TOKEN_NAME_KEY)) {
                cachedNameKey = decodeRecordText(token.substring(TOKEN_NAME_KEY.length()));
                continue;
            }
            if (token.startsWith(TOKEN_ROLE_ID)) {
                cachedRoleId = decodeRecordText(token.substring(TOKEN_ROLE_ID.length()));
                continue;
            }
            if (token.startsWith(TOKEN_ACTIVE)) {
                String flag = token.substring(TOKEN_ACTIVE.length()).trim();
                active = !"0".equals(flag) && !"false".equalsIgnoreCase(flag);
                continue;
            }
            if (token.startsWith(TOKEN_GROUP_ID)) {
                groupId = decodeRecordText(token.substring(TOKEN_GROUP_ID.length()));
            }
        }
        return new LinkedNpcRecord(
                uuid,
                position,
                homePosition,
                cachedDisplayName,
                cachedNameKey,
                cachedRoleId,
                active,
                groupId
        );
    }

    private String encodeRecordText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeRecordText(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            String decoded = new String(bytes, StandardCharsets.UTF_8);
            return decoded.isBlank() ? null : decoded;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private String normalizeOptionalValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private boolean equalsIgnoreCase(String left, String right) {
        if (left == null && right == null) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.equalsIgnoreCase(right);
    }
}

