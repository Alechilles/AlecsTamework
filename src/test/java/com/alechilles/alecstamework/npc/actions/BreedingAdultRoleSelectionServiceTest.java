package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Regression coverage for weighted adult-role selection in cross-role breeding families. */
class BreedingAdultRoleSelectionServiceTest {
    private final BreedingAdultRoleSelectionService service = new BreedingAdultRoleSelectionService();

    @Test
    void weightedAdultSelectionUsesConfiguredWeights() throws Exception {
        TwBreedingConfig.RoleFamily family = weightedFamily(
                adultRole("Deer_Stag", 1.0),
                adultRole("Deer_Doe", 3.0)
        );

        assertEquals("Deer_Stag", service.selectAdultRole(family, null, 0.20));
        assertEquals("Deer_Doe", service.selectAdultRole(family, null, 0.26));
        assertEquals("Deer_Doe", service.selectAdultRole(family, null, 0.99));
    }

    @Test
    void weightedAdultSelectionIgnoresBlankAndZeroWeightEntries() throws Exception {
        TwBreedingConfig.RoleFamily family = weightedFamily(
                adultRole("", 10.0),
                adultRole("Deer_Stag", 0.0),
                adultRole("Deer_Doe", 1.0)
        );

        assertEquals("Deer_Doe", service.selectAdultRole(family, null, 0.0));
        assertEquals("Deer_Doe", service.selectAdultRole(family, null, 0.99));
    }

    @Test
    void weightedAdultSelectionReturnsNullWhenNoSelectableAdultRoleExists() throws Exception {
        TwBreedingConfig.RoleFamily family = weightedFamily(
                adultRole("", 1.0),
                adultRole("Deer_Stag", -1.0)
        );

        assertNull(service.selectAdultRole(family, null, 0.50));
    }

    @Test
    void legacyAdultRoleStillWorksWhenAdultRolesAreOmitted() throws Exception {
        TwBreedingConfig.RoleFamily family = new TwBreedingConfig.RoleFamily();
        setField(family, "adultRoleId", "Legacy_Deer");

        assertEquals("Legacy_Deer", service.selectAdultRole(family, null, 0.50));
    }

    @Test
    void genderedAdultSelectionUsesMatchingChoices() throws Exception {
        TwBreedingConfig.RoleFamily family = weightedFamily(
                adultRole("Deer_Stag", 1.0, TwBreedingConfig.Gender.Male),
                adultRole("Deer_Doe", 10.0, TwBreedingConfig.Gender.Female)
        );

        assertEquals("Deer_Stag", service.selectAdultRole(family, null, 0.99, TwBreedingConfig.Gender.Male));
        assertEquals("Deer_Doe", service.selectAdultRole(family, null, 0.0, TwBreedingConfig.Gender.Female));
    }

    @Test
    void genderedAdultSelectionFallsBackToUngenderedChoicesWhenNeeded() throws Exception {
        TwBreedingConfig.RoleFamily family = weightedFamily(
                adultRole("Deer_Stag", 1.0, TwBreedingConfig.Gender.Male),
                adultRole("Deer_Adult", 1.0)
        );

        assertEquals("Deer_Adult", service.selectAdultRole(family, null, 0.50, TwBreedingConfig.Gender.Female));
    }

    private static TwBreedingConfig.RoleFamily weightedFamily(TwBreedingConfig.AdultRoleChoice... choices)
            throws Exception {
        TwBreedingConfig.RoleFamily family = new TwBreedingConfig.RoleFamily();
        setField(family, "adultRoles", choices);
        setField(family, "babyRoleId", "Deer_Fawn");
        return family;
    }

    private static TwBreedingConfig.AdultRoleChoice adultRole(String roleId, double weight) throws Exception {
        return adultRole(roleId, weight, null);
    }

    private static TwBreedingConfig.AdultRoleChoice adultRole(String roleId,
                                                             double weight,
                                                             TwBreedingConfig.Gender gender) throws Exception {
        TwBreedingConfig.AdultRoleChoice choice = new TwBreedingConfig.AdultRoleChoice();
        setField(choice, "roleId", roleId);
        setField(choice, "weight", weight);
        setField(choice, "gender", gender);
        return choice;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
