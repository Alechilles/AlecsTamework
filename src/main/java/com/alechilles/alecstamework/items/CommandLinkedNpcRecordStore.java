package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.hypixel.hytale.codec.Codec;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Handles command-item linked NPC record serialization and persistence.
 *
 * <p>This keeps string encoding/parsing concerns out of orchestration handlers so command flows
 * remain focused on gameplay behavior.
 */
final class CommandLinkedNpcRecordStore {
    private static final String LINK_RECORD_SEPARATOR = "\n";
    private final LinkedNpcRecordCodec codec = new LinkedNpcRecordCodec();
    private final LinkedNpcRecordCollection recordCollection = new LinkedNpcRecordCollection();

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
            LinkedNpcRecord record = codec.parse(line);
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
        for (LinkedNpcRecord record : recordCollection.deduplicate(records)) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(codec.encode(record));
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
        return upsert(
                stack,
                npcUuid,
                position,
                null,
                homePosition,
                cachedDisplayName,
                cachedNameKey,
                cachedRoleId,
                null,
                null
        );
    }

    ItemStack upsert(ItemStack stack,
                     UUID npcUuid,
                     Vector3d position,
                     Vector3d homePosition,
                     String cachedDisplayName,
                     String cachedNameKey,
                     String cachedRoleId,
                     Boolean activeOverride) {
        return upsert(
                stack,
                npcUuid,
                position,
                null,
                homePosition,
                cachedDisplayName,
                cachedNameKey,
                cachedRoleId,
                activeOverride,
                null
        );
    }

    ItemStack upsert(ItemStack stack,
                     UUID npcUuid,
                     Vector3d position,
                     Vector3d homePosition,
                     String cachedDisplayName,
                     String cachedNameKey,
                     String cachedRoleId,
                     Boolean activeOverride,
                     String cachedCommandState) {
        return upsert(
                stack,
                npcUuid,
                position,
                null,
                homePosition,
                cachedDisplayName,
                cachedNameKey,
                cachedRoleId,
                activeOverride,
                cachedCommandState
        );
    }

    ItemStack upsert(ItemStack stack,
                     UUID npcUuid,
                     Vector3d position,
                     String lastKnownWorldName,
                     Vector3d homePosition,
                     String cachedDisplayName,
                     String cachedNameKey,
                     String cachedRoleId,
                     Boolean activeOverride,
                     String cachedCommandState) {
        return upsert(
                stack,
                null,
                npcUuid,
                position,
                lastKnownWorldName,
                homePosition,
                cachedDisplayName,
                cachedNameKey,
                cachedRoleId,
                activeOverride,
                cachedCommandState
        );
    }

    ItemStack upsert(ItemStack stack,
                     String profileId,
                     UUID npcUuid,
                     Vector3d position,
                     String lastKnownWorldName,
                     Vector3d homePosition,
                     String cachedDisplayName,
                     String cachedNameKey,
                     String cachedRoleId,
                     Boolean activeOverride,
                     String cachedCommandState) {
        if (stack == null || stack.isEmpty() || npcUuid == null) {
            return stack;
        }
        return write(stack, recordCollection.upsert(
                read(stack), profileId, npcUuid, position, lastKnownWorldName, homePosition,
                cachedDisplayName, cachedNameKey, cachedRoleId, activeOverride, cachedCommandState
        ));
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
            ));
            changed = true;
            break;
        }
        return changed ? write(stack, records) : stack;
    }

    ItemStack setBreedingEnabled(ItemStack stack, UUID npcUuid, boolean breedingEnabled) {
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
            if (record.breedingEnabled == breedingEnabled) {
                break;
            }
            records.set(i, new LinkedNpcRecord(
                    record.npcUuid,
                    record.profileId,
                    record.lastKnownPosition,
                    record.lastKnownWorldName,
                    record.homePosition,
                    record.cachedDisplayName,
                    record.cachedNameKey,
                    record.cachedRoleId,
                    record.cachedCommandState,
                    record.active,
                    breedingEnabled,
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
                    record.profileId,
                    record.lastKnownPosition,
                    record.lastKnownWorldName,
                    record.homePosition,
                    record.cachedDisplayName,
                    record.cachedNameKey,
                    record.cachedRoleId,
                    record.cachedCommandState,
                    record.active,
                    record.breedingEnabled,
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
        List<LinkedNpcRecord> filtered = recordCollection.removeByUuid(records, npcUuid);
        return filtered.size() == records.size() ? stack : write(stack, filtered);
    }

    ItemStack remove(ItemStack stack, String profileId, UUID fallbackNpcUuid) {
        if (stack == null || stack.isEmpty()) {
            return stack;
        }
        if (LinkedNpcRecordCodec.normalizeProfileId(profileId) == null && fallbackNpcUuid == null) {
            return stack;
        }
        List<LinkedNpcRecord> records = read(stack);
        List<LinkedNpcRecord> filtered = recordCollection.removeByIdentity(records, profileId, fallbackNpcUuid);
        return filtered.size() == records.size() ? stack : write(stack, filtered);
    }

    LinkedNpcRecord find(List<LinkedNpcRecord> records, UUID npcUuid) {
        return recordCollection.findByUuid(records, npcUuid);
    }

    LinkedNpcRecord find(List<LinkedNpcRecord> records, String profileId, UUID fallbackNpcUuid) {
        return recordCollection.findByIdentity(records, profileId, fallbackNpcUuid);
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

