package com.alechilles.alecstamework.npc.systems;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic regression coverage for signed passive-breeding sweep scheduling. */
class CompanionPassiveBreedingSignedTimeTest {
    @Test
    void firstSweepRunsAtNegativeEpoch() {
        SignedIntervalSchedule schedule = new SignedIntervalSchedule();

        assertTrue(schedule.shouldRun(-10_000L, 2_000L));
        assertTrue(schedule.isInitialized());
        assertEquals(-8_000L, schedule.nextRunAtMs());
    }

    @Test
    void negativeToNegativeDeadlineUsesOrderingInsteadOfSign() {
        SignedIntervalSchedule schedule = initializedAt(-10_000L, 2_000L);

        assertFalse(schedule.shouldRun(-9_000L, 2_000L));
        assertTrue(schedule.shouldRun(-8_000L, 2_000L));
        assertEquals(-6_000L, schedule.nextRunAtMs());
    }

    @Test
    void negativeToPositiveDeadlineCrossesZeroNormally() {
        SignedIntervalSchedule schedule = initializedAt(-1_000L, 1_500L);

        assertEquals(500L, schedule.nextRunAtMs());
        assertFalse(schedule.shouldRun(0L, 1_500L));
        assertTrue(schedule.shouldRun(500L, 1_500L));
        assertEquals(2_000L, schedule.nextRunAtMs());
    }

    @Test
    void zeroSweepDeadlineIsNotMistakenForUninitializedState() {
        SignedIntervalSchedule schedule = initializedAt(-1_000L, 1_000L);

        assertEquals(0L, schedule.nextRunAtMs());
        assertFalse(schedule.shouldRun(-1L, 1_000L));
        assertTrue(schedule.shouldRun(0L, 1_000L));
        assertEquals(1_000L, schedule.nextRunAtMs());
    }

    @Test
    void backwardsClockJumpRunsRepairSweepAndStartsFreshInterval() {
        SignedIntervalSchedule schedule = initializedAt(10_000L, 5_000L);
        assertFalse(schedule.shouldRun(12_000L, 5_000L));

        assertTrue(schedule.shouldRun(5_000L, 5_000L));
        assertEquals(5_000L, schedule.lastNowMs());
        assertEquals(10_000L, schedule.nextRunAtMs());
        assertFalse(schedule.shouldRun(9_999L, 5_000L));
    }

    @Test
    void overflowingDeadlineSaturatesAndRunsAtMaximumOnlyOnce() {
        SignedIntervalSchedule schedule = initializedAt(Long.MAX_VALUE - 5L, 10L);

        assertEquals(Long.MAX_VALUE, schedule.nextRunAtMs());
        assertFalse(schedule.shouldRun(Long.MAX_VALUE - 1L, 10L));
        assertTrue(schedule.shouldRun(Long.MAX_VALUE, 10L));
        assertFalse(schedule.shouldRun(Long.MAX_VALUE, 10L));
    }

    @Test
    void settingsRefreshScheduleHandlesRollbackAndOverflow() {
        SignedIntervalSchedule refreshSchedule = new SignedIntervalSchedule();
        refreshSchedule.restart(-5_000L, 1_000L);

        assertFalse(refreshSchedule.shouldRun(-4_500L, 1_000L));
        assertTrue(refreshSchedule.shouldRun(-6_000L, 1_000L));
        assertEquals(-5_000L, refreshSchedule.nextRunAtMs());

        refreshSchedule.restart(Long.MAX_VALUE - 5L, 10L);
        assertEquals(Long.MAX_VALUE, refreshSchedule.nextRunAtMs());
        assertTrue(refreshSchedule.shouldRun(Long.MAX_VALUE, 10L));
        assertFalse(refreshSchedule.shouldRun(Long.MAX_VALUE, 10L));
    }

    private static SignedIntervalSchedule initializedAt(long nowMs, long intervalMs) {
        SignedIntervalSchedule schedule = new SignedIntervalSchedule();
        assertTrue(schedule.shouldRun(nowMs, intervalMs));
        return schedule;
    }
}
