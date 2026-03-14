package com.alechilles.alecstamework.config.assets;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tests role-scoped MaxNearbySameType resolution on breeding pairing settings. */
class TwBreedingConfigPairingSettingsTest {

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
}
