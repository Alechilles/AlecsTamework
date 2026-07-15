package com.alechilles.alecstamework.avatarflight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvatarFlightAnimationServiceTest {
    @Test
    void actionSlotProtectionExpiresAtConfiguredDeadline() {
        AvatarFlightComponent flight = new AvatarFlightComponent();
        flight.setAbilityAnimationId("Dragon_Flap");
        flight.setAbilityAnimationUntilMs(1500L);

        assertTrue(AvatarFlightAnimationService.isAbilityAnimationProtected(flight, 1499L));
        assertFalse(AvatarFlightAnimationService.isAbilityAnimationProtected(flight, 1500L));
    }

    @Test
    void blankCueNeverProtectsActionSlot() {
        AvatarFlightComponent flight = new AvatarFlightComponent();
        flight.setAbilityAnimationUntilMs(Long.MAX_VALUE);

        assertFalse(AvatarFlightAnimationService.isAbilityAnimationProtected(flight, 1000L));
    }
}
