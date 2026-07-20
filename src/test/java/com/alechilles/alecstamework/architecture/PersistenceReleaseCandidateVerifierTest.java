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

    @Test
    void verifierRunsEveryRepositoryGateBeforePackaging() throws Exception {
        String source = Files.readString(SCRIPT);

        for (String required : List.of(
                "tamework-tests", "telemetry-tests", "platform-typecheck", "platform-lint",
                "platform-tests", "--pool=threads", "--maxWorkers=4",
                "platform-build", "tamework-package",
                "Get-SurefireEvidence", "Get-VitestEvidence", "Get-PersistencePerformanceEvidence",
                "PersistenceResiliencePerformanceGateTest.xml", "requiredEntries")) {
            assertTrue(source.contains(required), "missing candidate gate: " + required);
        }
        assertTrue(source.indexOf("tamework-tests") < source.indexOf("tamework-package"));
        assertTrue(source.contains("worktree is dirty"));
        assertTrue(source.contains("commit changed while gates were running"));
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
}
