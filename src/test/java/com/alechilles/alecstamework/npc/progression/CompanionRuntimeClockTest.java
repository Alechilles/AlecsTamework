package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CompanionRuntimeClockTest {
    @AfterEach
    void reset() {
        CompanionRuntimeClock.resetForTests();
    }

    @Test
    void alternatingWorldTicksAtTheSameInstantAdvanceSharedTimeOnce() {
        CompanionRuntimeClock.advanceForWorldTick(1.0f, 1_000_000_000L);
        CompanionRuntimeClock.advanceForWorldTick(1.0f, 1_000_000_000L);
        CompanionRuntimeClock.advanceForWorldTick(1.0f, 2_000_000_000L);
        CompanionRuntimeClock.advanceForWorldTick(1.0f, 2_000_000_000L);

        assertEquals(2_000L, CompanionRuntimeClock.nowMs());
    }

    @Test
    void noTicksMeansNoCatchUpAndExplicitAdvanceStillWorks() {
        CompanionRuntimeClock.advanceForWorldTick(1.0f, 1_000_000_000L);
        assertEquals(1_000L, CompanionRuntimeClock.nowMs());

        assertEquals(1_000L, CompanionRuntimeClock.nowMs());
        CompanionRuntimeClock.advanceByDeltaSeconds(0.5f);

        assertEquals(1_500L, CompanionRuntimeClock.nowMs());
    }

    @Test
    void leadingWorldCanChangeWithoutAClockStall() {
        CompanionRuntimeClock.advanceForWorldTick(1.0f, 1_000_000_000L);
        CompanionRuntimeClock.advanceForWorldTick(1.0f, 2_000_000_000L);
        // The next world resumes after a long absence. The tick cap admits its
        // current interval only, rather than either replaying or stalling.
        CompanionRuntimeClock.advanceForWorldTick(1.0f, 3_602_000_000_000L);

        assertEquals(3_000L, CompanionRuntimeClock.nowMs());
    }
}
