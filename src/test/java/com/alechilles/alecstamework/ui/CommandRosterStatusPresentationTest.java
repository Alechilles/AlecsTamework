package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.CommandTimedSummoningState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRosterStatusPresentationTest {
    @Test
    void storedSummonIsDisabledByCooldownOrAuthoritativeCap() {
        var cooldown = status(CommandTimedSummoningState.ROSTER_STORED, 10_000L, 1, 3);
        assertTrue(cooldown.summonVisible());
        assertFalse(cooldown.summonEnabled());

        var capped = status(CommandTimedSummoningState.ROSTER_STORED, 0L, 3, 3);
        assertTrue(capped.capBlocked());
        assertFalse(capped.summonEnabled());
    }

    @Test
    void activeAndUnloadedRowsExposeDismissWhileStoringDoesNot() {
        assertTrue(status(CommandTimedSummoningState.ACTIVE, 0L, 1, 3).dismissEnabled());
        assertTrue(status(CommandTimedSummoningState.UNLOADED, 0L, 1, 3).dismissEnabled());
        assertFalse(status(CommandTimedSummoningState.STORING, 0L, 1, 3).dismissVisible());
    }

    @Test
    void deadRevivalIsVisiblyBlockedAtCapacity() {
        assertTrue(status(CommandTimedSummoningState.DEAD_REVIVABLE, 0L, 2, 2)
                .reviveCapBlocked());
    }

    private static CommandRosterStatusPresentation status(
            CommandTimedSummoningState state, long cooldown, int active, int limit) {
        return new CommandRosterStatusPresentation(
                "profile", "test:horn", state, 1L, null, 60_000L,
                false, cooldown, active, limit, "dragons", null);
    }
}
