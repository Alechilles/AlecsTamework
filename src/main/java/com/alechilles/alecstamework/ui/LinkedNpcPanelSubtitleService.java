package com.alechilles.alecstamework.ui;

import java.util.UUID;

/**
 * Formats subtitle text for linked NPC companion panel state.
 */
final class LinkedNpcPanelSubtitleService {
    private LinkedNpcPanelSubtitleService() {
    }

    static String resolveSubtitle(LinkedNpcEntry[] entries, UUID pendingUnlinkNpcUuid) {
        int total = entries != null ? entries.length : 0;
        if (pendingUnlinkNpcUuid != null) {
            String pendingName = resolvePendingUnlinkName(entries, pendingUnlinkNpcUuid);
            if (pendingName == null || pendingName.isBlank()) {
                pendingName = "this NPC";
            }
            return "Click X again to remove " + pendingName;
        }
        if (total <= 0) {
            return "No NPCs";
        }
        int linkedCount = 0;
        if (entries != null) {
            for (LinkedNpcEntry entry : entries) {
                if (entry != null && entry.linked()) {
                    linkedCount++;
                }
            }
        }
        if (linkedCount < total) {
            return total + " NPCs (" + linkedCount + " linked)";
        }
        return linkedCount + " linked NPC" + (linkedCount == 1 ? "" : "s");
    }

    static boolean containsEntry(LinkedNpcEntry[] entries, UUID npcUuid) {
        return resolvePendingUnlinkName(entries, npcUuid) != null;
    }

    private static String resolvePendingUnlinkName(LinkedNpcEntry[] entries, UUID pendingUuid) {
        if (entries == null || pendingUuid == null || entries.length == 0) {
            return null;
        }
        for (LinkedNpcEntry entry : entries) {
            if (entry == null || entry.npcUuid() == null) {
                continue;
            }
            if (pendingUuid.equals(entry.npcUuid())) {
                return entry.displayName();
            }
        }
        return null;
    }
}
