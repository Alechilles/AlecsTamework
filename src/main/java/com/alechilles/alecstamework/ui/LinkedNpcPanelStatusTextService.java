package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.ui.TameworkCommandSelectionPage.LinkedNpcEntry;

/**
 * Formats linked NPC panel status and health text labels.
 */
final class LinkedNpcPanelStatusTextService {
    private LinkedNpcPanelStatusTextService() {
    }

    static String resolveDeadHealthText(LinkedNpcEntry entry) {
        if (entry == null || !entry.dead()) {
            return "Dead";
        }
        if (entry.deadRespawnRemainingMs() < 0L) {
            return "Dead: respawn disabled.";
        }
        long remainingMs = Math.max(0L, entry.deadRespawnRemainingMs());
        if (remainingMs <= 0L) {
            return "Dead: ready to respawn.";
        }
        return "Dead: respawn in " + formatRemainingTime(remainingMs) + ".";
    }

    static String resolveAvailabilityStatusText(LinkedNpcEntry entry) {
        if (entry == null) {
            return "UNLOADED";
        }
        if (entry.dead()) {
            return "DEAD";
        }
        if (entry.captured()) {
            return "CAPTURED";
        }
        return "UNLOADED";
    }

    static String resolveUnavailableHealthText(LinkedNpcEntry entry) {
        if (entry != null && entry.captured()) {
            return "Captured in item.";
        }
        return "Unloaded (commands still queue).";
    }

    private static String formatRemainingTime(long remainingMs) {
        long totalSeconds = Math.max(0L, (remainingMs + 999L) / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        if (minutes <= 0L) {
            return seconds + "s";
        }
        return minutes + "m " + seconds + "s";
    }
}
