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

    private static ResolvedTameworkSettings settingsWithBreedingGenderEnabled(boolean enabled) {
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
                enabled,
                defaults.traitsEnabled(),
                defaults.reviveSystemEnabled(),
                defaults.recallTeleportingEnabled(),
                defaults.telemetryEnabled(),
                defaults.telemetryBreadcrumbsEnabled()
        );
    }
}
