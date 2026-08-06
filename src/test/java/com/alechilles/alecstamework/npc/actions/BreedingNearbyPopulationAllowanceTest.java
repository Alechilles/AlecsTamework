package com.alechilles.alecstamework.npc.actions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression coverage for delayed litters racing the nearby population cap. */
class BreedingNearbyPopulationAllowanceTest {

    @Test
    void litterIsLimitedToHeadroomObservedAtBirthTime() {
        BreedingNearbyPopulationAllowance allowance =
                new BreedingNearbyPopulationAllowance();

        assertEquals(2, allowance.limit(4, 8, 10));
        assertEquals(0, allowance.limit(4, 10, 10));
        assertEquals(4, allowance.limit(4, 8, 0));
    }
}
