package com.alechilles.alecstamework.ui;

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
        if (entry.lost()) {
            return "LOST";
        }
        if (entry.captured()) {
            return "CAPTURED";
        }
        return "UNLOADED";
    }

    static String resolveUnavailableHealthText(LinkedNpcEntry entry) {
        if (entry != null && entry.lost()) {
            return "Lost companion. Use Respawn to recover.";
        }
        if (entry != null && entry.captured()) {
            return "Captured in item.";
        }
        return "Unloaded (commands still queue).";
    }

    static String resolveDeadHappinessText(LinkedNpcEntry entry) {
        if (entry == null || !entry.dead()) {
            return "Happiness: unavailable";
        }
        return "Happiness: unavailable (dead).";
    }

    static String resolveUnavailableHappinessText(LinkedNpcEntry entry) {
        if (entry != null && entry.lost()) {
            return "Happiness: unavailable (lost).";
        }
        if (entry != null && entry.captured()) {
            return "Happiness: unavailable (captured).";
        }
        return "Happiness: unavailable (unloaded).";
    }

    static String resolveBreedingCooldownTooltip(LinkedNpcEntry entry) {
        if (entry == null || !entry.loaded()) {
            return "Breeding CD: unavailable";
        }
        if (!entry.breedingCooldownKnown()) {
            return "Breeding CD: unavailable";
        }
        if (!entry.breedingCooldownActive()) {
            return "Breeding CD: ready";
        }
        return "Breeding CD: " + formatRemainingClock(entry.breedingCooldownRemainingMs());
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

    private static String formatRemainingClock(long remainingMs) {
        long totalSeconds = Math.max(0L, (remainingMs + 999L) / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%d:%02d", minutes, seconds);
    }
}
