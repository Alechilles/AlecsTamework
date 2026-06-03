package com.alechilles.alecstamework.persistence;

import com.alechilles.alecstamework.settings.ResolvedTameworkSettings;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
                false,
                true,
                false,
                true,
                false,
                false,
                true,
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
        assertEquals(false, overrides.breedingGenderEnabled());
        assertEquals(true, overrides.traitsEnabled());
        assertEquals(false, overrides.levelingEnabled());
        assertEquals(true, overrides.talentsEnabled());
        assertEquals(false, overrides.reviveSystemEnabled());
        assertEquals(false, overrides.recallTeleportingEnabled());
        assertEquals(true, overrides.telemetryEnabled());
        assertEquals(false, overrides.telemetryBreadcrumbsEnabled());

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
        assertTrue(raw.contains("\"genderEnabled\""));
        assertTrue(raw.contains("\"traits\""));
        assertTrue(raw.contains("\"progression\""));
        assertTrue(raw.contains("\"levelingEnabled\""));
        assertTrue(raw.contains("\"talentsEnabled\""));
        assertTrue(raw.contains("\"revive\""));
        assertTrue(raw.contains("\"travel\""));
        assertTrue(raw.contains("\"recallTeleportingEnabled\""));
        assertTrue(raw.contains("\"telemetry\""));
        assertTrue(raw.contains("\"breadcrumbsEnabled\""));
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
        assertEquals(true, overrides.breedingGenderEnabled());
        assertEquals(true, overrides.traitsEnabled());
        assertEquals(true, overrides.levelingEnabled());
        assertEquals(true, overrides.talentsEnabled());
        assertEquals(true, overrides.reviveSystemEnabled());
        assertEquals(true, overrides.recallTeleportingEnabled());
        assertEquals(true, overrides.telemetryEnabled());
        assertEquals(true, overrides.telemetryBreadcrumbsEnabled());
    }

    @Test
    void loadGlobalSettingsCreatesCompleteResolvedDocumentWhenFileMissing() {
        Path tameworkRoot = tempDir.resolve("universe").resolve("Tamework");
        Path settingsFile = TameworkSettingsStore.resolveGlobalSettingsFile(tameworkRoot);

        assertFalse(Files.exists(settingsFile));
        ResolvedTameworkSettings settings =
                TameworkSettingsStore.loadGlobalSettings(settingsFile, null);

        assertTrue(Files.isRegularFile(settingsFile));
        assertEquals(0, settings.populationLimitPerPlayerOwnedTotal());
        assertEquals("PerWorld", settings.populationPerPlayerLimitScope());
        assertEquals(false, settings.simpleClaimsEnabled());
        assertEquals(true, settings.captureClearsOwner());
        assertEquals(true, settings.spawnSetsOwner());
        assertEquals(true, settings.captureRequiresOwner());
        assertEquals(true, settings.spawnRequiresOwner());
        assertEquals(true, settings.interactionRequiresOwner());
        assertEquals(true, settings.linkingRequiresOwner());
        assertEquals(true, settings.needsEnabled());
        assertEquals("OWNER_ONLINE_GRACE_THEN_DECAY", settings.needsTickPolicyMode());
        assertEquals(72.0, settings.needsOwnerOfflineGraceHours());
        assertEquals(true, settings.needsDamageEnabled());
        assertEquals("MIN_ONLY_PERCENT", settings.needsDamageModel());
        assertEquals("USE_HIGHER_ONLY", settings.needsDamageDualNeedRule());
        assertEquals(true, settings.happinessEnabled());
        assertEquals(true, settings.passiveBreedingEnabled());
        assertEquals(true, settings.breedingRequiresHappiness());
        assertEquals(true, settings.breedingGenderEnabled());
        assertEquals(true, settings.traitsEnabled());
        assertEquals(true, settings.reviveSystemEnabled());
        assertEquals(true, settings.recallTeleportingEnabled());
        assertEquals(true, settings.telemetryEnabled());
        assertEquals(true, settings.telemetryBreadcrumbsEnabled());
    }

    @Test
    void importsLegacyTelemetryJsonIntoGlobalSettings() throws Exception {
        Path settingsFile = tempDir.resolve("universe").resolve("Tamework").resolve("Settings").resolve("tamework-settings.json");
        Path legacy = tempDir.resolve("plugin-data").resolve("crash-telemetry.json");
        Files.createDirectories(legacy.getParent());
        Files.writeString(
                legacy,
                """
                {
                  "enabled": false,
                  "breadcrumbsEnabled": false
                }
                """,
                StandardCharsets.UTF_8
        );

        assertTrue(TameworkSettingsStore.importLegacyTelemetrySettingsIfMissing(settingsFile, List.of(legacy), null));
        TameworkSettingsStore.GlobalOverrides overrides = TameworkSettingsStore.loadGlobalOverrides(settingsFile, null);

        assertNotNull(overrides);
        assertEquals(0, overrides.populationLimitPerPlayerOwnedTotal());
        assertEquals("PerWorld", overrides.populationPerPlayerLimitScope());
        assertEquals(false, overrides.simpleClaimsEnabled());
        assertEquals(true, overrides.captureRequiresOwner());
        assertEquals(true, overrides.spawnRequiresOwner());
        assertEquals(true, overrides.interactionRequiresOwner());
        assertEquals(true, overrides.linkingRequiresOwner());
        assertEquals(true, overrides.needsEnabled());
        assertEquals(true, overrides.happinessEnabled());
        assertEquals(true, overrides.passiveBreedingEnabled());
        assertEquals(true, overrides.breedingGenderEnabled());
        assertEquals(true, overrides.traitsEnabled());
        assertEquals(false, overrides.telemetryEnabled());
        assertEquals(false, overrides.telemetryBreadcrumbsEnabled());
        assertTrue(Files.readString(settingsFile, StandardCharsets.UTF_8).contains("\"telemetry\""));
    }

    @Test
    void importsLegacyTelemetryTextIntoGlobalSettings() throws Exception {
        Path settingsFile = tempDir.resolve("universe").resolve("Tamework").resolve("Settings").resolve("tamework-settings.json");
        Path legacy = tempDir.resolve("plugin-data").resolve("tamework-crash-telemetry.txt");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "enabled=0\nbreadcrumbs_enabled=off", StandardCharsets.UTF_8);

        assertTrue(TameworkSettingsStore.importLegacyTelemetrySettingsIfMissing(settingsFile, List.of(legacy), null));
        TameworkSettingsStore.GlobalOverrides overrides = TameworkSettingsStore.loadGlobalOverrides(settingsFile, null);

        assertNotNull(overrides);
        assertEquals(false, overrides.telemetryEnabled());
        assertEquals(false, overrides.telemetryBreadcrumbsEnabled());
    }

    @Test
    void existingGlobalTelemetrySettingsWinOverLegacyFiles() throws Exception {
        Path settingsFile = tempDir.resolve("universe").resolve("Tamework").resolve("Settings").resolve("tamework-settings.json");
        Path legacy = tempDir.resolve("plugin-data").resolve("crash-telemetry.json");
        Files.createDirectories(settingsFile.getParent());
        Files.createDirectories(legacy.getParent());
        Files.writeString(
                settingsFile,
                """
                {
                  "version": 1,
                  "telemetry": {
                    "enabled": true,
                    "breadcrumbsEnabled": false
                  }
                }
                """,
                StandardCharsets.UTF_8
        );
        Files.writeString(legacy, "{\"enabled\": false, \"breadcrumbsEnabled\": true}", StandardCharsets.UTF_8);

        assertTrue(TameworkSettingsStore.importLegacyTelemetrySettingsIfMissing(settingsFile, List.of(legacy), null));
        TameworkSettingsStore.GlobalOverrides overrides = TameworkSettingsStore.loadGlobalOverrides(settingsFile, null);

        assertNotNull(overrides);
        assertEquals(true, overrides.telemetryEnabled());
        assertEquals(false, overrides.telemetryBreadcrumbsEnabled());
    }
}
