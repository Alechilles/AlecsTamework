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
}
