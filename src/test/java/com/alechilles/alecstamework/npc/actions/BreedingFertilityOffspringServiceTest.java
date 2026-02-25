package com.alechilles.alecstamework.npc.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BreedingFertilityOffspringServiceTest {
    @Test
    void resolveOffspringCountSupportsNoOffspringBelowOne() {
        assertEquals(0, BreedingFertilityOffspringService.resolveOffspringCount(0.40, 0.90));
        assertEquals(1, BreedingFertilityOffspringService.resolveOffspringCount(0.40, 0.10));
    }

    @Test
    void resolveOffspringCountSupportsMultipleOffspringAboveOne() {
        assertEquals(3, BreedingFertilityOffspringService.resolveOffspringCount(2.30, 0.10));
        assertEquals(2, BreedingFertilityOffspringService.resolveOffspringCount(2.30, 0.80));
    }

    @Test
    void resolveOffspringCountClampsAtConfiguredCap() {
        assertEquals(4, BreedingFertilityOffspringService.resolveOffspringCount(9.0, 0.50));
    }
}
