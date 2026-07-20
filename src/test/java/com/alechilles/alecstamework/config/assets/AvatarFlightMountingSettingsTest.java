package com.alechilles.alecstamework.config.assets;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers nested parent fallback for NPC-backed avatar-flight mounting settings. */
class AvatarFlightMountingSettingsTest {
    @Test
    void defaultValuesUseGroundedBackCrouchSafety() {
        AvatarFlightMountingSettings settings = new AvatarFlightMountingSettings();

        assertEquals(750L, settings.getDismountHoldMs());
        assertTrue(settings.isRequireGroundedDismount());
        assertTrue(settings.isRestoreNpcAtLastSafeGround());
        assertEquals(1.75, settings.getPlayerDismountOffset(), 0.00001);
    }

    @Test
    void explicitNestedKeyWinsAndMissingKeysInherit() {
        AvatarFlightMountingSettings parent = new AvatarFlightMountingSettings();
        parent.dismountHoldMs = 1_200.0;
        parent.requireGroundedDismount = false;
        parent.restoreNpcAtLastSafeGround = false;
        parent.playerDismountOffset = 3.0;
        AvatarFlightMountingSettings child = new AvatarFlightMountingSettings();
        child.dismountHoldMs = 500.0;

        child.inheritMissingFrom(parent, Set.of("DismountHoldMs"));

        assertEquals(500L, child.getDismountHoldMs());
        assertFalse(child.isRequireGroundedDismount());
        assertFalse(child.isRestoreNpcAtLastSafeGround());
        assertEquals(3.0, child.getPlayerDismountOffset(), 0.00001);
    }

    @Test
    void avatarConfigTracksTopLevelAndNestedMountingOverrides() {
        TwAvatarFlightConfig parent = new TwAvatarFlightConfig();
        parent.mounting.dismountHoldMs = 1_200.0;
        parent.mounting.requireGroundedDismount = false;
        parent.mounting.playerDismountOffset = 3.0;
        TwAvatarFlightConfig child = new TwAvatarFlightConfig();
        child.mounting.dismountHoldMs = 500.0;

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("Mounting"),
                Map.of("Mounting", Set.of("DismountHoldMs"))
        );

        assertEquals(500L, child.getMounting().getDismountHoldMs());
        assertFalse(child.getMounting().isRequireGroundedDismount());
        assertEquals(3.0, child.getMounting().getPlayerDismountOffset(), 0.00001);
    }
}
