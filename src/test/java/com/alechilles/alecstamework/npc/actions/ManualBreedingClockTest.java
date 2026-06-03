package com.alechilles.alecstamework.npc.actions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for the manual breeding selection time basis. */
class ManualBreedingClockTest {

    @Test
    void manualSelectionClockUsesWallClockTime() {
        long before = System.currentTimeMillis();
        long resolved = ManualBreedingClock.nowMs();
        long after = System.currentTimeMillis();

        assertTrue(resolved >= before);
        assertTrue(resolved <= after);
    }
}
