package com.alechilles.alecstamework.npc.progression;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests breeding eligibility math helpers used by interaction breeding flow. */
class BreedingEligibilityServiceTest {

    @Test
    void resolveThresholdPrefersFiniteInteractionOverride() {
        double threshold = BreedingEligibilityService.resolveThreshold(82.5, 70.0);
        assertEquals(82.5, threshold);
    }

    @Test
    void resolveThresholdFallsBackWhenOverrideMissing() {
        double threshold = BreedingEligibilityService.resolveThreshold(null, 70.0);
        assertEquals(70.0, threshold);
    }

    @Test
    void resolveEffectiveHappinessAppliesMultiplierAndBonus() {
        double effective = BreedingEligibilityService.resolveEffectiveHappiness(40.0, 1.2, 10.0);
        assertEquals(58.0, effective, 0.000001);
        assertTrue(BreedingEligibilityService.isEligible(effective, 55.0));
    }

    @Test
    void resolveEffectiveHappinessSanitizesInvalidInputs() {
        double effective = BreedingEligibilityService.resolveEffectiveHappiness(Double.NaN, Double.NaN, Double.NaN);
        assertEquals(0.0, effective, 0.000001);
        assertFalse(BreedingEligibilityService.isEligible(Double.NaN, 10.0));
    }
}
