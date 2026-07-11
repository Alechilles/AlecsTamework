package com.alechilles.alecstamework.persistence;

import com.alechilles.alecstamework.integration.claims.ClaimIntegrationProvider;
import com.alechilles.alecstamework.settings.ResolvedTameworkSettings;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkSettingsProviderValidationTest {
    @TempDir
    Path tempDir;

    @Test
    void settingsSaveRejectsUnknownExplicitProvider() {
        Path settingsFile = tempDir.resolve("tamework-settings.json");

        assertFalse(TameworkSettingsStore.saveGlobalSettings(settingsFile, snapshot("TownyMaybe"), null));
        assertFalse(Files.exists(settingsFile));
    }

    @Test
    void legacyDecodePreservesInvalidProviderAndSurfacesInvalidRequest() throws Exception {
        Path settingsFile = tempDir.resolve("tamework-settings.json");
        Files.writeString(
                settingsFile,
                """
                {
                  "version": 1,
                  "simpleClaims": {
                    "provider": "TownyMaybe",
                    "simpleClaimsEnabled": true,
                    "limitPerClaimTotal": 4
                  }
                }
                """,
                StandardCharsets.UTF_8
        );

        ResolvedTameworkSettings resolved = TameworkSettingsStore.loadGlobalSettings(settingsFile, null);

        assertEquals("TownyMaybe", resolved.simpleClaimsProvider());
        assertFalse(resolved.simpleClaimsProviderRequest().valid());
        assertFalse(TameworkRuntimeSettings.from(resolved).simpleClaimsProviderRequest().valid());
        assertEquals(
                ClaimIntegrationProvider.OFF,
                TameworkRuntimeSettings.from(resolved).simpleClaimsProvider(),
                "The legacy enum view must fail closed instead of silently selecting Auto."
        );
        assertEquals(
                "Invalid claim provider at simpleClaims.provider: 'TownyMaybe'.",
                resolved.simpleClaimsProviderRequest().invalidDiagnostic("simpleClaims.provider")
        );
    }

    @Test
    void legacyDecodeCanonicalizesAcceptedAliasButBlankStillDefaultsToAuto() throws Exception {
        Path settingsFile = tempDir.resolve("tamework-settings.json");
        Files.writeString(settingsFile, "{\"simpleClaims\":{\"provider\":\"qlc\"}}", StandardCharsets.UTF_8);
        assertEquals(
                ClaimIntegrationProvider.QUESTLINES_CLAIMS.configValue(),
                TameworkSettingsStore.loadGlobalSettings(settingsFile, null).simpleClaimsProvider()
        );

        Files.writeString(settingsFile, "{\"simpleClaims\":{\"provider\":\"  \"}}", StandardCharsets.UTF_8);
        TameworkSettingsStore.invalidateRuntimeGlobalOverridesCache();
        assertEquals(
                ClaimIntegrationProvider.AUTO.configValue(),
                TameworkSettingsStore.loadGlobalSettings(settingsFile, null).simpleClaimsProvider()
        );
    }

    private static TameworkSettingsStore.GlobalSettingsSnapshot snapshot(String provider) {
        ResolvedTameworkSettings defaults = TameworkSettingsStore.defaultGlobalSettings();
        return new TameworkSettingsStore.GlobalSettingsSnapshot(
                defaults.populationLimitPerPlayerOwnedTotal(),
                defaults.populationPerPlayerLimitScope(),
                provider,
                defaults.simpleClaimsEnabled(),
                defaults.simpleClaimsLimitPerClaimChunk(),
                defaults.simpleClaimsLimitPerClaimTotal(),
                defaults.simpleClaimsBreedingRequiresClaim(),
                defaults.simpleClaimsProtectTamedFromNonMembers(),
                defaults.blockOwnerDamage(),
                defaults.blockAllPlayerDamageIfOwned(),
                defaults.invulnerableIfOwned(),
                defaults.captureClearsOwner(),
                defaults.spawnSetsOwner(),
                defaults.captureRequiresOwner(),
                defaults.spawnRequiresOwner(),
                defaults.interactionRequiresOwner(),
                defaults.linkingRequiresOwner(),
                defaults.needsEnabled(),
                defaults.needsResourceMode(),
                defaults.needsTickPolicyMode(),
                defaults.needsOwnerOfflineGraceHours(),
                defaults.needsOwnerOfflineDecayMultiplier(),
                defaults.needsDamageEnabled(),
                defaults.needsDamageModel(),
                defaults.needsDamageDualNeedRule(),
                defaults.needsStarvationDamagePerMinute(),
                defaults.needsDehydrationDamagePerMinute(),
                defaults.needsDamageLethal(),
                defaults.happinessEnabled(),
                defaults.passiveBreedingEnabled(),
                defaults.breedingRequiresHappiness(),
                defaults.breedingGenderEnabled(),
                defaults.traitsEnabled(),
                defaults.levelingEnabled(),
                defaults.talentsEnabled(),
                defaults.reviveSystemEnabled(),
                defaults.recallTeleportingEnabled(),
                defaults.telemetryEnabled(),
                defaults.telemetryBreadcrumbsEnabled()
        );
    }
}
