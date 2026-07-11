package com.alechilles.alecstamework.items;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static guardrails for the decomposed managed-coop cutover boundary. */
class ManagedCoopRuntimeCutoverArchitectureTest {
    private static final Path MAIN = Path.of(
            "src/main/java/com/alechilles/alecstamework/items");
    private static final List<String> FILES = List.of(
            "ManagedCoopChunkScanner.java",
            "ManagedCoopCaptureCandidate.java",
            "ManagedCoopRuntimeCandidateScanner.java",
            "ManagedCoopLifecycleAdmissionGuard.java",
            "ManagedCoopRuntimeSweepPlanner.java",
            "ManagedCoopRuntimeOperationDispatcher.java",
            "HytaleManagedCoopReleaseProjectionGateway.java",
            "ManagedCoopRuntimeSweepOrchestrator.java",
            "ManagedCoopRuntimeSystem.java");

    @Test
    void cutoverClassesStayFocusedAndNeverReintroduceLegacyOrVanillaMutation() throws Exception {
        for (String file : FILES) {
            Path path = MAIN.resolve(file);
            String source = Files.readString(path);
            assertTrue(Files.readAllLines(path).size() <= 500, file + " exceeds 500 lines");
            assertFalse(source.contains("CommandLinkedNpcCoopService"), file);
            assertFalse(source.contains("captureSnapshotForLedger"), file);
            assertFalse(source.contains("getCoopSnapshot"), file);
            assertFalse(source.contains("tryPutResident"), file);
            assertFalse(source.contains("spawnEntity("), file);
            assertFalse(source.contains("Class.forName("), file);
            assertFalse(source.contains("TameworkReflectionAccessCache"), file);
            assertFalse(source.contains("java.lang.reflect"), file);
        }
    }

    @Test
    void asyncDispatcherDoesNotCaptureLiveArgumentsInContinuations() throws Exception {
        String source = Files.readString(MAIN.resolve("ManagedCoopRuntimeOperationDispatcher.java"));

        assertTrue(source.contains("captures.capture("));
        assertTrue(source.contains("capture.thenCompose(this::afterCapture)"));
        assertTrue(source.contains("releases.coordinate(new ReleaseAttempt"));
        assertTrue(source.contains("projections.project(new ReleaseProjectionCommand"));
        assertFalse(source.contains("thenCompose(outcome -> operations.capture"));
        assertFalse(source.contains("thenCompose(outcome -> releases.release"));
    }
}
