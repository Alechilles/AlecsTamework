package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.persistence.TameworkSettingsStore;
import com.alechilles.alecstamework.settings.ResolvedTameworkSettings;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests disposition scaling behavior for happiness equilibrium modifiers.
 */
class CompanionHappinessModifierServiceTest {

    @Test
    void applyDispositionToOffsetScalesPositiveGainsDirectly() {
        double adjusted = CompanionHappinessModifierService.applyDispositionToOffset(10.0, 1.2);
        assertEquals(12.0, adjusted, 0.000001);
    }

    @Test
    void applyDispositionToOffsetSoftensDetractorsWhenDispositionIsHigh() {
        double adjusted = CompanionHappinessModifierService.applyDispositionToOffset(-10.0, 1.2);
        assertEquals(-8.333333, adjusted, 0.000001);
    }

    @Test
    void applyDispositionToOffsetAmplifiesDetractorsWhenDispositionIsLow() {
        double adjusted = CompanionHappinessModifierService.applyDispositionToOffset(-10.0, 0.8);
        assertEquals(-12.5, adjusted, 0.000001);
    }

    @Test
    void applyDispositionToOffsetFallsBackToNeutralForInvalidMultiplier() {
        double adjusted = CompanionHappinessModifierService.applyDispositionToOffset(10.0, -1.0);
        assertEquals(10.0, adjusted, 0.000001);
    }

    @Test
    void runtimeHappinessGateRequiresEnabledConfigAndEnabledSettings() throws Exception {
        TwHappinessConfig enabledConfig = happinessConfig(true);
        TwHappinessConfig disabledConfig = happinessConfig(false);
        TameworkRuntimeSettings enabledSettings = TameworkRuntimeSettings.from(settingsWithHappinessEnabled(true));
        TameworkRuntimeSettings disabledSettings = TameworkRuntimeSettings.from(settingsWithHappinessEnabled(false));

        assertTrue(HappinessConfigResolver.isRuntimeEnabled(enabledConfig, enabledSettings));
        assertFalse(HappinessConfigResolver.isRuntimeEnabled(enabledConfig, disabledSettings));
        assertFalse(HappinessConfigResolver.isRuntimeEnabled(disabledConfig, enabledSettings));
        assertFalse(HappinessConfigResolver.isRuntimeEnabled(null, enabledSettings));
    }

    private static TwHappinessConfig happinessConfig(boolean enabled) throws Exception {
        var ctor = TwHappinessConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        TwHappinessConfig config = ctor.newInstance();
        setField(config, "enabled", enabled);
        return config;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static ResolvedTameworkSettings settingsWithHappinessEnabled(boolean enabled) {
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
                enabled,
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

