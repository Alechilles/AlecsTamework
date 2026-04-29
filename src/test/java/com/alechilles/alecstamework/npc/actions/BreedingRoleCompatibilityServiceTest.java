package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for family-based cross-role breeding compatibility. */
class BreedingRoleCompatibilityServiceTest {
    private final BreedingRoleCompatibilityService service = new BreedingRoleCompatibilityService();

    @Test
    void sameLifecycleFamilyAllowsWeightedAdultRolesButRejectsUnrelatedRoles() throws Exception {
        TwBreedingConfig config = configWithFamily(
                adultRole("Deer_Stag", 1.0),
                adultRole("Deer_Doe", 1.0)
        );
        TwBreedingConfig.PairingSettings pairing = new TwBreedingConfig.PairingSettings();
        setField(pairing, "roleCompatibility", TwBreedingConfig.RoleCompatibility.SAME_LIFECYCLE_FAMILY);

        assertTrue(service.canPair("Deer_Stag", "Deer_Doe", config, pairing));
        assertTrue(service.canPair("mods:Deer_Doe", "Deer_Stag", config, pairing));
        assertTrue(service.canPair("Deer_Stag", "Deer_Stag", config, pairing));
        assertFalse(service.canPair("Deer_Stag", "Wolf", config, pairing));
        assertFalse(service.canPair("Deer_Fawn", "Deer_Doe", config, pairing));
    }

    @Test
    void differentFamilyRoleRequiresDifferentAdultRolesInSameFamily() throws Exception {
        TwBreedingConfig config = configWithFamily(
                adultRole("Deer_Stag", 1.0),
                adultRole("Deer_Doe", 1.0)
        );
        TwBreedingConfig.PairingSettings pairing = new TwBreedingConfig.PairingSettings();
        setField(pairing, "roleCompatibility", TwBreedingConfig.RoleCompatibility.DIFFERENT_FAMILY_ROLE);

        assertTrue(service.canPair("Deer_Stag", "Deer_Doe", config, pairing));
        assertTrue(service.canPair("Deer_Doe", "Deer_Stag", config, pairing));
        assertFalse(service.canPair("Deer_Stag", "Deer_Stag", config, pairing));
        assertFalse(service.canPair("Deer_Doe", "mods:Deer_Doe", config, pairing));
        assertFalse(service.canPair("Deer_Stag", "Deer_Fawn", config, pairing));
        assertFalse(service.canPair("Deer_Stag", "Wolf", config, pairing));
    }

    @Test
    void sameRoleRemainsStrictByDefault() throws Exception {
        TwBreedingConfig config = configWithFamily(
                adultRole("Deer_Stag", 1.0),
                adultRole("Deer_Doe", 1.0)
        );
        TwBreedingConfig.PairingSettings pairing = new TwBreedingConfig.PairingSettings();

        assertTrue(service.canPair("Deer_Stag", "deer_stag", config, pairing));
        assertFalse(service.canPair("Deer_Stag", "Deer_Doe", config, pairing));
    }

    @Test
    void anyCompatibilityAllowsDifferentRolesExplicitly() {
        TwBreedingConfig.PairingSettings pairing = new TwBreedingConfig.PairingSettings();
        setFieldUnchecked(pairing, "roleCompatibility", TwBreedingConfig.RoleCompatibility.ANY);

        assertTrue(service.canPair("Deer_Stag", "Wolf", null, pairing));
    }

    private static TwBreedingConfig configWithFamily(TwBreedingConfig.AdultRoleChoice... choices) throws Exception {
        Constructor<TwBreedingConfig> constructor = TwBreedingConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwBreedingConfig config = constructor.newInstance();
        TwBreedingConfig.OffspringLifecycleSettings lifecycle = config.getOffspringLifecycle();
        TwBreedingConfig.RoleFamily family = new TwBreedingConfig.RoleFamily();
        setField(family, "adultRoles", choices);
        setField(family, "babyRoleId", "Deer_Fawn");
        setField(lifecycle, "families", new TwBreedingConfig.RoleFamily[] { family });
        setField(config, "offspringLifecycle", lifecycle);
        return config;
    }

    private static TwBreedingConfig.AdultRoleChoice adultRole(String roleId, double weight) throws Exception {
        TwBreedingConfig.AdultRoleChoice choice = new TwBreedingConfig.AdultRoleChoice();
        setField(choice, "roleId", roleId);
        setField(choice, "weight", weight);
        return choice;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void setFieldUnchecked(Object target, String fieldName, Object value) {
        try {
            setField(target, fieldName, value);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }
}
