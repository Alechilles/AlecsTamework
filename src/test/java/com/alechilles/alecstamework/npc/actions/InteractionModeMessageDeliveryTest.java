package com.alechilles.alecstamework.npc.actions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for mode feedback when an entity is not yet tracked by the client. */
class InteractionModeMessageDeliveryTest {

    @Test
    void fallsBackToUiMessageWhenFloatingTextCannotBeQueued() {
        CapturingChannels channels = new CapturingChannels(false, true);

        assertTrue(InteractionModeMessageDelivery.deliver(
                "Following", true, false, channels
        ));
        assertEquals(1, channels.floatingTextAttempts);
        assertEquals(1, channels.uiMessageAttempts);
        assertEquals("Following", channels.uiMessage);
    }

    private static final class CapturingChannels
            implements InteractionModeMessageDelivery.Channels {
        private final boolean floatingTextResult;
        private final boolean uiMessageResult;
        private int floatingTextAttempts;
        private int uiMessageAttempts;
        private String uiMessage;

        private CapturingChannels(boolean floatingTextResult, boolean uiMessageResult) {
            this.floatingTextResult = floatingTextResult;
            this.uiMessageResult = uiMessageResult;
        }

        @Override
        public boolean showFloatingText(String message) {
            floatingTextAttempts++;
            return floatingTextResult;
        }

        @Override
        public boolean showUiMessage(String message) {
            uiMessageAttempts++;
            uiMessage = message;
            return uiMessageResult;
        }
    }
}
