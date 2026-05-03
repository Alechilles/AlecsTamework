package com.alechilles.alecstamework.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkSettingsStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void saveAndLoadGlobalSettingsRoundTripsValues() throws Exception {
        Path tameworkRoot = tempDir.resolve("universe").resolve("Tamework");
        Path settingsFile = TameworkSettingsStore.resolveGlobalSettingsFile(tameworkRoot);

        TameworkSettingsStore.GlobalSettingsSnapshot snapshot = new TameworkSettingsStore.GlobalSettingsSnapshot(
                17,
                "Global",
                true,
                3,
                12,
                true,
                true,
                true,
                false,
                true,
                false,
                true,
                true,
                false,
                false,
                true,
                false,
                "OWNER_ONLINE_GRACE_THEN_DECAY",
                48.0,
                1.25,
                true,
                "MIN_ONLY_PERCENT",
                "SUM_BOTH",
                4.5,
                5.5,
                false,
                true,
                false,
                false,
                true,
                false,
                false
        );

        assertTrue(TameworkSettingsStore.saveGlobalSettings(settingsFile, snapshot, null));

        TameworkSettingsStore.GlobalOverrides overrides = TameworkSettingsStore.loadGlobalOverrides(settingsFile, null);
        assertNotNull(overrides);
        assertEquals(17, overrides.populationLimitPerPlayerOwnedTotal());
        assertEquals("Global", overrides.populationPerPlayerLimitScope());
        assertEquals(true, overrides.simpleClaimsEnabled());
        assertEquals(3, overrides.simpleClaimsLimitPerClaimChunk());
        assertEquals(12, overrides.simpleClaimsLimitPerClaimTotal());
        assertEquals(true, overrides.simpleClaimsBreedingRequiresClaim());
        assertEquals(true, overrides.simpleClaimsProtectTamedFromNonMembers());
        assertEquals(true, overrides.blockOwnerDamage());
        assertEquals(false, overrides.blockAllPlayerDamageIfOwned());
        assertEquals(true, overrides.invulnerableIfOwned());
        assertEquals(false, overrides.captureClearsOwner());
        assertEquals(true, overrides.spawnSetsOwner());
        assertEquals(true, overrides.captureRequiresOwner());
        assertEquals(false, overrides.spawnRequiresOwner());
        assertEquals(false, overrides.interactionRequiresOwner());
        assertEquals(true, overrides.linkingRequiresOwner());
        assertEquals(false, overrides.needsEnabled());
        assertEquals("OWNER_ONLINE_GRACE_THEN_DECAY", overrides.needsTickPolicyMode());
        assertEquals(48.0, overrides.needsOwnerOfflineGraceHours());
        assertEquals(1.25, overrides.needsOwnerOfflineDecayMultiplier());
        assertEquals(true, overrides.needsDamageEnabled());
        assertEquals("MIN_ONLY_PERCENT", overrides.needsDamageModel());
        assertEquals("SUM_BOTH", overrides.needsDamageDualNeedRule());
        assertEquals(4.5, overrides.needsStarvationDamagePerMinute());
        assertEquals(5.5, overrides.needsDehydrationDamagePerMinute());
        assertEquals(false, overrides.needsDamageLethal());
        assertEquals(true, overrides.happinessEnabled());
        assertEquals(false, overrides.passiveBreedingEnabled());
        assertEquals(false, overrides.breedingRequiresHappiness());
        assertEquals(true, overrides.traitsEnabled());
        assertEquals(false, overrides.reviveSystemEnabled());
        assertEquals(false, overrides.recallTeleportingEnabled());

        String raw = Files.readString(settingsFile);
        assertTrue(raw.contains("\"population\""));
        assertTrue(raw.contains("\"simpleClaims\""));
        assertTrue(raw.contains("\"ownership\""));
        assertTrue(raw.contains("\"damageProtection\""));
        assertTrue(raw.contains("\"capture\""));
        assertTrue(raw.contains("\"captureClearsOwner\""));
        assertTrue(raw.contains("\"SpawnSetsOwner\""));
        assertTrue(raw.contains("\"interactionRequiresOwner\""));
        assertTrue(raw.contains("\"linkingRequiresOwner\""));
        assertTrue(raw.contains("\"needs\""));
        assertTrue(raw.contains("\"enabled\""));
        assertTrue(raw.contains("\"happiness\""));
        assertTrue(raw.contains("\"breeding\""));
        assertTrue(raw.contains("\"traits\""));
        assertTrue(raw.contains("\"revive\""));
        assertTrue(raw.contains("\"travel\""));
        assertTrue(raw.contains("\"recallTeleportingEnabled\""));
    }

    @Test
    void loadGlobalOverridesCreatesDefaultDocumentWhenFileMissing() {
        Path tameworkRoot = tempDir.resolve("universe").resolve("Tamework");
        Path settingsFile = TameworkSettingsStore.resolveGlobalSettingsFile(tameworkRoot);

        assertFalse(Files.exists(settingsFile));
        TameworkSettingsStore.GlobalOverrides overrides = TameworkSettingsStore.loadGlobalOverrides(settingsFile, null);
        assertNotNull(overrides);
        assertTrue(Files.isRegularFile(settingsFile));

        assertEquals(0, overrides.populationLimitPerPlayerOwnedTotal());
        assertEquals("PerWorld", overrides.populationPerPlayerLimitScope());
        assertEquals(false, overrides.simpleClaimsEnabled());
        assertEquals(0, overrides.simpleClaimsLimitPerClaimChunk());
        assertEquals(0, overrides.simpleClaimsLimitPerClaimTotal());
        assertEquals(false, overrides.simpleClaimsBreedingRequiresClaim());
        assertEquals(false, overrides.simpleClaimsProtectTamedFromNonMembers());
        assertEquals(false, overrides.blockOwnerDamage());
        assertEquals(false, overrides.blockAllPlayerDamageIfOwned());
        assertEquals(false, overrides.invulnerableIfOwned());
        assertEquals(true, overrides.captureClearsOwner());
        assertEquals(true, overrides.spawnSetsOwner());
        assertEquals(true, overrides.captureRequiresOwner());
        assertEquals(true, overrides.spawnRequiresOwner());
        assertEquals(true, overrides.interactionRequiresOwner());
        assertEquals(true, overrides.linkingRequiresOwner());
        assertEquals(true, overrides.needsEnabled());
        assertEquals("OWNER_ONLINE_GRACE_THEN_DECAY", overrides.needsTickPolicyMode());
        assertEquals(72.0, overrides.needsOwnerOfflineGraceHours());
        assertEquals(1.0, overrides.needsOwnerOfflineDecayMultiplier());
        assertEquals(true, overrides.needsDamageEnabled());
        assertEquals("MIN_ONLY_PERCENT", overrides.needsDamageModel());
        assertEquals("USE_HIGHER_ONLY", overrides.needsDamageDualNeedRule());
        assertEquals(2.0, overrides.needsStarvationDamagePerMinute());
        assertEquals(3.0, overrides.needsDehydrationDamagePerMinute());
        assertEquals(true, overrides.needsDamageLethal());
        assertEquals(true, overrides.happinessEnabled());
        assertEquals(true, overrides.passiveBreedingEnabled());
        assertEquals(true, overrides.breedingRequiresHappiness());
        assertEquals(true, overrides.traitsEnabled());
        assertEquals(true, overrides.reviveSystemEnabled());
        assertEquals(true, overrides.recallTeleportingEnabled());
    }
}
