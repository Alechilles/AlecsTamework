package com.alechilles.alecstamework.avatarflight;

import com.hypixel.hytale.protocol.AnimationSlot;
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

    @Test
    void movementCueOwnsOnlyMovementUntilItsDeadline() {
        AvatarFlightComponent flight = new AvatarFlightComponent();
        flight.setAbilityAnimationId("Dragon_Boost");
        flight.setAbilityAnimationSlot("Movement");
        flight.setAbilityAnimationUntilMs(1500L);

        assertTrue(AvatarFlightAnimationService.doesAbilityOwnSlot(
                flight, AnimationSlot.Movement, 1499L));
        assertFalse(AvatarFlightAnimationService.doesAbilityOwnSlot(
                flight, AnimationSlot.Action, 1499L));
        assertFalse(AvatarFlightAnimationService.doesAbilityOwnSlot(
                flight, AnimationSlot.Movement, 1500L));
    }

    @Test
    void groundedIdleHandoffRequiresAnOwnedFlightAnimation() {
        assertTrue(AvatarFlightAnimationService.needsGroundedIdleHandoff(
                AvatarFlightMode.GROUNDED, true));
        assertFalse(AvatarFlightAnimationService.needsGroundedIdleHandoff(
                AvatarFlightMode.GROUNDED, false));
        assertFalse(AvatarFlightAnimationService.needsGroundedIdleHandoff(
                AvatarFlightMode.FORWARD_FLIGHT, true));
    }

    @Test
    void groundedIdleHandoffRemainsTrackedUntilMovementReleasesIt() {
        AvatarFlightComponent flight = new AvatarFlightComponent();

        assertFalse(AvatarFlightAnimationService.isGroundedIdleHandoffActive(flight));

        flight.setMovementAnimationId("Idle");

        assertTrue(AvatarFlightAnimationService.isGroundedIdleHandoffActive(flight));
    }
}
