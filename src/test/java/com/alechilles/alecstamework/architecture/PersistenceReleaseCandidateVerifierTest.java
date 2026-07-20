package com.alechilles.alecstamework.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the exact-candidate verifier and its strict no-world-backup boundary. */
class PersistenceReleaseCandidateVerifierTest {
    private static final Path SCRIPT =
            Path.of("scripts/tools/verify-persistence-release-candidate.ps1");
    private static final Path LIVE_SCRIPT =
            Path.of("scripts/tools/verify-persistence-live-rehearsal.ps1");
    private static final Path LIVE_TEMPLATE =
            Path.of("scripts/tools/templates/persistence-live-rehearsal-template.json");

    @Test
    void verifierRunsEveryRepositoryGateBeforePackaging() throws Exception {
        String source = Files.readString(SCRIPT);

        for (String required : List.of(
                "tamework-tests", "telemetry-tests", "platform-typecheck", "platform-lint",
                "platform-tests", "--pool=threads", "--maxWorkers=4",
                "platform-build", "tamework-package",
                "Get-SurefireEvidence", "Get-VitestEvidence", "Get-PersistencePerformanceEvidence",
                "Get-RequiredSurefireReportEvidence", "Get-RequiredVitestFileEvidence",
                "Get-UnsafePlayerAccessScanEvidence", "PersistenceResiliencePerformanceGateTest.xml",
                "requiredTameworkReports", "requiredTelemetryReports", "requiredPlatformFiles",
                "live-rehearsal-verifier-tests", "liveRehearsalVerifierContract",
                "releaseTooling", "verify-persistence-live-rehearsal.ps1",
                "dependencies", "documentation", "knownLimitations", "requiredEntries")) {
            assertTrue(source.contains(required), "missing candidate gate: " + required);
        }
        assertTrue(source.indexOf("tamework-tests") < source.indexOf("tamework-package"));
        assertTrue(source.contains("worktree is dirty"));
        assertTrue(source.contains("commit changed while gates were running"));
        assertTrue(source.contains("[string] $HytaleVersion,"));
        assertFalse(source.contains("$HytaleVersion ="));
    }

    @Test
    void verifierRequiresNamedSafetyMigrationPrivacyAndPortalEvidence() throws Exception {
        String source = Files.readString(SCRIPT);

        for (String required : List.of(
                "EcsWriteSafetyGuardTest",
                "AsyncThreadSafetyGuardTest",
                "PersistenceReleaseCandidateVerifierTest",
                "HistoricalSchemaPrerequisiteRepairTest",
                "SqliteMigrationBackupServiceTest",
                "PersistenceHistoricalCorpusManifestTest",
                "PersistenceTelemetryPrivacyTest",
                "TelemetryBreadcrumbContextTest",
                "ManualReportRedactorTest",
                "persistence-correlation-migration.test.ts",
                "persistence-incident-timeline.test.tsx",
                "privacy-retention-repo.test.ts",
                "package-lock.json")) {
            assertTrue(source.contains(required), "missing named release evidence: " + required);
        }
        assertTrue(source.contains("matchCount = 0"));
        assertTrue(source.contains("Required Surefire report must appear exactly once"));
        assertTrue(source.contains("pending-user-run"));
        assertTrue(source.contains("pending-deployment-authorization"));
    }

    @Test
    void verifierRecordsButNeverCreatesAWholeSaveBackup() throws Exception {
        String source = Files.readString(SCRIPT);
        String lower = source.toLowerCase();

        assertTrue(source.contains("wholeSaveBackupCreatedByTamework = $false"));
        assertTrue(source.contains("hytaleOwnsWholeSaveBackups = $true"));
        assertFalse(lower.contains("copy-item"));
        assertFalse(lower.contains("compress-archive"));
        assertFalse(lower.contains("vacuum into"));
        assertFalse(lower.contains("userdata/saves"));
        assertFalse(lower.contains("userdata\\saves"));
    }

    @Test
    void liveRehearsalVerifierRequiresExactArtifactsDomainRepetitionAndRollback() throws Exception {
        String source = Files.readString(LIVE_SCRIPT);
        String template = Files.readString(LIVE_TEMPLATE);

        for (String required : List.of(
                "candidateArtifactSha256", "sourceCommits", "five distinct fixtures",
                "login-tame-and-two-spawns", "managed-coop-old-and-new-multi-resident",
                "manual-and-passive-breeding-repeat", "inventory-and-storage-capture-release",
                "cleanup-death-lost-revival", "same-and-cross-world-recall",
                "hold-follow-restart-no-teleport", "linked-panel-canonical-state-name",
                "scoped-fault-and-recovery", "diagnostic-export-and-telemetry-correlation",
                "linkedProfiles must be >= 1000", "managedCoops must be >= 100",
                "candidate tick p95 exceeds the 0.25 ms live budget", "unresolvedWarnings must be empty",
                "tamework_sqlite_only", "rollback SQLite hash must match",
                "operatorSignedOff", "wholeSaveBackupCreatedByTamework")) {
            assertTrue(source.contains(required), "missing live rehearsal contract: " + required);
        }
        assertTrue(template.contains("\"operatorSignedOff\": false"));
        assertTrue(template.contains("\"wholeSaveBackupCreatedByTamework\": false"));
        assertFalse(source.toLowerCase().contains("copy-item"));
        assertFalse(source.toLowerCase().contains("compress-archive"));
    }
}
