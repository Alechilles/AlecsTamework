package com.alechilles.alecstamework.settings;

import com.alechilles.alecstamework.persistence.TameworkSettingsStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkRuntimeSettingsTest {

    @Test
    void breedingGenderSettingGatesButDoesNotForceConfigGender() {
        TameworkRuntimeSettings enabledSettings =
                TameworkRuntimeSettings.from(TameworkSettingsStore.defaultGlobalSettings());
        assertTrue(enabledSettings.breedingGenderEnabledForConfig(true));
        assertFalse(enabledSettings.breedingGenderEnabledForConfig(false));

        TameworkRuntimeSettings disabledSettings =
                TameworkRuntimeSettings.from(settingsWithBreedingGenderEnabled(false));
        assertFalse(disabledSettings.breedingGenderEnabledForConfig(true));
        assertFalse(disabledSettings.breedingGenderEnabledForConfig(false));
    }

    @Test
    void levelingAndTalentSettingsUseResolvedSettings() {
        TameworkRuntimeSettings disabledSettings =
                TameworkRuntimeSettings.from(settingsWithProgressionToggles(false, false));
        assertFalse(disabledSettings.levelingEnabled());
        assertFalse(disabledSettings.talentsEnabled());

        TameworkRuntimeSettings enabledSettings =
                TameworkRuntimeSettings.from(settingsWithProgressionToggles(true, true));
        assertTrue(enabledSettings.levelingEnabled());
        assertTrue(enabledSettings.talentsEnabled());
    }

    private static ResolvedTameworkSettings settingsWithBreedingGenderEnabled(boolean enabled) {
        ResolvedTameworkSettings defaults = TameworkSettingsStore.defaultGlobalSettings();
        return settingsWithProgressionSettings(
                enabled,
                defaults.levelingEnabled(),
                defaults.talentsEnabled()
        );
    }

    private static ResolvedTameworkSettings settingsWithProgressionToggles(boolean levelingEnabled,
                                                                          boolean talentsEnabled) {
        ResolvedTameworkSettings defaults = TameworkSettingsStore.defaultGlobalSettings();
        return settingsWithProgressionSettings(
                defaults.breedingGenderEnabled(),
                levelingEnabled,
                talentsEnabled
        );
    }

    private static ResolvedTameworkSettings settingsWithProgressionSettings(boolean breedingGenderEnabled,
                                                                           boolean levelingEnabled,
                                                                           boolean talentsEnabled) {
        ResolvedTameworkSettings defaults = TameworkSettingsStore.defaultGlobalSettings();
        return new ResolvedTameworkSettings(
                defaults.populationLimitPerPlayerOwnedTotal(),
                defaults.populationPerPlayerLimitScope(),
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
                breedingGenderEnabled,
                defaults.traitsEnabled(),
                levelingEnabled,
                talentsEnabled,
                defaults.reviveSystemEnabled(),
                defaults.recallTeleportingEnabled(),
                defaults.telemetryEnabled(),
                defaults.telemetryBreadcrumbsEnabled()
        );
    }
}
