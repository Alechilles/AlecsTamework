package com.alechilles.alecstamework.npc.progression;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests disposition scaling behavior for happiness equilibrium modifiers.
 */
class CompanionHappinessModifierServiceTest {

    @Test
    void applyDispositionToOffsetScalesPositiveGainsDirectly() {
        double adjusted = CompanionHappinessModifierService.applyDispositionToOffset(10.0, 1.2);
        assertEquals(12.0, adjusted, 0.000001);
    }

    @Test
    void applyDispositionToOffsetSoftensDetractorsWhenDispositionIsHigh() {
        double adjusted = CompanionHappinessModifierService.applyDispositionToOffset(-10.0, 1.2);
        assertEquals(-8.333333, adjusted, 0.000001);
    }

    @Test
    void applyDispositionToOffsetAmplifiesDetractorsWhenDispositionIsLow() {
        double adjusted = CompanionHappinessModifierService.applyDispositionToOffset(-10.0, 0.8);
        assertEquals(-12.5, adjusted, 0.000001);
    }

    @Test
    void applyDispositionToOffsetFallsBackToNeutralForInvalidMultiplier() {
        double adjusted = CompanionHappinessModifierService.applyDispositionToOffset(10.0, -1.0);
        assertEquals(10.0, adjusted, 0.000001);
    }
}

