package com.alechilles.alecstamework.avatarflight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests one-shot Dragon Reins input state stored beside packet movement intent. */
class AvatarFlightInputComponentTest {

    @Test
    void reinsFlapIsConsumedOnce() {
        AvatarFlightInputComponent input = new AvatarFlightInputComponent();

        input.queueReinsFlap(1_000L);

        assertTrue(input.consumeReinsFlap());
        assertFalse(input.consumeReinsFlap());
    }

    @Test
    void staleReinsFlapIsConsumedWithoutApplying() {
        AvatarFlightInputComponent input = new AvatarFlightInputComponent();

        input.queueReinsFlap(1_000L);

        assertFalse(input.consumeReinsFlap(2_001L, 1_000L));
        assertFalse(input.consumeReinsFlap());
    }

    @Test
    void reinsAirbrakeUsesShortActiveWindow() {
        AvatarFlightInputComponent input = new AvatarFlightInputComponent();

        input.activateReinsAirbrake(1_000L, 350L);

        assertTrue(input.isReinsAirbrakeActive(1_200L));
        assertFalse(input.isReinsAirbrakeActive(1_351L));
    }

    @Test
    void reinsBoostIsConsumedOnceWithinIntentWindow() {
        AvatarFlightInputComponent input = new AvatarFlightInputComponent();

        input.queueReinsBoost(1_000L);

        assertTrue(input.consumeReinsBoost(1_200L, 1_000L));
        assertFalse(input.consumeReinsBoost(1_200L, 1_000L));
    }

    @Test
    void staleReinsBoostIsConsumedWithoutApplying() {
        AvatarFlightInputComponent input = new AvatarFlightInputComponent();

        input.queueReinsBoost(1_000L);

        assertFalse(input.consumeReinsBoost(2_001L, 1_000L));
        assertFalse(input.consumeReinsBoost(2_001L, 1_000L));
    }

    @Test
    void sprintBoostIsQueuedOnlyOnRisingEdge() {
        AvatarFlightInputComponent input = new AvatarFlightInputComponent();

        input.updateSprinting(false, 900L);
        input.updateSprinting(true, 1_000L);
        input.updateSprinting(true, 1_100L);

        assertTrue(input.consumeSprintBoost(1_150L, 1_000L));
        assertFalse(input.consumeSprintBoost(1_150L, 1_000L));

        input.updateSprinting(true, 1_200L);
        assertFalse(input.consumeSprintBoost(1_250L, 1_000L),
                "holding sprint must not queue another boost after the first press is consumed");

        input.updateSprinting(false, 1_300L);
        input.updateSprinting(true, 1_400L);
        assertTrue(input.consumeSprintBoost(1_450L, 1_000L));
    }

    @Test
    void staleSprintBoostIsConsumedWithoutApplying() {
        AvatarFlightInputComponent input = new AvatarFlightInputComponent();

        input.updateSprinting(true, 1_000L);

        assertFalse(input.consumeSprintBoost(2_001L, 1_000L));
        assertFalse(input.consumeSprintBoost(2_001L, 1_000L));
    }

    @Test
    void transientVerticalIntentClearingDoesNotCreateNewSprintEdges() {
        AvatarFlightInputComponent input = new AvatarFlightInputComponent();
        input.setVerticalAxis(-1.0);
        input.setJumping(true);
        input.setCrouching(true);
        input.updateSprinting(true, 1_000L);

        input.clearTransientVerticalIntent();

        assertEquals(0.0, input.getVerticalAxis(), 0.0001);
        assertFalse(input.isJumping());
        assertFalse(input.isCrouching());
        assertTrue(input.isSprinting(),
                "raw sprint state must persist so a held key does not look like a fresh press every tick");
        assertTrue(input.consumeSprintBoost(1_100L, 1_000L));
        input.clearTransientVerticalIntent();
        assertFalse(input.consumeSprintBoost(1_200L, 1_000L));
    }

    @Test
    void launchReleaseIsQueuedOnceWithHoldDuration() {
        AvatarFlightInputComponent input = new AvatarFlightInputComponent();

        input.beginLaunchCharge(1_000L);
        input.queueLaunchRelease(1_750L);

        assertFalse(input.isLaunchCharging());
        assertEquals(750L, input.getLaunchHoldMs());
        assertTrue(input.consumeLaunchRelease(1_800L, 1_000L));
        assertEquals(750L, input.getLaunchHoldMs(),
                "movement reads the queued hold duration after consuming the release intent");
        assertFalse(input.consumeLaunchRelease(1_800L, 1_000L));
    }

    @Test
    void staleLaunchReleaseIsConsumedWithoutApplying() {
        AvatarFlightInputComponent input = new AvatarFlightInputComponent();

        input.beginLaunchCharge(1_000L);
        input.queueLaunchRelease(1_750L);

        assertFalse(input.consumeLaunchRelease(2_801L, 1_000L));
        assertEquals(750L, input.getLaunchHoldMs());
        assertFalse(input.consumeLaunchRelease(2_801L, 1_000L));
    }

    @Test
    void repeatedLaunchBeginKeepsOriginalStartTime() {
        AvatarFlightInputComponent input = new AvatarFlightInputComponent();

        input.beginLaunchCharge(1_000L);
        input.beginLaunchCharge(1_500L);
        input.queueLaunchRelease(1_750L);

        assertEquals(750L, input.getLaunchHoldMs());
        assertTrue(input.consumeLaunchRelease(1_800L, 1_000L));
    }

    @Test
    void cancellingLaunchChargePreventsReleaseQueue() {
        AvatarFlightInputComponent input = new AvatarFlightInputComponent();

        input.beginLaunchCharge(1_000L);
        input.cancelLaunchCharge();
        input.queueLaunchRelease(1_750L);

        assertFalse(input.isLaunchCharging());
        assertEquals(0L, input.getLaunchHoldMs());
        assertFalse(input.consumeLaunchRelease(1_800L, 1_000L));
    }
}
