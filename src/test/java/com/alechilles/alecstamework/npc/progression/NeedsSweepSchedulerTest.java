package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class NeedsSweepSchedulerTest {
    @Test
    void stableOffsetIsDeterministicForNpc() {
        UUID npcId = new UUID(123L, 456L);

        assertEquals(
                NeedsSweepScheduler.stableOffsetMs(npcId, 2_000L),
                NeedsSweepScheduler.stableOffsetMs(npcId, 2_000L)
        );
    }

    @Test
    void stableOffsetStaysInsideInterval() {
        long offset = NeedsSweepScheduler.stableOffsetMs(new UUID(123L, 456L), 2_000L);

        assertTrue(offset >= 0L);
        assertTrue(offset < 2_000L);
    }

    @Test
    void dueWhenLastSweepPlusIntervalHasPassed() {
        assertTrue(NeedsSweepScheduler.shouldRunSweep(
                new UUID(1L, 2L),
                10_000L,
                12_000L,
                2_000L
        ));
    }

    @Test
    void notDueBeforeIntervalHasPassed() {
        assertFalse(NeedsSweepScheduler.shouldRunSweep(
                new UUID(1L, 2L),
                10_000L,
                11_000L,
                2_000L
        ));
    }

    @Test
    void lowNeedsKeepBaseInterval() {
        assertEquals(2_000L, NeedsSweepIntervalPolicy.intervalMsForRatios(0.10, 0.90, 2_000L));
        assertEquals(2_000L, NeedsSweepIntervalPolicy.intervalMsForRatios(0.90, 0.10, 2_000L));
    }

    @Test
    void satisfiedNeedsUseLongerInterval() {
        assertEquals(16_000L, NeedsSweepIntervalPolicy.intervalMsForRatios(0.90, 0.90, 2_000L));
    }

    @Test
    void adaptiveIntervalIsCapped() {
        assertEquals(30_000L, NeedsSweepIntervalPolicy.intervalMsForRatios(1.0, 1.0, 10_000L));
    }

    @Test
    void invalidBaseIntervalFallsBackToImmediateSweep() {
        assertEquals(0L, NeedsSweepIntervalPolicy.intervalMsForRatios(1.0, 1.0, 0L));
    }
}
