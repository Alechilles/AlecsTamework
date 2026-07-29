package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummonedCompanionExperienceSystemTest {
    @Test
    void onlyLiveBondedProjectionsAreEligibleForSummonedXp() {
        TameworkProjectionIdentityComponent bonded =
                TameworkProjectionIdentityComponent.bondedCompanion("profile-a", "lease-a");
        TameworkProjectionIdentityComponent ordinary = new TameworkProjectionIdentityComponent(
                "profile-a", "operation-a", TameworkProjectionIdentityComponent.KIND_COMMAND_ROSTER,
                null, null, 0L);

        assertTrue(SummonedCompanionExperienceSystem.isEligibleForSummonedXp(true, bonded, false));
        assertFalse(SummonedCompanionExperienceSystem.isEligibleForSummonedXp(true, ordinary, false));
        assertFalse(SummonedCompanionExperienceSystem.isEligibleForSummonedXp(true, bonded, true));
        assertFalse(SummonedCompanionExperienceSystem.isEligibleForSummonedXp(false, bonded, false));
    }
}
