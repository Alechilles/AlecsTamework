package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
