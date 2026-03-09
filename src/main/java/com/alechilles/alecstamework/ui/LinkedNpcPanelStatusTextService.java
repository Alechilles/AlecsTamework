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

    static String resolveDeadHappinessText(LinkedNpcEntry entry) {
        if (entry == null || !entry.dead()) {
            return "Happiness: unavailable";
        }
        return "Happiness: unavailable (dead).";
    }

    static String resolveUnavailableHappinessText(LinkedNpcEntry entry) {
        if (entry != null && entry.captured()) {
            return "Happiness: unavailable (captured).";
        }
        return "Happiness: unavailable (unloaded).";
    }

    static String resolveBreedingCooldownTooltip(LinkedNpcEntry entry) {
        if (entry == null || !entry.loaded()) {
            return "Breeding cooldown: unavailable";
        }
        if (!entry.breedingCooldownKnown()) {
            return "Breeding cooldown: unavailable";
        }
        if (!entry.breedingCooldownActive()) {
            return "Breeding cooldown: ready";
        }
        return "Breeding cooldown: " + formatRemainingHms(entry.breedingCooldownRemainingMs()) + " remaining";
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

    private static String formatRemainingHms(long remainingMs) {
        long totalSeconds = Math.max(0L, (remainingMs + 999L) / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        return String.format("%02dh %02dm %02ds", hours, minutes, seconds);
    }
}
