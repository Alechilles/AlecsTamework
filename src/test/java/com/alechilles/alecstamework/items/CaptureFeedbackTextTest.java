package com.alechilles.alecstamework.items;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CaptureFeedbackTextTest {
    @Test
    void powerDenialNamesTheSourceAndTargetAndRequiredPower() {
        var text = CaptureFeedbackText.denial(
                CaptureFeedbackReason.POWER_TOO_LOW,
                new CaptureFeedbackText.Context("Draconic Stone", "Nordic Drake", 2, 4, null));

        assertEquals("tamework.ui.notifications.capture.powerTooLow", text.key());
        assertArrayEquals(new Object[] {"Draconic Stone", "Nordic Drake", 4}, text.arguments());
    }

    @Test
    void unrecognizedPolicyReasonUsesTheSafeFallback() {
        assertEquals(CaptureFeedbackReason.UNAVAILABLE,
                CaptureFeedbackReason.fromPolicyCode("capture-random-provider-failed"));
    }
}
