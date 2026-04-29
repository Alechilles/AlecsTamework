package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression coverage for offspring role and gender reconciliation. */
class BreedingOffspringRoleResolverTest {

    @Test
    void selectedAdultRoleGenderOverridesSampledGender() throws Exception {
        TwBreedingConfig.RoleFamily family = weightedFamily(
                adultRole("Deer_Stag", TwBreedingConfig.Gender.Male)
        );

        assertEquals(
                TwBreedingConfig.Gender.Male,
                BreedingOffspringRoleResolver.resolveSelectedGender(
                        family,
                        "Deer_Stag",
                        TwBreedingConfig.Gender.Female
                )
        );
    }

    @Test
    void sampledGenderIsKeptWhenAdultRoleHasNoGender() throws Exception {
        TwBreedingConfig.RoleFamily family = weightedFamily(adultRole("Deer_Adult", null));

        assertEquals(
                TwBreedingConfig.Gender.Female,
                BreedingOffspringRoleResolver.resolveSelectedGender(
                        family,
                        "Deer_Adult",
                        TwBreedingConfig.Gender.Female
                )
        );
    }

    private static TwBreedingConfig.RoleFamily weightedFamily(TwBreedingConfig.AdultRoleChoice... choices)
            throws Exception {
        TwBreedingConfig.RoleFamily family = new TwBreedingConfig.RoleFamily();
        setField(family, "adultRoles", choices);
        return family;
    }

    private static TwBreedingConfig.AdultRoleChoice adultRole(String roleId,
                                                             TwBreedingConfig.Gender gender) throws Exception {
        TwBreedingConfig.AdultRoleChoice choice = new TwBreedingConfig.AdultRoleChoice();
        setField(choice, "roleId", roleId);
        setField(choice, "gender", gender);
        return choice;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
