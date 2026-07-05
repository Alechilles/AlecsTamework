package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.integration.claims.ClaimIntegrationProvider;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals(TameworkSettingsPreset.SIMPLIFIED, TameworkSettingsPreset.match(presetValues));
        assertEquals(values.simpleClaimsEnabled(), presetValues.simpleClaimsEnabled());
        assertEquals(values.simpleClaimsProvider(), presetValues.simpleClaimsProvider());
    }

    @Test
    void easierAndFullPresetsMatchExpectedProfiles() {
        TameworkSettingsValues easier = TameworkSettingsPreset.EASIER.applyTo(baseValues());
        assertEquals(false, easier.needsDamageEnabled());
        assertEquals(false, easier.needsDamageLethal());
        assertEquals(true, easier.breedingGenderEnabled());
        assertEquals(TameworkSettingsPreset.EASIER, TameworkSettingsPreset.match(easier));

        TameworkSettingsValues full = TameworkSettingsPreset.FULL_EXPERIENCE.applyTo(baseValues());
        assertEquals(true, full.needsDamageEnabled());
        assertEquals(true, full.needsDamageLethal());
        assertEquals(true, full.breedingGenderEnabled());
        assertEquals(true, full.levelingEnabled());
        assertEquals(true, full.talentsEnabled());
        assertEquals(TameworkSettingsPreset.FULL_EXPERIENCE, TameworkSettingsPreset.match(full));
    }

    @Test
    void presetDisplayNamesUseLanguageKeys() throws Exception {
        String content = Files.readString(
                Path.of("src/main/java/com/alechilles/alecstamework/ui/TameworkSettingsPreset.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(content.contains("displayKey"));
        assertTrue(content.contains("LocalizedText.resolve(language"));
        assertFalse(content.contains("\"Simplified (Minecraft-like)\""));
    }

    private static TameworkSettingsValues baseValues() {
        return new TameworkSettingsValues(
                12,
                TwGlobalConfig.PerPlayerLimitScope.PER_WORLD,
                ClaimIntegrationProvider.QUESTLINES_CLAIMS,
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
                "Accurate",
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
                true,
                true,
                false,
                false,
                false
        );
    }
}
