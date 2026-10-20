package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.CommandTimedSummoningState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LinkedNpcPanelRosterLinesTest {
    @Test
    void anUntimedMemberOfAnUnlimitedRosterShowsOnlyItsState() {
        CommandRosterStatusPresentation stored = roster(CommandTimedSummoningState.ROSTER_STORED, null, 0L, 0, 0);

        assertEquals("", LinkedNpcPanelFeatureBinder.statusLine(
                new CommandPanelFeaturePresentation(stored, null), "en-US"));
        assertEquals("", LinkedNpcPanelFeatureBinder.capacityLine(stored, "en-US"));
    }

    @Test
    void aRunningTimerAndAnActiveLimitAreShown() {
        CommandRosterStatusPresentation summoned = roster(CommandTimedSummoningState.ACTIVE, 90_000L, 0L, 1, 3);
        CommandRosterStatusPresentation cooling = roster(CommandTimedSummoningState.ROSTER_STORED, null, 30_000L, 1, 3);

        assertEquals("Summon time: 2m", LinkedNpcPanelFeatureBinder.statusLine(
                new CommandPanelFeaturePresentation(summoned, null), "en-US"));
        assertEquals("Summon cooldown: 30s", LinkedNpcPanelFeatureBinder.statusLine(
                new CommandPanelFeaturePresentation(cooling, null), "en-US"));
        assertEquals("Active: 1 / 3", LinkedNpcPanelFeatureBinder.capacityLine(summoned, "en-US"));
    }

    private static CommandRosterStatusPresentation roster(CommandTimedSummoningState state, Long remainingMs,
                                                         long cooldownMs, int active, int limit) {
        return new CommandRosterStatusPresentation("profile", "test:horn", state, 1L, remainingMs,
                60_000L, remainingMs == null && cooldownMs == 0L, cooldownMs, active, limit, null, null);
    }
}
