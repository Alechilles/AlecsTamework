package com.alechilles.alecstamework.commands;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards explicit, fingerprint-bound operator approval for destructive coop import progress. */
class TameworkCoopReconcileCommandArchitectureTest {
    @Test
    void reconcileRemainsReportOnlyUntilAnAuthorizedExactFingerprintConfirmation()
            throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/commands/TameworkCoopCommand.java"));

        assertTrue(source.contains("tamework.command.coop.reconcile"));
        assertTrue(source.contains("ManagedCoopImportControl.shared()"));
        assertTrue(source.contains("control.latestInspection(authorityKey)"));
        assertTrue(source.contains("control.confirm(authorityKey, fingerprint"));
        assertTrue(source.contains("control.cancel(authorityKey)"));
        assertTrue(source.contains("The runtime will revalidate it"));
        assertTrue(source.contains("no import was approved"));
        assertTrue(source.contains("report.overflowCount()"));
        assertTrue(source.contains("source.sourceFingerprint()"));
        assertTrue(source.contains("source.unavailableFieldsJson()"));
        assertTrue(source.contains("rollback-preflight"));
        assertTrue(source.contains("live v5-to-v4 downgrade is unsupported"));
        assertTrue(source.contains("A SQLite backup is not a complete-save backup"));
        assertTrue(source.contains("report.residentDetails()"));
        assertTrue(source.contains("report.operationDetails()"));
        assertTrue(source.contains("runtime.getWriteQueueLifecycleMetrics()"));
        assertTrue(source.contains("managedCoopAudit=FAILED"));
        assertTrue(source.contains("activeLifecycle=UNKNOWN"));
        assertTrue(source.contains("coop.failureReason()"));
    }
}
