package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.settings.AnimalAgingMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TameworkSettingsPresetTest {

    @Test
    void simplifiedPresetDisablesExperienceSystems() {
        TameworkSettingsValues values = baseValues();

        TameworkSettingsValues presetValues = TameworkSettingsPreset.SIMPLIFIED.applyTo(values);

        assertEquals(false, presetValues.needsEnabled());
        assertEquals(false, presetValues.needsDamageEnabled());
        assertEquals(false, presetValues.needsDamageLethal());
        assertEquals(false, presetValues.happinessEnabled());
        assertEquals(false, presetValues.passiveBreedingEnabled());
        assertEquals(false, presetValues.breedingRequiresHappiness());
        assertEquals(false, presetValues.breedingGenderEnabled());
        assertEquals(false, presetValues.traitsEnabled());
        assertEquals(false, presetValues.levelingEnabled());
        assertEquals(false, presetValues.talentsEnabled());
        assertEquals(AnimalAgingMode.OFF, presetValues.animalAgingMode());
        assertEquals(TameworkSettingsPreset.SIMPLIFIED, TameworkSettingsPreset.match(presetValues));
        assertEquals(values.simpleClaimsEnabled(), presetValues.simpleClaimsEnabled());
        assertEquals(values.needsResourceMode(), presetValues.needsResourceMode());
    }

    @Test
    void easierAndFullPresetsMatchExpectedProfiles() {
        TameworkSettingsValues easier = TameworkSettingsPreset.EASIER.applyTo(baseValues());
        assertEquals(false, easier.needsDamageEnabled());
        assertEquals(false, easier.needsDamageLethal());
        assertEquals(true, easier.breedingGenderEnabled());
        assertEquals(AnimalAgingMode.FREEZE_AT_PRIME, easier.animalAgingMode());
        assertEquals(TameworkSettingsPreset.EASIER, TameworkSettingsPreset.match(easier));

        TameworkSettingsValues full = TameworkSettingsPreset.FULL_EXPERIENCE.applyTo(baseValues());
        assertEquals(true, full.needsDamageEnabled());
        assertEquals(true, full.needsDamageLethal());
        assertEquals(true, full.breedingGenderEnabled());
        assertEquals(true, full.levelingEnabled());
        assertEquals(true, full.talentsEnabled());
        assertEquals(AnimalAgingMode.FULL, full.animalAgingMode());
        assertEquals(TameworkSettingsPreset.FULL_EXPERIENCE, TameworkSettingsPreset.match(full));
    }

    @Test
    void hardcoreEnablesPermanentLossAndHigherPercentageNeedsDamage() {
        TameworkSettingsValues base = baseValues(TwNeedsConfig.DamageModel.MIN_ONLY_FLAT);
        TameworkSettingsValues hardcore = TameworkSettingsPreset.HARDCORE.applyTo(base);

        assertEquals(AnimalAgingMode.FULL, hardcore.animalAgingMode());
        assertEquals(true, hardcore.animalOldAgeDeathEnabled());
        assertEquals(false, hardcore.reviveSystemEnabled());
        assertEquals(true, hardcore.needsDamageEnabled());
        assertEquals(true, hardcore.needsDamageLethal());
        assertEquals(TwNeedsConfig.DamageModel.MIN_ONLY_PERCENT, hardcore.needsDamageModel());
        assertEquals(10.0, hardcore.needsStarvationDamagePerMinute());
        assertEquals(15.0, hardcore.needsDehydrationDamagePerMinute());
        assertEquals(TameworkSettingsPreset.HARDCORE, TameworkSettingsPreset.match(hardcore));
        assertEquals(base.populationLimitPerPlayerOwnedTotal(), hardcore.populationLimitPerPlayerOwnedTotal());
        assertEquals(base.blockOwnerDamage(), hardcore.blockOwnerDamage());
        assertEquals(base.simpleClaimsEnabled(), hardcore.simpleClaimsEnabled());
        assertEquals(false, hardcore.recallTeleportingEnabled());
    }

    @Test
    void leavingHardcoreRestoresRevivesAndNormalNeedsDamageWithoutOldAgeDeath() {
        TameworkSettingsValues hardcore = TameworkSettingsPreset.HARDCORE.applyTo(baseValues());

        for (TameworkSettingsPreset preset : new TameworkSettingsPreset[] {
                TameworkSettingsPreset.SIMPLIFIED, TameworkSettingsPreset.EASIER,
                TameworkSettingsPreset.FULL_EXPERIENCE}) {
            TameworkSettingsValues restored = preset.applyTo(hardcore);
            assertEquals(true, restored.reviveSystemEnabled());
            assertEquals(true, restored.recallTeleportingEnabled());
            assertEquals(false, restored.animalOldAgeDeathEnabled());
            assertEquals(2.0, restored.needsStarvationDamagePerMinute());
            assertEquals(3.0, restored.needsDehydrationDamagePerMinute());
            assertEquals(preset, TameworkSettingsPreset.match(restored));
        }
        assertEquals(hardcore, TameworkSettingsPreset.CUSTOM.applyTo(hardcore));
    }

    private static TameworkSettingsValues baseValues() {
        return baseValues(TwNeedsConfig.DamageModel.MIN_ONLY_PERCENT);
    }

    @Test
    void switchingPresetsResetsProgressionAndDamageRulesRegardlessOfPreviousProfile() {
        TameworkSettingsValues base = baseValues();
        for (TameworkSettingsPreset target : TameworkSettingsPreset.values()) {
            if (!target.isLoadable()) continue;
            TameworkSettingsValues expected = target.applyTo(base);
            assertEquals(TwNeedsConfig.TickPolicyMode.OWNER_ONLINE_GRACE_THEN_DECAY,
                    expected.needsTickPolicyMode());
            assertEquals(72.0, expected.needsOwnerOfflineGraceHours());
            assertEquals(1.0, expected.needsOwnerOfflineDecayMultiplier());
            assertEquals(TwNeedsConfig.DualNeedRule.USE_HIGHER_ONLY, expected.needsDamageDualNeedRule());
            assertEquals(base.needsResourceMode(), expected.needsResourceMode());
            assertEquals(base.telemetryEnabled(), expected.telemetryEnabled());
            assertEquals(base.telemetryBreadcrumbsEnabled(), expected.telemetryBreadcrumbsEnabled());
            for (TameworkSettingsPreset previous : TameworkSettingsPreset.values()) {
                assertEquals(expected, target.applyTo(previous.applyTo(base)),
                        previous + " -> " + target);
            }
        }
    }

    private static TameworkSettingsValues baseValues(TwNeedsConfig.DamageModel damageModel) {
        return new TameworkSettingsValues(
                12,
                TwGlobalConfig.PerPlayerLimitScope.PER_WORLD,
                true,
                3,
                7,
                true,
                true,
                false,
                false,
                false,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                "AlwaysFast",
                true,
                TwNeedsConfig.TickPolicyMode.ANY_LOADED_PLAYER,
                6.0,
                0.25,
                damageModel,
                TwNeedsConfig.DualNeedRule.SUM_BOTH,
                2.0,
                3.0,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                false,
                false,
                AnimalAgingMode.FREEZE_AT_PRIME,
                false
        );
    }
}
