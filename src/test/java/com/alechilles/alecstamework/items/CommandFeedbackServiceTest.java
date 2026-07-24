package com.alechilles.alecstamework.items;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    @Test
    void frozenDestinationUsesSpecificRestorationWarning() {
        assertEquals(
                "tamework.ui.notifications.command.destination.npcsFrozen",
                CommandFeedbackService.restorationRequestFeedbackKey(
                        CommandCompanionRestorationService.RequestStatus
                                .DESTINATION_NPCS_FROZEN
                )
        );
        assertNull(CommandFeedbackService.restorationRequestFeedbackKey(
                CommandCompanionRestorationService.RequestStatus.STARTED
        ));
    }
}
