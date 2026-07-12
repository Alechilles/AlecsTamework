package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression coverage for signed world-time cooldown restoration after respawn. */
class CommandRespawnProgressionRestoreServiceTest {
    @Test
    void negativeFutureDeadlinePreservesSignAndRemainingWindow() {
        BreedingTimeService.CooldownTiming timing =
                CommandRespawnProgressionRestoreService.restoreCooldownTiming(-1_000L, -5_000L);

        assertEquals(-1_000L, timing.deadlineMs());
        assertEquals(-5_000L, timing.startedAtMs());
        assertEquals(4_000L, timing.durationMs());
    }

    @Test
    void zeroRemainsTheOnlyUnsetSentinel() {
        BreedingTimeService.CooldownTiming timing =
                CommandRespawnProgressionRestoreService.restoreCooldownTiming(0L, -5_000L);

        assertEquals(0L, timing.deadlineMs());
        assertEquals(0L, timing.startedAtMs());
        assertEquals(0L, timing.durationMs());
    }
}
