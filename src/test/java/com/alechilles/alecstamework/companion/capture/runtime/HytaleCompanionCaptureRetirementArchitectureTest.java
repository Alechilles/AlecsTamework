package com.alechilles.alecstamework.companion.capture.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Guards the capture-retirement boundary used by the dormant-removal observer. */
class HytaleCompanionCaptureRetirementArchitectureTest {
    @Test
    void captureMarksTargetAsIntentionallyRetiredBeforeRemoval()
            throws IOException {
        // Regression: capture removals logged dormant_operation_in_progress on 2026-08-05.
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/companion/"
                        + "capture/runtime/HytaleCompanionCaptureAttemptGateway.java"
        ));

        int marker = source.indexOf("TameworkPersistenceRetirementComponent.exact(");
        int removal = source.indexOf(
                "store.removeEntity(target, RemoveReason.REMOVE)"
        );

        assertTrue(marker >= 0,
                "Capture must mark its intentional target retirement.");
        assertTrue(marker < removal,
                "The retirement marker must exist before removal observers run.");
    }
}
