package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.localization.LocalizedText;
import java.util.UUID;

/**
 * Formats subtitle text for linked NPC companion panel state.
 */
final class LinkedNpcPanelSubtitleService {
    private LinkedNpcPanelSubtitleService() {
    }

    static String resolveSubtitle(LinkedNpcEntry[] entries, UUID pendingUnlinkNpcUuid) {
        return resolveSubtitle(entries, pendingUnlinkNpcUuid, null);
    }

    static String resolveSubtitle(LinkedNpcEntry[] entries, UUID pendingUnlinkNpcUuid, String language) {
        int total = entries != null ? entries.length : 0;
        if (pendingUnlinkNpcUuid != null) {
            LinkedNpcEntry pendingEntry = resolvePendingEntry(entries, pendingUnlinkNpcUuid);
            String pendingName = pendingEntry != null ? pendingEntry.displayName() : null;
            if (pendingName == null || pendingName.isBlank()) {
                pendingName = LocalizedText.resolve(language, "tamework.ui.linkedPanel.subtitle.defaultNpcName");
            }
            if (pendingEntry != null && !pendingEntry.linked()) {
                return LocalizedText.format(language, "tamework.ui.linkedPanel.subtitle.releaseOrCull", pendingName);
            }
            return LocalizedText.format(language, "tamework.ui.linkedPanel.subtitle.confirmRemove", pendingName);
        }
        if (total <= 0) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.subtitle.none");
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
            return LocalizedText.format(language, "tamework.ui.linkedPanel.subtitle.mixedCount", total, linkedCount);
        }
        return linkedCount == 1
                ? LocalizedText.format(language, "tamework.ui.linkedPanel.subtitle.linkedSingle", linkedCount)
                : LocalizedText.format(language, "tamework.ui.linkedPanel.subtitle.linkedPlural", linkedCount);
    }

    static boolean containsEntry(LinkedNpcEntry[] entries, UUID npcUuid) {
        return resolvePendingEntry(entries, npcUuid) != null;
    }

    private static LinkedNpcEntry resolvePendingEntry(LinkedNpcEntry[] entries, UUID pendingUuid) {
        if (entries == null || pendingUuid == null || entries.length == 0) {
            return null;
        }
        for (LinkedNpcEntry entry : entries) {
            if (entry == null || entry.npcUuid() == null) {
                continue;
            }
            if (pendingUuid.equals(entry.npcUuid())) {
                return entry;
            }
        }
        return null;
    }
}
