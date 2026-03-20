package com.alechilles.alecstamework.config.assets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwCompanionConfigTravelSettingsTest {

    @Test
    void effectiveDefaultsAllowConfiguredWorldChangeStates() {
        TwCompanionConfig.EffectiveSettings settings = TwCompanionConfig.EffectiveSettings.fromGlobal(null);

        assertTrue(settings.isFollowMasterOnWorldChange());
        assertTrue(settings.isCrossWorldRecallEnabled());
        assertTrue(settings.isWorldChangeStateAllowed("Follow"));
        assertTrue(settings.isWorldChangeStateAllowed("defend.default"));
        assertTrue(settings.isWorldChangeStateAllowed("Aggressive"));
        assertTrue(settings.isWorldChangeStateAllowed("Tamework.Instruction.Follow.Default"));
        assertTrue(settings.isWorldChangeStateAllowed("Tamework.Instruction.Defend.Combat"));
        assertTrue(settings.isWorldChangeStateAllowed("AggressiveDefault"));
        assertFalse(settings.isWorldChangeStateAllowed("Hold"));
        assertFalse(settings.isWorldChangeStateAllowed("Tamework.Instruction.Hold.Default"));
    }
}
