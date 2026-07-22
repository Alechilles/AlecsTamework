package com.alechilles.alecstamework.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prevents a terminal tame-link capture from stranding an active companion without a lease. */
class CaptureTameLinkLeaseConvergenceArchitectureTest {
    @Test
    void commitRequiresRosterThenActiveLeaseAndRestartReplaysBoth() throws Exception {
        String handler = source("items/SpawnerFeatureHandler.java");
        String recovery = source("items/SpawnerCaptureSourceSpendRecoveryService.java");
        String evidence = source("items/capturepolicy/runtime/SqliteCaptureAttemptRecoveryEvidence.java");

        int roster = handler.indexOf("commandFamilyRosterService.upsert(rosterRequest)");
        int lease = handler.indexOf("registerTimedLease(", roster);
        int commit = handler.indexOf("captureAttemptRuntime.commit(attemptId)", lease);
        assertTrue(roster >= 0 && lease > roster && commit > lease);
        assertTrue(handler.contains("registerActiveProjection("));
        assertTrue(handler.contains("capture-timed-lease-registration-failed:"));
        assertFalse(handler.contains("tame-and-command-link-rollback:"));

        assertTrue(recovery.contains("loadPendingTameLinkConvergence(playerUuid)"));
        assertTrue(recovery.contains("rosters.upsert("));
        assertTrue(recovery.contains("timed.registerActiveProjection("));
        assertTrue(recovery.indexOf("rosters.upsert(")
                < recovery.indexOf("timed.registerActiveProjection("));
        assertTrue(recovery.indexOf("timed.registerActiveProjection(")
                < recovery.indexOf("attempts.commitRecoveredTameLink("));
        assertTrue(evidence.contains("capture-recovery-tame-link-convergence-required"));
    }

    @Test
    void restartBeforeBeginApplyOnlyRefundsAnUnappliedPopulationOperation() throws Exception {
        String recovery = source("items/SpawnerCaptureSourceSpendRecoveryService.java");
        int prepared = recovery.indexOf(
                "operation.state() == CompanionPopulationOperationRecord.State.PREPARED");
        int resolved = recovery.indexOf(
                "attempt.state() == CaptureAttemptRecord.State.RESOLVED_SUCCESS", prepared);
        int refund = recovery.indexOf("capture-restart-before-begin-apply", resolved);
        assertTrue(prepared >= 0 && resolved > prepared && refund > resolved);
    }

    private static String source(String relative) throws Exception {
        return Files.readString(Path.of("src/main/java/com/alechilles/alecstamework", relative));
    }
}
