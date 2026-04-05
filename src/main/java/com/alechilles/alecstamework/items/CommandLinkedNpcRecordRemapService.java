package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Rewrites linked-NPC record UUIDs inside command tools after entity identity remaps.
 */
final class CommandLinkedNpcRecordRemapService {
    private CommandLinkedNpcRecordRemapService() {
    }

    static boolean remapLinkedNpcRecordsInHotbar(@Nullable Player player,
                                                 @Nullable UUID oldNpcUuid,
                                                 @Nullable UUID newNpcUuid) {
        if (player == null || oldNpcUuid == null || newNpcUuid == null || oldNpcUuid.equals(newNpcUuid)) {
            return false;
        }
        Inventory inventory = player.getInventory();
        if (inventory == null) {
            return false;
        }
        ItemContainer hotbar = inventory.getHotbar();
        if (hotbar == null) {
            return false;
        }
        boolean changedAny = false;
        short capacity = hotbar.getCapacity();
        for (short slot = 0; slot < capacity; slot++) {
            ItemStack slotStack = hotbar.getItemStack(slot);
            if (slotStack == null || slotStack.isEmpty()) {
                continue;
            }
            String encodedLinks = slotStack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_LINKED_NPCS, Codec.STRING);
            if (encodedLinks == null || encodedLinks.isBlank()) {
                continue;
            }
            String rewritten = rewriteLinkedNpcUuidRecords(encodedLinks, oldNpcUuid, newNpcUuid);
            if (rewritten == null || rewritten.equals(encodedLinks)) {
                continue;
            }
            hotbar.setItemStackForSlot(
                    slot,
                    slotStack.withMetadata(TameworkMetadataKeys.COMMAND_LINKED_NPCS, Codec.STRING, rewritten)
            );
            changedAny = true;
        }
        return changedAny;
    }

    private static String rewriteLinkedNpcUuidRecords(String encodedLinks, UUID oldNpcUuid, UUID newNpcUuid) {
        if (encodedLinks == null || encodedLinks.isBlank() || oldNpcUuid == null || newNpcUuid == null) {
            return encodedLinks;
        }
        String oldKey = oldNpcUuid.toString();
        String newKey = newNpcUuid.toString();
        String[] lines = encodedLinks.split("\\R");
        LinkedHashMap<String, String> dedupedLines = new LinkedHashMap<>();
        Set<String> seenUuids = new HashSet<>();
        boolean changed = false;
        int rawCounter = 0;

        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String trimmed = line.trim();
            int separator = trimmed.indexOf('|');
            String prefix = separator >= 0 ? trimmed.substring(0, separator) : trimmed;
            String suffix = separator >= 0 ? trimmed.substring(separator) : "";

            String rewrittenPrefix = prefix;
            if (prefix.equalsIgnoreCase(oldKey)) {
                rewrittenPrefix = newKey;
                changed = true;
            }
            String rewritten = rewrittenPrefix + suffix;

            String dedupeKey;
            try {
                dedupeKey = UUID.fromString(rewrittenPrefix).toString().toLowerCase(Locale.ROOT);
                if (!seenUuids.add(dedupeKey)) {
                    changed = true;
                    continue;
                }
            } catch (IllegalArgumentException ignored) {
                dedupeKey = "__raw__" + (rawCounter++);
            }
            dedupedLines.putIfAbsent(dedupeKey, rewritten);
        }

        if (!changed) {
            return encodedLinks;
        }

        StringBuilder builder = new StringBuilder();
        for (String line : dedupedLines.values()) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(line);
        }
        return builder.toString();
    }
}
