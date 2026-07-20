package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.AvatarFlightMountingSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for separating voluntary dismount from crouch launch charging. */
class AvatarFlightDismountPolicyTest {
    private final AvatarFlightMountingSettings settings = new AvatarFlightMountingSettings();

    @Test
    void groundedBackAndCrouchCompletesAfterConfiguredHold() {
        AvatarFlightDismountPolicy.Decision started = AvatarFlightDismountPolicy.evaluate(
                1_000L, 0L, true, true, -1.0, 0.25, settings);
        AvatarFlightDismountPolicy.Decision completed = AvatarFlightDismountPolicy.evaluate(
                1_750L, started.holdStartedAtMs(), true, true, -1.0, 0.25, settings);

        assertTrue(started.suppressLaunch());
        assertFalse(started.complete());
        assertEquals(1_000L, started.holdStartedAtMs());
        assertTrue(completed.complete());
    }

    @Test
    void crouchWithoutBackwardIntentRemainsAvailableForLaunch() {
        AvatarFlightDismountPolicy.Decision decision = AvatarFlightDismountPolicy.evaluate(
                1_000L, 0L, true, true, 0.0, 0.25, settings);

        assertFalse(decision.suppressLaunch());
        assertFalse(decision.complete());
        assertEquals(0L, decision.holdStartedAtMs());
    }

    @Test
    void airborneRequestCancelsGroundedHold() {
        AvatarFlightDismountPolicy.Decision decision = AvatarFlightDismountPolicy.evaluate(
                1_500L, 1_000L, false, true, -1.0, 0.25, settings);

        assertFalse(decision.suppressLaunch());
        assertEquals(0L, decision.holdStartedAtMs());
    }
}
