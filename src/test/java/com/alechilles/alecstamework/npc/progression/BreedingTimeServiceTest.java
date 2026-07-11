package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for demo-only progression time scaling. */
class BreedingTimeServiceTest {

    @Test
    void worldTimeScaledDurationsAreShortenedByProgressionScale() {
        long durationMs = BreedingTimeService.toGameDurationMs(
                2700.0,
                TwBreedingConfig.TimerBasis.WORLD_TIME_SCALED,
                20.0,
                20.0,
                30.0
        );

        assertEquals(1_800_000L, durationMs);
    }

    @Test
    void realTimeDurationsIgnoreProgressionScale() {
        long durationMs = BreedingTimeService.toGameDurationMs(
                2700.0,
                TwBreedingConfig.TimerBasis.REAL_TIME,
                20.0,
                20.0,
                30.0
        );

        assertEquals(54_000_000L, durationMs);
    }

    @Test
    void invalidProgressionScaleFallsBackToUnscaledDuration() {
        long durationMs = BreedingTimeService.toGameDurationMs(
                60.0,
                TwBreedingConfig.TimerBasis.WORLD_TIME_SCALED,
                20.0,
                20.0,
                Double.NaN
        );

        assertEquals(1_200_000L, durationMs);
    }

    @Test
    void invalidConfiguredDurationReturnsZero() {
        long durationMs = BreedingTimeService.toGameDurationMs(
                -1.0,
                TwBreedingConfig.TimerBasis.WORLD_TIME_SCALED,
                20.0,
                20.0,
                30.0
        );

        assertEquals(0L, durationMs);
    }

    @Test
    void signedDeadlinesWorkAcrossNegativeAndPositiveEpochs() {
        assertEquals(-50L, BreedingTimeService.deadlineAfter(-100L, 50L));
        assertEquals(100L, BreedingTimeService.remainingDurationMs(-100L, -200L));
        assertEquals(200L, BreedingTimeService.remainingDurationMs(100L, -100L));
        assertTrue(BreedingTimeService.isDeadlineActive(-100L, -200L));
        assertFalse(BreedingTimeService.isDeadlineActive(-100L, -50L));
    }

    @Test
    void zeroDeadlineIsAlwaysUnsetEvenAtNegativeEpoch() {
        assertEquals(0L, BreedingTimeService.deadlineAfter(-100L, 0L));
        assertEquals(1L, BreedingTimeService.deadlineAfter(-100L, 100L));
        assertEquals(-1L, BreedingTimeService.cooldownStartedAt(0L, 100L));
        assertEquals(0L, BreedingTimeService.remainingDurationMs(0L, -200L));
        assertFalse(BreedingTimeService.isDeadlineActive(0L, -200L));
    }

    @Test
    void timestampArithmeticSaturatesInsteadOfWrapping() {
        assertEquals(Long.MAX_VALUE, BreedingTimeService.saturatingAdd(Long.MAX_VALUE - 5L, 10L));
        assertEquals(Long.MIN_VALUE, BreedingTimeService.saturatingAdd(Long.MIN_VALUE + 5L, -10L));
        assertEquals(Long.MAX_VALUE, BreedingTimeService.saturatingSubtract(Long.MAX_VALUE, -1L));
        assertEquals(Long.MIN_VALUE, BreedingTimeService.saturatingSubtract(Long.MIN_VALUE, 1L));
        assertEquals(Long.MAX_VALUE, BreedingTimeService.remainingDurationMs(Long.MAX_VALUE, Long.MIN_VALUE));
    }

    @Test
    void cooldownReconstructionPreservesNegativeDeadlineAndStartSign() {
        BreedingTimeService.CooldownTiming timing =
                BreedingTimeService.reconstructCooldownTiming(-100L, -250L);

        assertEquals(-100L, timing.deadlineMs());
        assertEquals(-250L, timing.startedAtMs());
        assertEquals(150L, timing.durationMs());
    }
}
