package com.alechilles.alecstamework.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the small release contract without recreating a release subsystem. */
class PersistenceReplacementReleaseContractTest {
    private static final Path CHECKLIST =
            Path.of("docs/Persistence-Replacement-Release-Checklist.md");

    @Test
    void checklistUsesNormalGatesAndNamesReplacementEvidence() throws Exception {
        String source = Files.readString(CHECKLIST);

        for (String required : List.of(
                "./mvnw test",
                "scripts/release/validate-release.ps1",
                "scripts/release/build-package.ps1",
                "LegacyPersistenceFixtureTest",
                "LegacySourceClassifierTest",
                "PublicPersistenceImporterTest",
                "PersistenceProcessCrashMatrixTest",
                "PublicPersistenceStartupFailureMatrixTest",
                "PublicPersistenceShutdownTest",
                "ReplacementPersistenceArchitectureGuardTest",
                "EcsWriteSafetyGuardTest",
                "AsyncThreadSafetyGuardTest",
                "ReplacementPersistencePerformanceGateTest",
                "fresh world",
                "copied v2.16.1 save",
                "tamework-state.sqlite",
                "tamework.sqlite")) {
            assertTrue(source.contains(required), "missing release contract: " + required);
        }

        String lower = source.toLowerCase(java.util.Locale.ROOT);
        for (String superseded : List.of(
                "alecs-telemetry",
                "telemetry-platform",
                "old-managed-coop",
                "diagnostic-export",
                "schema v8",
                "candidate manifest")) {
            assertFalse(lower.contains(superseded), "superseded release evidence: " + superseded);
        }
    }

    @Test
    void supersededPersistenceReleaseSubsystemStaysDeleted() {
        for (String path : List.of(
                "scripts/tools/verify-persistence-release-candidate.ps1",
                "scripts/tools/verify-persistence-live-rehearsal.ps1",
                "scripts/tools/tests/test-verify-persistence-live-rehearsal.ps1",
                "scripts/tools/templates/persistence-live-rehearsal-template.json")) {
            assertFalse(Files.exists(Path.of(path)), "superseded release tool returned: " + path);
        }
    }
}
