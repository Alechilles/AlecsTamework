package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.localization.LocalizedText;

/**
 * Formats linked NPC panel status and health text labels.
 */
final class LinkedNpcPanelStatusTextService {
    private static final String LAST_KNOWN_LABEL_KEY = "tamework.ui.linkedPanel.cached.lastKnown";
    private static final String LAST_KNOWN_TOOLTIP_KEY = "tamework.ui.linkedPanel.cached.lastKnownTooltip";
    private static final String LAST_KNOWN_LABEL_FALLBACK = "Last known";

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

    /** Uses the same lifecycle precedence as the status label; recovery holds keep their own text. */
    static String resolveAvailabilityEmblem(LinkedNpcEntry entry) {
        if (entry == null || entry.recoveryHeld()) return null;
        String name = entry.dead() ? "Dead" : entry.inCoop() ? "InCoop"
                : entry.lost() ? "Lost" : entry.captured() ? "Captured"
                : !entry.loaded() ? "Unloaded" : null;
        return name == null ? null : "Tamework/StatusEmblems/" + name + ".png";
    }

    static String resolveAvailabilityStatusText(LinkedNpcEntry entry) {
        return resolveAvailabilityStatusText(entry, null);
    }

    static String resolveAvailabilityStatusText(LinkedNpcEntry entry, String language) {
        if (entry == null) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.status.unloaded");
        }
        if (entry.recoveryHeld()) {
            if (entry.recoveryIncidentId() != null) {
                return LocalizedText.format(language,
                        "tamework.ui.linkedPanel.status.recoveryHeldReference",
                        entry.recoveryIncidentId());
            }
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.status.recoveryHeld");
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

    static boolean breedingBlockedByHappiness(LinkedNpcEntry entry) {
        return entry != null && entry.hasHappiness() && entry.breedingCooldownKnown()
                && !entry.breedingCooldownActive()
                && entry.happinessRatio() < entry.breedingHappinessRatio();
    }

    static String resolveBreedingCooldownTooltip(LinkedNpcEntry entry, String language) {
        if (entry == null || !entry.breedingCooldownKnown()) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.breedingCooldown.unavailable");
        }
        String tooltip;
        if (!entry.breedingEnabled()) {
            tooltip = LocalizedText.resolve(language, "tamework.ui.linkedPanel.breedingCooldown.off");
        } else if (entry.breedingCooldownRemainingMs() < 0L) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.cached.timerUnknown");
        } else if (breedingBlockedByHappiness(entry)) {
            tooltip = LocalizedText.resolve(language, "tamework.ui.linkedPanel.breedingCooldown.unhappy");
        } else if (!entry.breedingCooldownActive()) {
            tooltip = LocalizedText.resolve(language, "tamework.ui.linkedPanel.breedingCooldown.ready");
        } else if (!entry.loaded() && entry.breedingCooldownRemainingMs() <= 0L) {
            return resolveLastKnownTooltip(language);
        } else {
            tooltip = LocalizedText.format(
                    language,
                    "tamework.ui.linkedPanel.breedingCooldown.remaining",
                    formatRemainingClock(entry.breedingCooldownRemainingMs())
            );
        }
        return appendLastKnownTooltip(tooltip, entry, language);
    }

    static String resolveHarvestCooldownTooltip(LinkedNpcEntry entry) {
        return resolveHarvestCooldownTooltip(entry, null);
    }

    static String resolveHarvestCooldownTooltip(LinkedNpcEntry entry, String language) {
        if (entry == null || !entry.harvestCooldownKnown()) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.harvestCooldown.unavailable");
        }
        String tooltip;
        if (entry.harvestCooldownRemainingMs() < 0L) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.cached.timerUnknown");
        } else if (!entry.harvestCooldownActive()) {
            tooltip = LocalizedText.resolve(language, "tamework.ui.linkedPanel.harvestCooldown.ready");
        } else if (!entry.loaded() && entry.harvestCooldownRemainingMs() <= 0L) {
            return resolveLastKnownTooltip(language);
        } else {
            tooltip = LocalizedText.format(
                    language,
                    "tamework.ui.linkedPanel.harvestCooldown.remaining",
                    formatRemainingClock(entry.harvestCooldownRemainingMs())
            );
        }
        return appendLastKnownTooltip(tooltip, entry, language);
    }

    static String appendLastKnownTooltip(String tooltip, LinkedNpcEntry entry, String language) {
        if (entry == null || entry.loaded() || tooltip == null || tooltip.isBlank()) {
            return tooltip;
        }
        return tooltip + "\n" + resolveLastKnownTooltip(language);
    }

    static String resolveCooldownLabel(boolean active, long remainingMs, String readyText,
                                       LinkedNpcEntry entry, String language) {
        if (remainingMs < 0L) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.cached.unknown");
        }
        if (!active) {
            return readyText;
        }
        if (entry != null && !entry.loaded() && remainingMs <= 0L) {
            return resolveLastKnownLabel(language);
        }
        return formatRemainingClock(remainingMs);
    }

    private static String resolveLastKnownLabel(String language) {
        String label = LocalizedText.resolve(language, LAST_KNOWN_LABEL_KEY);
        return LAST_KNOWN_LABEL_KEY.equals(label) ? LAST_KNOWN_LABEL_FALLBACK : label;
    }

    private static String resolveLastKnownTooltip(String language) {
        String hint = LocalizedText.resolve(language, LAST_KNOWN_TOOLTIP_KEY);
        return LAST_KNOWN_TOOLTIP_KEY.equals(hint)
                ? "Last known state; refreshes when loaded."
                : hint;
    }

    static String formatRemainingTime(long remainingMs, String language) {
        if (remainingMs < 60_000L) {
            return LocalizedText.format(language, "tamework.ui.shared.duration.seconds",
                    ceilMillisToSeconds(remainingMs));
        }
        long minutes = 1L + ((remainingMs - 1L) / 60_000L);
        return LocalizedText.format(language, "tamework.ui.shared.duration.minutes", minutes);
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
