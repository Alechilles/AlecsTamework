package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Guards the shared destination policy at both command paths that can create a live companion.
 */
class CompanionDestinationAdmissionWiringTest {
    @Test
    void relocationAndRestorationShareFrozenNpcAdmissionPolicy() throws IOException {
        String relocation = source("CommandRelocationDispatchService.java");
        String restoration = source("CommandCompanionRestorationService.java");

        assertTrue(relocation.contains(
                "CompanionDestinationAdmissionPolicy.assess(world)"
        ));
        assertTrue(restoration.contains(
                "CompanionDestinationAdmissionPolicy.assess(world)"
        ));
    }

    @Test
    void rejectionProducesSpecificPlayerFeedback() throws IOException {
        String menu = source("CommandMenuMoveService.java");
        String feedback = source("CommandFeedbackService.java");
        String feedbackKey =
                "\"tamework.ui.notifications.command.destination.npcsFrozen\"";

        assertTrue(menu.contains(feedbackKey));
        assertTrue(feedback.contains(feedbackKey));
    }

    private String source(String fileName) throws IOException {
        return Files.readString(
                Path.of(
                        "src/main/java/com/alechilles/alecstamework/items",
                        fileName
                ),
                StandardCharsets.UTF_8
        );
    }
}
