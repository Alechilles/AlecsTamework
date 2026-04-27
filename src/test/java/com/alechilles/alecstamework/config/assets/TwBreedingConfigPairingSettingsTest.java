package com.alechilles.alecstamework.config.assets;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests role-scoped MaxNearbySameType resolution on breeding pairing settings. */
class TwBreedingConfigPairingSettingsTest {
    @Test
    void roleCompatibilityDefaultsFromLegacySameRoleToggle() throws Exception {
        TwBreedingConfig.PairingSettings defaultPairing = new TwBreedingConfig.PairingSettings();
        assertEquals(TwBreedingConfig.RoleCompatibility.SAME_ROLE, defaultPairing.getRoleCompatibility());

        TwBreedingConfig.PairingSettings broadPairing = new TwBreedingConfig.PairingSettings();
        setField(broadPairing, "requireSameRoleId", false);
        assertEquals(TwBreedingConfig.RoleCompatibility.ANY, broadPairing.getRoleCompatibility());

        TwBreedingConfig.PairingSettings explicitPairing = new TwBreedingConfig.PairingSettings();
        setField(explicitPairing, "requireSameRoleId", true);
        setField(explicitPairing, "roleCompatibility", TwBreedingConfig.RoleCompatibility.SAME_LIFECYCLE_FAMILY);
        assertEquals(TwBreedingConfig.RoleCompatibility.SAME_LIFECYCLE_FAMILY, explicitPairing.getRoleCompatibility());
    }

    @Test
    void roleCompatibilityParsesConfigValues() {
        assertEquals(
                TwBreedingConfig.RoleCompatibility.SAME_LIFECYCLE_FAMILY,
                TwBreedingConfig.RoleCompatibility.fromConfigValue("SameLifecycleFamily", null)
        );
        assertEquals(
                TwBreedingConfig.RoleCompatibility.SAME_ROLE,
                TwBreedingConfig.RoleCompatibility.fromConfigValue("same_role", null)
        );
        assertEquals(
                TwBreedingConfig.RoleCompatibility.ANY,
                TwBreedingConfig.RoleCompatibility.fromConfigValue("Any", TwBreedingConfig.RoleCompatibility.SAME_ROLE)
        );
        assertEquals(
                TwBreedingConfig.RoleCompatibility.DIFFERENT_FAMILY_ROLE,
                TwBreedingConfig.RoleCompatibility.fromConfigValue("different_family_role", null)
        );
        assertEquals(
                TwBreedingConfig.RoleCompatibility.DIFFERENT_FAMILY_ROLE,
                TwBreedingConfig.RoleCompatibility.fromConfigValue("DifferentFamilyRole", null)
        );
    }

    @Test
    void familyMatchesWeightedAdultRolesAndPreservesLegacyAdultFallback() throws Exception {
        TwBreedingConfig.RoleFamily family = new TwBreedingConfig.RoleFamily();
        setField(family, "adultRoleId", "Legacy_Deer");
        setField(family, "babyRoleId", "Deer_Fawn");
        setField(family, "adultRoles", new TwBreedingConfig.AdultRoleChoice[] {
                adultRole("Deer_Stag", 1.0),
                adultRole("Deer_Doe", 1.0)
        });

        assertTrue(family.matchesAdultRole("deer_stag"));
        assertTrue(family.matchesAdultRole("mods:Deer_Doe"));
        assertTrue(family.matchesRole("Deer_Fawn"));
        assertFalse(family.matchesAdultRole("Wolf"));
        assertEquals("Deer_Stag", family.getAdultRoleId());

        TwBreedingConfig.RoleFamily legacyFamily = new TwBreedingConfig.RoleFamily();
        setField(legacyFamily, "adultRoleId", "Legacy_Deer");
        assertEquals("Legacy_Deer", legacyFamily.getAdultRoleId());
        assertTrue(legacyFamily.matchesAdultRole("legacy_deer"));
    }

    @Test
    void resolveMaxNearbySameTypeFallsBackToBaseWhenNoRoleOverrideMatches() throws Exception {
        TwBreedingConfig.PairingSettings pairing = new TwBreedingConfig.PairingSettings();
        setField(pairing, "maxNearbySameType", 8);

        TwBreedingConfig.RoleMaxNearbySameTypeOverride override =
                new TwBreedingConfig.RoleMaxNearbySameTypeOverride();
        setField(override, "roleId", "Tamed_Wolf_Black");
        setField(override, "maxNearbySameType", 5);
        setField(pairing, "roleMaxNearbySameType", new TwBreedingConfig.RoleMaxNearbySameTypeOverride[] { override });

        assertEquals(8, pairing.resolveMaxNearbySameType("Tamed_Rat"));
    }

    @Test
    void resolveMaxNearbySameTypeUsesRoleOverrideCaseAndNamespaceInsensitive() throws Exception {
        TwBreedingConfig.PairingSettings pairing = new TwBreedingConfig.PairingSettings();
        setField(pairing, "maxNearbySameType", 8);

        TwBreedingConfig.RoleMaxNearbySameTypeOverride override =
                new TwBreedingConfig.RoleMaxNearbySameTypeOverride();
        setField(override, "roleId", "Tamed_Wolf_Black");
        setField(override, "maxNearbySameType", 5);
        setField(pairing, "roleMaxNearbySameType", new TwBreedingConfig.RoleMaxNearbySameTypeOverride[] { override });

        assertEquals(5, pairing.resolveMaxNearbySameType("tamed_wolf_black"));
        assertEquals(5, pairing.resolveMaxNearbySameType("mods:Tamed_Wolf_Black"));
    }

    @Test
    void resolveMaxNearbySameTypeClampsNegativeOverrideToZero() throws Exception {
        TwBreedingConfig.PairingSettings pairing = new TwBreedingConfig.PairingSettings();
        setField(pairing, "maxNearbySameType", 8);

        TwBreedingConfig.RoleMaxNearbySameTypeOverride override =
                new TwBreedingConfig.RoleMaxNearbySameTypeOverride();
        setField(override, "roleId", "Tamed_Rat");
        setField(override, "maxNearbySameType", -3);
        setField(pairing, "roleMaxNearbySameType", new TwBreedingConfig.RoleMaxNearbySameTypeOverride[] { override });

        assertEquals(0, pairing.resolveMaxNearbySameType("Tamed_Rat"));
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static TwBreedingConfig.AdultRoleChoice adultRole(String roleId, double weight) throws Exception {
        TwBreedingConfig.AdultRoleChoice choice = new TwBreedingConfig.AdultRoleChoice();
        setField(choice, "roleId", roleId);
        setField(choice, "weight", weight);
        return choice;
    }
}
