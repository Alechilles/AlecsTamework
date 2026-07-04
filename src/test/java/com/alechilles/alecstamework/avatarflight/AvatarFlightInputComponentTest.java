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
    void verticalPacketIntentIsClearedAfterSampling() {
        AvatarFlightInputComponent input = new AvatarFlightInputComponent();
        input.setVerticalAxis(-1.0);
        input.setJumping(true);
        input.setCrouching(true);
        input.setSprinting(true);

        input.clearTransientVerticalIntent();

        assertEquals(0.0, input.getVerticalAxis(), 0.0001);
        assertFalse(input.isJumping());
        assertFalse(input.isCrouching());
        assertFalse(input.isSprinting());
    }
}
