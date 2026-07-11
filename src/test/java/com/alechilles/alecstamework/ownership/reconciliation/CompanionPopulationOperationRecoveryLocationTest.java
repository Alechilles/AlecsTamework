package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.OwnerPopulationOperation;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationRepairRepository;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationOperationRecoveryTestSupport.dormant;
import static com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationOperationRecoveryTestSupport.insertScenario;
import static com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationOperationRecoveryTestSupport.physical;
import static com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationOperationRecoveryTestSupport.updateTargetContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers restore and rehome recovery decisions that depend on exact physical-location evidence. */
class CompanionPopulationOperationRecoveryLocationTest {
    @TempDir
    Path tempDir;

    @Test
    void applyingRestoreCommitsLiveReplacementAndRepairIgnoresStaleDormantAlias() throws Exception {
        try (CompanionPopulationOperationRecoveryTestSupport.Harness harness = harness(
                "restore-stale-dormant.sqlite"
        )) {
            UUID oldUuid = UUID.randomUUID();
            UUID restoredUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            insertScenario(
                    harness, "profile", oldUuid, ownerUuid, ownerUuid,
                    CompanionLifecycleState.CAPTURED, CompanionLifecycleState.ACTIVE,
                    "default", "default", OwnerPopulationOperation.RESTORE,
                    CompanionPopulationOperationRecord.State.APPLYING, false
            );
            updateTargetContext(harness, "{\"npcUuid\":\"" + restoredUuid
                    + "\",\"world\":\"default\",\"chunkX\":0,\"chunkZ\":0}");
            CompanionPopulationEvidenceSet evidence = new CompanionPopulationEvidenceSet(List.of(
                    physical(restoredUuid, ownerUuid, "default", 0, 0),
                    dormant(oldUuid, ownerUuid, CompanionPopulationEvidence.Kind.CAPTURED_SNAPSHOT, "default")
            ));

            CompanionPopulationOperationRecoveryService.RecoveryResult recovery =
                    harness.recovery().recoverAsync(
                            harness.repository().loadNonterminalOperations(), evidence
                    ).get(3, TimeUnit.SECONDS);

            assertEquals(1, recovery.committed());
            assertTrue(recovery.complete());
            assertEquals(restoredUuid, harness.state().currentNpcUuid());
            CompanionPopulationRepairRepository.RepairResult repair = harness.repair().mergeAsync(evidence)
                    .completion().get(3, TimeUnit.SECONDS).value();
            assertTrue(repair.merged());
            assertEquals(CompanionLifecycleState.UNLOADED.name(), harness.state().lifecycleState());
        }
    }

    @Test
    void applyingRestoreClosesWhenSpawnNeverHappenedAndOldSnapshotStillExists() throws Exception {
        try (CompanionPopulationOperationRecoveryTestSupport.Harness harness = harness(
                "restore-not-spawned.sqlite"
        )) {
            UUID previousUuid = UUID.randomUUID();
            UUID plannedUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            insertScenario(
                    harness, "profile", previousUuid, ownerUuid, ownerUuid,
                    CompanionLifecycleState.CAPTURED, CompanionLifecycleState.ACTIVE,
                    "default", "default", OwnerPopulationOperation.RESTORE,
                    CompanionPopulationOperationRecord.State.APPLYING, false
            );
            updateTargetContext(harness, "{\"previousNpcUuid\":\"" + previousUuid
                    + "\",\"plannedNpcUuid\":\"" + plannedUuid
                    + "\",\"world\":\"default\",\"chunkX\":0,\"chunkZ\":0}");

            CompanionPopulationOperationRecoveryService.RecoveryResult result = harness.recover(List.of(
                    dormant(previousUuid, ownerUuid,
                            CompanionPopulationEvidence.Kind.CAPTURED_SNAPSHOT, "default")
            ));

            assertTrue(result.complete());
            assertEquals(0, result.committed());
            assertEquals(1, result.canceled());
            assertEquals(CompanionPopulationOperationRecord.State.FAILED, harness.operationState());
            assertEquals(previousUuid, harness.state().currentNpcUuid());
            assertEquals(CompanionLifecycleState.CAPTURED.name(), harness.state().lifecycleState());
        }
    }

    @Test
    void applyingRehomeClosesWhenNpcRemainsAtExactSourceLocation() throws Exception {
        try (CompanionPopulationOperationRecoveryTestSupport.Harness harness = harness(
                "rehome-not-moved.sqlite"
        )) {
            UUID npcUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            insertScenario(
                    harness, "profile", npcUuid, ownerUuid, ownerUuid,
                    CompanionLifecycleState.ACTIVE, CompanionLifecycleState.ACTIVE,
                    "source", "destination", OwnerPopulationOperation.REHOME,
                    CompanionPopulationOperationRecord.State.APPLYING, false
            );
            updateTargetContext(harness, "{\"previousNpcUuid\":\"" + npcUuid
                    + "\",\"npcUuid\":\"" + npcUuid
                    + "\",\"world\":\"destination\",\"chunkX\":8,\"chunkZ\":9}");

            CompanionPopulationOperationRecoveryService.RecoveryResult result = harness.recover(List.of(
                    physical(npcUuid, ownerUuid, "source", 0, 0)
            ));

            assertTrue(result.complete());
            assertEquals(0, result.committed());
            assertEquals(1, result.canceled());
            assertEquals(CompanionPopulationOperationRecord.State.FAILED, harness.operationState());
            assertEquals("source", harness.state().physicalWorldName());
            assertEquals(Integer.valueOf(0), harness.state().physicalChunkX());
            assertEquals(Integer.valueOf(0), harness.state().physicalChunkZ());
        }
    }

    private CompanionPopulationOperationRecoveryTestSupport.Harness harness(String file) throws Exception {
        return CompanionPopulationOperationRecoveryTestSupport.open(tempDir, file);
    }
}
