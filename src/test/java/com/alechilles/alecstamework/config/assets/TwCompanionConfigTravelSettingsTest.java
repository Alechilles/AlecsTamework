package com.alechilles.alecstamework.config.assets;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwCompanionConfigTravelSettingsTest {

    @Test
    void effectiveDefaultsDisableAutomaticWorldChangeFollowing() {
        TwCompanionConfig.EffectiveSettings settings = TwCompanionConfig.EffectiveSettings.fromGlobal(null);

        assertFalse(settings.isFollowMasterOnWorldChange());
        assertTrue(settings.isCrossWorldRecallEnabled());
        assertFalse(settings.isWorldChangeStateAllowed("Follow"));
        assertFalse(settings.isWorldChangeStateAllowed("defend.default"));
        assertFalse(settings.isWorldChangeStateAllowed("Aggressive"));
        assertFalse(settings.isWorldChangeStateAllowed("Tamework.Instruction.Follow.Default"));
        assertFalse(settings.isWorldChangeStateAllowed("Tamework.Instruction.Defend.Combat"));
        assertFalse(settings.isWorldChangeStateAllowed("AggressiveDefault"));
        assertFalse(settings.isWorldChangeStateAllowed("Hold"));
        assertFalse(settings.isWorldChangeStateAllowed("Tamework.Instruction.Hold.Default"));
    }

    @Test
    void roleConfigCanExplicitlyOptIntoAutomaticWorldChangeFollowing() throws Exception {
        TwCompanionConfig scoped = new TwCompanionConfig();
        Field commandField = TwCompanionConfig.class.getDeclaredField("command");
        commandField.setAccessible(true);
        Object command = commandField.get(scoped);
        Field travelField = command.getClass().getDeclaredField("travel");
        travelField.setAccessible(true);
        Object travel = travelField.get(command);
        Field followField = travel.getClass().getDeclaredField("followMasterOnWorldChange");
        followField.setAccessible(true);
        followField.setBoolean(travel, true);

        TwCompanionConfig.EffectiveSettings settings = TwCompanionConfig.EffectiveSettings.from(scoped, null);

        assertTrue(settings.isFollowMasterOnWorldChange());
        assertTrue(settings.isWorldChangeStateAllowed("Follow"));
        assertFalse(settings.isWorldChangeStateAllowed("Hold"));
    }
}
