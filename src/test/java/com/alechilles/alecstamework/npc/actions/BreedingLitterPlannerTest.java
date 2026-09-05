package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Protects ordinary breeding from requiring optional managed activity profiles. */
class BreedingLitterPlannerTest {
    @Test
    void ordinaryLitterCanBreedWithoutManagedActivityAssets() {
        ManagedActivityConfigRegistry registry = new ManagedActivityConfigRegistry();
        String profile = BreedingLitterPlanner.resolveManagedProfile(
                List.of(child("Cat_Pet"), child("Cat_Longhair_Pet")),
                role -> registry.resolveRole(role)
                        .map(value -> value.profile().profileId()).orElse(null));

        assertEquals("", profile);
    }

    @Test
    void managedLitterRetainsItsRequiredAdmissionProfile() {
        assertEquals("managed-herd", BreedingLitterPlanner.resolveManagedProfile(
                List.of(child("Managed_Calf"), child("Managed_Calf")),
                role -> "managed-herd"));
    }

    @Test
    void mixedLitterCannotBypassManagedAdmission() {
        assertNull(BreedingLitterPlanner.resolveManagedProfile(
                List.of(child("Ordinary_Calf"), child("Managed_Calf")),
                role -> role.equals("Managed_Calf") ? "managed-herd" : null));
        assertNull(BreedingLitterPlanner.resolveManagedProfile(
                List.of(child("Managed_Calf"), child("Ordinary_Calf")),
                role -> role.equals("Managed_Calf") ? "managed-herd" : null));
    }

    private static BreedingLitterOperation.ChildPlan child(String role) {
        return new BreedingLitterOperation.ChildPlan(UUID.randomUUID(), role, role, null, null, null);
    }
}
