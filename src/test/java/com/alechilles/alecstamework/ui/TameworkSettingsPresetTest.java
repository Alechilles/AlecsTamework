package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
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
        assertEquals(false, presetValues.traitsEnabled());
        assertEquals(TameworkSettingsPreset.SIMPLIFIED, TameworkSettingsPreset.match(presetValues));
        assertEquals(values.simpleClaimsEnabled(), presetValues.simpleClaimsEnabled());
    }

    @Test
    void easierAndFullPresetsMatchExpectedProfiles() {
        TameworkSettingsValues easier = TameworkSettingsPreset.EASIER.applyTo(baseValues());
        assertEquals(false, easier.needsDamageEnabled());
        assertEquals(false, easier.needsDamageLethal());
        assertEquals(TameworkSettingsPreset.EASIER, TameworkSettingsPreset.match(easier));

        TameworkSettingsValues full = TameworkSettingsPreset.FULL_EXPERIENCE.applyTo(baseValues());
        assertEquals(true, full.needsDamageEnabled());
        assertEquals(true, full.needsDamageLethal());
        assertEquals(TameworkSettingsPreset.FULL_EXPERIENCE, TameworkSettingsPreset.match(full));
    }

    private static TameworkSettingsValues baseValues() {
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
                true,
                TwNeedsConfig.TickPolicyMode.OWNER_ONLINE_GRACE_THEN_DECAY,
                72.0,
                1.0,
                TwNeedsConfig.DamageModel.MIN_ONLY_PERCENT,
                TwNeedsConfig.DualNeedRule.USE_HIGHER_ONLY,
                2.0,
                3.0,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                false,
                false
        );
    }
}
