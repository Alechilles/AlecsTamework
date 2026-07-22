package com.alechilles.alecstamework.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards capture spending from world-thread SQLite reads and ambiguous crash boundaries. */
class CaptureSourceSpendRecoveryArchitectureTest {
    @Test
    void captureUsesAsyncRosterProofAndDurableReceiptBeforeDecrement() throws Exception {
        String handler = source("items/SpawnerFeatureHandler.java");
        String runtime = source("items/SpawnerCaptureAttemptRuntimeCoordinator.java");
        String recovery = source("items/SpawnerCaptureSourceSpendRecoveryService.java");

        assertTrue(handler.contains("loadRevisionProof("));
        assertTrue(handler.contains("isCurrent("));
        assertFalse(handler.contains("commandFamilyRosterService.get("));
        assertTrue(runtime.indexOf("confirmSourceReceipted(attemptId)")
                < runtime.indexOf("SpawnerCaptureSourceReceipt.after(receipt)"));
        assertTrue(recovery.contains("loadPendingSourceSpends(playerUuid)"));
        assertTrue(recovery.contains("cancelUnreceiptedSuccess("));
        assertTrue(recovery.contains("requireSourceRefund("));
        assertTrue(handler.contains("captureSourceSpendRecoveryService.recoverAfterWorldJoin("));
    }

    private static String source(String relative) throws Exception {
        return Files.readString(Path.of("src/main/java/com/alechilles/alecstamework", relative));
    }
}
