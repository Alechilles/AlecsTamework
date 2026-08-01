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
        long result = LinkedPanelRefreshCoordinator.NO_COUNTDOWN_REMAINING_MS;
        for (CommandPanelFeaturePresentation row : rows.values()) {
            result = shortestBondedCountdown(result, row.bonded());
            result = shortestRevivalCountdown(result, row.revival());
            result = shortestRosterCountdown(result, row.roster());
        }
        return result;
    }

    private static long shortestBondedCountdown(
            long current, BondedCompanionPanelPresentation bonded
    ) {
        if (bonded == null) {
            return current;
        }
        long afterStatus = add(current, bonded.status().cooldownRemainingMs());
        return add(afterStatus, positiveLong(bonded.attributes().get("sessionRemainingMs")));
    }

    private static long shortestRevivalCountdown(
            long current, CommandReviveCostPresentation revival
    ) {
        return revival == null ? current : add(current, revival.cooldownRemainingMs());
    }

    private static long shortestRosterCountdown(
            long current, CommandRosterStatusPresentation roster
    ) {
        if (roster == null) {
            return current;
        }
        long afterRemaining = roster.remainingMs() == null
                ? current
                : add(current, roster.remainingMs());
        return add(afterRemaining, roster.cooldownRemainingMs());
    }

    private static long add(long current, long candidate) {
        if (candidate <= 0L) {
            return current;
        }
        return current < 0L ? candidate : Math.min(current, candidate);
    }

    private static long positiveLong(String value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
