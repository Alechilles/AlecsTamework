package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationOperationRecoveryTestSupport.Harness;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationOperationRecoveryTestSupport.dormant;
import static com.alechilles.alecstamework.ownership.reconciliation.CompanionProjectionRecoveryTestSupport.prepareManagedCoopRelease;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for atomically closing unapplied managed-coop population releases. */
class ManagedCoopPopulationOperationRecoveryTest {
    private static final ManagedCoopAuthorityKey AUTHORITY =
            new ManagedCoopAuthorityKey("default", 10, 20, 30);

    @TempDir
    Path tempDir;

    /** Protects the four retained APPLYING/SPAWN_CLAIMED rows observed in the July 13 test world. */
    @Test
    void dormantSourceEvidenceRestoresClaimedReleaseAndClosesBothJournals() throws Exception {
        UUID previousNpcUuid = UUID.randomUUID();
        UUID plannedNpcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        try (Harness harness = CompanionPopulationOperationRecoveryTestSupport.open(
                tempDir, "managed-coop-close.sqlite")) {
            prepareManagedCoopRelease(
                    harness, previousNpcUuid, plannedNpcUuid, ownerUuid, AUTHORITY);

            CompanionPopulationOperationRecoveryService.RecoveryResult result = harness.recover(
                    List.of(dormant(
                            previousNpcUuid, ownerUuid,
                            CompanionPopulationEvidence.Kind.COOP_SNAPSHOT, "default")));

            assertTrue(result.complete(), result.toString());
            assertEquals(1, result.canceled());
            assertEquals(CompanionPopulationOperationRecord.State.FAILED,
                    harness.operationState());
            var resident = harness.residents().loadById("resident-profile");
            assertEquals(ResidentState.HOUSED, resident.state());
            assertEquals(previousNpcUuid, resident.residentUuid());
            assertNull(resident.deployedNpcUuid());
            var lifecycle = harness.lifecycle().load("coop-release-operation");
            assertEquals(OperationState.FAILED, lifecycle.state());
            assertFalse(lifecycle.active());
        }
    }

    @Test
    void durableProjectionReceiptKeepsBothJournalsQuarantined() throws Exception {
        UUID previousNpcUuid = UUID.randomUUID();
        UUID plannedNpcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        try (Harness harness = CompanionPopulationOperationRecoveryTestSupport.open(
                tempDir, "managed-coop-ambiguous.sqlite")) {
            prepareManagedCoopRelease(
                    harness, previousNpcUuid, plannedNpcUuid, ownerUuid, AUTHORITY);
            markDurableProjectionReceipt(harness, plannedNpcUuid);

            CompanionPopulationOperationRecoveryService.RecoveryResult result = harness.recover(
                    List.of(dormant(
                            previousNpcUuid, ownerUuid,
                            CompanionPopulationEvidence.Kind.COOP_SNAPSHOT, "default")));

            assertFalse(result.complete());
            assertEquals("operation-recovery-close-failed",
                    result.ambiguous().getFirst().reason());
            assertEquals(CompanionPopulationOperationRecord.State.APPLYING,
                    harness.operationState());
            assertEquals(OperationState.SPAWN_CLAIMED,
                    harness.lifecycle().load("coop-release-operation").state());
            assertEquals(ResidentState.RELEASING,
                    harness.residents().loadById("resident-profile").state());
        }
    }

    private static void markDurableProjectionReceipt(
            Harness harness, UUID plannedNpcUuid) throws Exception {
        try (Connection connection = harness.connections().openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE coop_lifecycle_operations
                     SET actual_target_uuid = ?
                     WHERE operation_id = 'coop-release-operation'
                     """)) {
            statement.setString(1, plannedNpcUuid.toString());
            statement.executeUpdate();
        }
    }
}
