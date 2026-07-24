package com.alechilles.alecstamework.items.persistence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DormantCompanionBridgeArchitectureTest {
    @Test
    void entityRemovalDoesNotInferDormancyFromUnloadAbsenceOrTimeout()
            throws IOException {
        String factory = source(
                "items/persistence/"
                        + "HytaleDormantCompanionObservationFactory.java"
        );
        String system = source(
                "npc/systems/CompanionDormantRemovalSystem.java"
        );

        assertTrue(factory.contains("reason == RemoveReason.REMOVE"));
        assertFalse(factory.contains("Evidence.UNLOAD"));
        assertFalse(factory.contains("Evidence.ABSENCE"));
        assertFalse(factory.contains("Evidence.TIMEOUT"));
        assertTrue(system.contains("reason == RemoveReason.REMOVE"));
    }

    @Test
    void asyncCompletionRetainsOnlyStableKey() throws IOException {
        String bridge = source(
                "items/persistence/DormantCompanionEcsBridge.java"
        );

        assertTrue(bridge.contains(
                "stage.whenCompleteAsync("
        ));
        assertTrue(bridge.contains(
                "complete(key, result, failure)"
        ));
        assertFalse(bridge.contains(
                "complete(reference, store"
        ));
    }

    @Test
    void worldDeletionRequiresUncancelledDeleteOnRemoveEvent()
            throws IOException {
        String bridge = source(
                "items/persistence/"
                        + "DormantCompanionWorldRemovalBridge.java"
        );

        assertTrue(bridge.contains("!event.isCancelled()"));
        assertTrue(bridge.contains("isDeleteOnRemove()"));
        assertFalse(bridge.contains("RemoveReason.UNLOAD"));
    }

    @Test
    void worldDeletionNeverJoinsTheWorldExecutorAndReportsOffThread()
            throws IOException {
        String bridge = source(
                "items/persistence/"
                        + "DormantCompanionWorldRemovalBridge.java"
        );

        assertTrue(bridge.contains(
                "CompletableFuture.runAsync(() -> scan(world), world)"
        ));
        assertTrue(bridge.contains(".whenCompleteAsync("));
        assertTrue(bridge.contains("completionExecutor"));
        assertFalse(bridge.contains(".join()"));
        assertFalse(bridge.contains(".get()"));
    }

    private String source(String relative) throws IOException {
        return Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework", relative
        ));
    }
}
