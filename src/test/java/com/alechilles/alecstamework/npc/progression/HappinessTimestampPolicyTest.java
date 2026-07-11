package com.alechilles.alecstamework.npc.progression;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for the wall-clock happiness timestamp contract. */
class HappinessTimestampPolicyTest {
    @Test
    void rejectsSignedWorldAndUnsetValuesButPreservesWallClockTime() {
        assertFalse(HappinessTimestampPolicy.isValid(-1_000L));
        assertFalse(HappinessTimestampPolicy.isValid(0L));
        assertTrue(HappinessTimestampPolicy.isValid(1_000L));
        assertEquals(1_000L, HappinessTimestampPolicy.orElse(1_000L, 2_000L));
        assertEquals(2_000L, HappinessTimestampPolicy.orElse(-1_000L, 2_000L));
    }

    @Test
    void requiresPositiveFallback() {
        assertThrows(IllegalArgumentException.class, () -> HappinessTimestampPolicy.orElse(0L, 0L));
    }
}
