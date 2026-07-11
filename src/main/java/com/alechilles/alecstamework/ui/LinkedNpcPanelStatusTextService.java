package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.localization.LocalizedText;

/**
 * Formats linked NPC panel status and health text labels.
 */
final class LinkedNpcPanelStatusTextService {
    private LinkedNpcPanelStatusTextService() {
    }

    static String resolveDeadHealthText(LinkedNpcEntry entry) {
        return resolveDeadHealthText(entry, null);
    }

    static String resolveDeadHealthText(LinkedNpcEntry entry, String language) {
        if (entry == null || !entry.dead()) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.health.dead");
        }
        if (entry.deadRespawnRemainingMs() < 0L) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.health.deadRespawnDisabled");
        }
        long remainingMs = Math.max(0L, entry.deadRespawnRemainingMs());
        if (remainingMs <= 0L) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.health.deadReadyToRespawn");
        }
        return LocalizedText.format(
                language,
                "tamework.ui.linkedPanel.health.deadRespawnIn",
                formatRemainingTime(remainingMs, language)
        );
    }

    static String resolveDeadHealthTooltip(LinkedNpcEntry entry) {
        return resolveDeadHealthTooltip(entry, null);
    }

    static String resolveDeadHealthTooltip(LinkedNpcEntry entry, String language) {
        String primary = resolveDeadHealthText(entry, language);
        if (entry == null || entry.deathCauseHint() == null || entry.deathCauseHint().isBlank()) {
            return primary;
        }
        return primary + "\n" + entry.deathCauseHint();
    }

    static String resolveAvailabilityStatusText(LinkedNpcEntry entry) {
        return resolveAvailabilityStatusText(entry, null);
    }

    static String resolveAvailabilityStatusText(LinkedNpcEntry entry, String language) {
        if (entry == null) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.status.unloaded");
        }
        if (entry.dead()) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.status.dead");
        }
        if (entry.inCoop()) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.status.inCoop");
        }
        if (entry.lost()) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.status.lost");
        }
        if (entry.captured()) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.status.captured");
        }
        return LocalizedText.resolve(language, "tamework.ui.linkedPanel.status.unloaded");
    }

    static String resolveUnavailableHealthText(LinkedNpcEntry entry) {
        return resolveUnavailableHealthText(entry, null);
    }

    static String resolveUnavailableHealthText(LinkedNpcEntry entry, String language) {
        if (entry != null && entry.inCoop()) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.health.unavailable.inCoop");
        }
        if (entry != null && entry.lost()) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.health.unavailable.lost");
        }
        if (entry != null && entry.captured()) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.health.unavailable.captured");
        }
        return LocalizedText.resolve(language, "tamework.ui.linkedPanel.health.unavailable.unloaded");
    }

    static String resolveDeadHappinessText(LinkedNpcEntry entry) {
        return resolveDeadHappinessText(entry, null);
    }

    static String resolveDeadHappinessText(LinkedNpcEntry entry, String language) {
        if (entry == null || !entry.dead()) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.happiness.unavailable");
        }
        return LocalizedText.resolve(language, "tamework.ui.linkedPanel.happiness.unavailable.dead");
    }

    static String resolveUnavailableHappinessText(LinkedNpcEntry entry) {
        return resolveUnavailableHappinessText(entry, null);
    }

    static String resolveUnavailableHappinessText(LinkedNpcEntry entry, String language) {
        if (entry != null && entry.inCoop()) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.happiness.unavailable.inCoop");
        }
        if (entry != null && entry.lost()) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.happiness.unavailable.lost");
        }
        if (entry != null && entry.captured()) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.happiness.unavailable.captured");
        }
        return LocalizedText.resolve(language, "tamework.ui.linkedPanel.happiness.unavailable.unloaded");
    }

    static String resolveBreedingCooldownTooltip(LinkedNpcEntry entry) {
        return resolveBreedingCooldownTooltip(entry, null);
    }

    static String resolveBreedingCooldownTooltip(LinkedNpcEntry entry, String language) {
        if (entry == null || !entry.loaded()) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.breedingCooldown.unavailable");
        }
        if (!entry.breedingCooldownKnown()) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.breedingCooldown.unavailable");
        }
        if (!entry.breedingCooldownActive()) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.breedingCooldown.ready");
        }
        return LocalizedText.format(
                language,
                "tamework.ui.linkedPanel.breedingCooldown.remaining",
                formatRemainingClock(entry.breedingCooldownRemainingMs())
        );
    }

    static String resolveHarvestCooldownTooltip(LinkedNpcEntry entry) {
        return resolveHarvestCooldownTooltip(entry, null);
    }

    static String resolveHarvestCooldownTooltip(LinkedNpcEntry entry, String language) {
        if (entry == null || !entry.loaded()) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.harvestCooldown.unavailable");
        }
        if (!entry.harvestCooldownKnown()) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.harvestCooldown.unavailable");
        }
        if (!entry.harvestCooldownActive()) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.harvestCooldown.ready");
        }
        return LocalizedText.format(
                language,
                "tamework.ui.linkedPanel.harvestCooldown.remaining",
                formatRemainingClock(entry.harvestCooldownRemainingMs())
        );
    }

    static String formatRemainingTime(long remainingMs, String language) {
        long totalSeconds = ceilMillisToSeconds(remainingMs);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        if (minutes <= 0L) {
            return LocalizedText.format(language, "tamework.ui.shared.duration.seconds", seconds);
        }
        return LocalizedText.format(language, "tamework.ui.shared.duration.minutesSeconds", minutes, seconds);
    }

    static String formatRemainingClock(long remainingMs) {
        long totalSeconds = ceilMillisToSeconds(remainingMs);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%d:%02d", minutes, seconds);
    }

    private static long ceilMillisToSeconds(long remainingMs) {
        return remainingMs > 0L ? 1L + ((remainingMs - 1L) / 1000L) : 0L;
    }
}
