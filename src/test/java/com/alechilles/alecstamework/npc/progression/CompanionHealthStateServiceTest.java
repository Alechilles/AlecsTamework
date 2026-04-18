package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class CompanionHealthStateServiceTest {

    @Test
    void resolveStoredHealthPercentReturnsCurrentPercentOfMaxHealth() {
        assertEquals(25.0, CompanionHealthStateService.resolveStoredHealthPercent(5.0, 20.0), 0.000001);
    }

    @Test
    void resolveStoredHealthPercentClampsOverflowingHealth() {
        assertEquals(100.0, CompanionHealthStateService.resolveStoredHealthPercent(30.0, 20.0), 0.000001);
    }

    @Test
    void resolveStoredHealthPercentRejectsInvalidStats() {
        assertNull(CompanionHealthStateService.resolveStoredHealthPercent(Double.NaN, 20.0));
        assertNull(CompanionHealthStateService.resolveStoredHealthPercent(5.0, 0.0));
    }

    @Test
    void resolveRestoredHealthValueUsesRestoredMaxHealth() {
        assertEquals(15.0, CompanionHealthStateService.resolveRestoredHealthValue(50.0, 30.0), 0.000001);
    }

    @Test
    void resolveRestoredHealthValueClampsOutOfRangePercents() {
        assertEquals(30.0, CompanionHealthStateService.resolveRestoredHealthValue(150.0, 30.0), 0.000001);
        assertEquals(0.0, CompanionHealthStateService.resolveRestoredHealthValue(-25.0, 30.0), 0.000001);
    }
}
