package com.alechilles.alecstamework.items;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandFeedbackServiceTest {
    @Test
    void customHudCountIncludesQueuedUnloadedRecipients() {
        assertEquals(1, CommandFeedbackService.acceptedRecipientCount(0, 1));
        assertEquals(3, CommandFeedbackService.acceptedRecipientCount(1, 2));
    }

    @Test
    void acceptedRecipientCountDoesNotExposeNegativeInputs() {
        assertEquals(0, CommandFeedbackService.acceptedRecipientCount(-1, -2));
    }
}
