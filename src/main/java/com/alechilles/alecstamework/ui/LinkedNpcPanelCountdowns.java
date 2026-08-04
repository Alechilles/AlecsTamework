package com.alechilles.alecstamework.ui;

import java.util.Map;

/**
 * Finds the shortest countdown currently visible in linked-panel row
 * presentations so the refresh coordinator can schedule its next wake.
 */
final class LinkedNpcPanelCountdowns {
    private LinkedNpcPanelCountdowns() {
    }

    /**
     * Returns the shortest positive visible countdown, or the coordinator's
     * no-countdown sentinel when none is visible.
     */
    static long shortest(Map<java.util.UUID, CommandPanelFeaturePresentation> rows) {
        return shortest(rows, null);
    }

    /**
     * Includes legacy linked-entry countdowns alongside feature-managed rows.
     */
    static long shortest(Map<java.util.UUID, CommandPanelFeaturePresentation> rows,
                         LinkedNpcEntry[] entries) {
        long result = LinkedPanelRefreshCoordinator.NO_COUNTDOWN_REMAINING_MS;
        for (CommandPanelFeaturePresentation row : rows.values()) {
            result = shortestBondedCountdown(result, row.bonded());
            result = shortestRevivalCountdown(result, row.revival());
            result = shortestRosterCountdown(result, row.roster());
        }
        if (entries != null) {
            for (LinkedNpcEntry entry : entries) {
                result = shortestLegacyCountdown(result, entry);
            }
        }
        return result;
    }

    private static long shortestLegacyCountdown(long current, LinkedNpcEntry entry) {
        if (entry == null) {
            return current;
        }
        long result = entry.dead() && entry.deadRespawnRemainingMs() >= 0L
                ? addVisible(current, entry.deadRespawnRemainingMs()) : current;
        if (entry.recallPending() && !entry.loaded() && !entry.dead()
                && !entry.captured() && !entry.inCoop() && !entry.lost()) {
            result = addVisible(result, entry.recallLostRemainingMs());
        }
        if (entry.breedingCooldownKnown() && entry.breedingCooldownActive()) {
            result = addVisible(result, entry.breedingCooldownRemainingMs());
        }
        if (entry.harvestCooldownKnown() && entry.harvestCooldownActive()) {
            result = addVisible(result, entry.harvestCooldownRemainingMs());
        }
        return result;
    }

    private static long shortestBondedCountdown(
            long current, BondedCompanionPanelPresentation bonded
    ) {
        if (bonded == null) {
            return current;
        }
        long afterStatus = bonded.status().blockReason()
                == com.alechilles.alecstamework.api.BondedCompanionActionBlockReason.COOLDOWN_ACTIVE
                ? addVisible(current, bonded.status().cooldownRemainingMs()) : current;
        Long sessionRemainingMs = bonded.attributes().containsKey("sessionRemainingMs")
                ? parsedLong(bonded.attributes().get("sessionRemainingMs")) : null;
        return sessionRemainingMs == null ? afterStatus
                : addVisible(afterStatus, sessionRemainingMs);
    }

    private static long shortestRevivalCountdown(
            long current, CommandReviveCostPresentation revival
    ) {
        return revival != null && revival.status()
                == com.alechilles.alecstamework.api.PaidCommandRevivalQuote.Status.COOLDOWN
                ? addVisible(current, revival.cooldownRemainingMs()) : current;
    }

    private static long shortestRosterCountdown(
            long current, CommandRosterStatusPresentation roster
    ) {
        if (roster == null) {
            return current;
        }
        long afterRemaining = roster.remainingMs() == null
                ? current
                : addVisible(current, roster.remainingMs());
        return add(afterRemaining, roster.cooldownRemainingMs());
    }

    private static long add(long current, long candidate) {
        if (candidate <= 0L) {
            return current;
        }
        return current < 0L ? candidate : Math.min(current, candidate);
    }
    private static long addVisible(long current, long candidate) {
        if (candidate < 0L) return current;
        return current < 0L ? candidate : Math.min(current, candidate);
    }

    private static Long parsedLong(String value) {
        if (value == null) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
