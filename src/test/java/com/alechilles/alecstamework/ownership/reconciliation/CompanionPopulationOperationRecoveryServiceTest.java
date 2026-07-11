package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.OwnerPopulationOperation;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationOperationRecoveryTestSupport.Harness;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationOperationRecoveryTestSupport.dormant;
import static com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationOperationRecoveryTestSupport.insertScenario;
import static com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationOperationRecoveryTestSupport.physical;
import static com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationOperationRecoveryTestSupport.physicalDead;
import static com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationOperationRecoveryTestSupport.updateTargetContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionPopulationOperationRecoveryServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void preparedOperationClosesEvenWhenTargetEvidenceLooksApplied() throws Exception {
        try (Harness harness = harness("prepared.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            insertScenario(
                    harness,
                    "profile",
                    npcUuid,
                    null,
                    ownerUuid,
                    CompanionLifecycleState.ACTIVE,
                    CompanionLifecycleState.ACTIVE,
                    "default",
                    "default",
                    OwnerPopulationOperation.NEW_OWNERSHIP,
                    CompanionPopulationOperationRecord.State.PREPARED,
                    false
            );

            CompanionPopulationOperationRecoveryService.RecoveryResult result = harness.recover(List.of(
                    physical(npcUuid, ownerUuid, "default", 0, 0)
            ));

            assertEquals(0, result.committed());
            assertEquals(1, result.canceled());
            assertTrue(result.complete());
            assertEquals(CompanionPopulationOperationRecord.State.FAILED, harness.operationState());
        }
    }

    @Test
    void applyingNewOwnerTransferAndClearFollowActualPhysicalOwner() throws Exception {
        assertOwnerDeltaCommits(OwnerPopulationOperation.NEW_OWNERSHIP, null, UUID.randomUUID(), "new");
        assertOwnerDeltaCommits(
                OwnerPopulationOperation.OWNER_TRANSFER,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "transfer"
        );
        assertOwnerDeltaCommits(OwnerPopulationOperation.OWNER_CLEAR, UUID.randomUUID(), null, "clear");
    }

    @Test
    void profileRecordIsBaselineAndCannotProveAnApplyingOwnerClear() throws Exception {
        try (Harness harness = harness("profile-baseline.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            UUID oldOwner = UUID.randomUUID();
            insertScenario(
                    harness,
                    "profile",
                    npcUuid,
                    oldOwner,
                    null,
                    CompanionLifecycleState.ACTIVE,
                    CompanionLifecycleState.ACTIVE,
                    "default",
                    "default",
                    OwnerPopulationOperation.OWNER_CLEAR,
                    CompanionPopulationOperationRecord.State.APPLYING,
                    false
            );

            CompanionPopulationOperationRecoveryService.RecoveryResult result = harness.recover(List.of(
                    dormant(npcUuid, oldOwner, CompanionPopulationEvidence.Kind.PROFILE_RECORD, "default")
            ));

            assertFalse(result.complete());
            assertEquals("operation-recovery-target-not-observed", result.ambiguous().getFirst().reason());
            assertEquals(CompanionPopulationOperationRecord.State.APPLYING, harness.operationState());
        }
    }

    @Test
    void permanentReleaseCommitsWhenDestructiveEffectAlreadyRemovedTarget() throws Exception {
        try (Harness harness = harness("permanent-release-absent.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            UUID oldOwner = UUID.randomUUID();
            insertScenario(
                    harness, "profile", npcUuid, oldOwner, null,
                    CompanionLifecycleState.ACTIVE, CompanionLifecycleState.RELEASED,
                    "default", "default", OwnerPopulationOperation.OWNER_CLEAR,
                    CompanionPopulationOperationRecord.State.APPLYING, false
            );
            markPermanentRelease(harness, npcUuid);

            CompanionPopulationOperationRecoveryService.RecoveryResult result = harness.recover(List.of());

            assertEquals(1, result.committed());
            assertTrue(result.complete());
            assertEquals(null, harness.state().ownerUuid());
            assertEquals(CompanionLifecycleState.RELEASED.name(), harness.state().lifecycleState());
            assertEquals(null, harness.state().physicalWorldName());
            assertEquals(CompanionPopulationOperationRecord.State.COMMITTED, harness.operationState());
        }
    }

    @Test
    void permanentReleaseCommitsWhenOwnerWriteAppliedBeforeFatalDamage() throws Exception {
        try (Harness harness = harness("permanent-release-ownerless.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            UUID oldOwner = UUID.randomUUID();
            insertScenario(
                    harness, "profile", npcUuid, oldOwner, null,
                    CompanionLifecycleState.ACTIVE, CompanionLifecycleState.RELEASED,
                    "default", "default", OwnerPopulationOperation.OWNER_CLEAR,
                    CompanionPopulationOperationRecord.State.APPLYING, false
            );
            markPermanentRelease(harness, npcUuid);

            CompanionPopulationOperationRecoveryService.RecoveryResult result = harness.recover(List.of(
                    physical(npcUuid, null, "default", 0, 0)
            ));

            assertEquals(1, result.committed());
            assertTrue(result.complete());
            assertEquals(null, harness.state().ownerUuid());
            assertEquals(CompanionLifecycleState.RELEASED.name(), harness.state().lifecycleState());
        }
    }

    @Test
    void permanentDeathDoesNotTreatAnOwnerlessLiveEntityAsCompletedDeath() throws Exception {
        try (Harness harness = harness("permanent-death-ownerless-live.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            UUID oldOwner = UUID.randomUUID();
            insertScenario(
                    harness, "profile", npcUuid, oldOwner, null,
                    CompanionLifecycleState.ACTIVE, CompanionLifecycleState.RELEASED,
                    "default", "default", OwnerPopulationOperation.OWNER_CLEAR,
                    CompanionPopulationOperationRecord.State.APPLYING, false
            );
            markPermanentDeath(harness, npcUuid);

            CompanionPopulationOperationRecoveryService.RecoveryResult result = harness.recover(List.of(
                    physical(npcUuid, null, "default", 0, 0)
            ));

            assertFalse(result.complete());
            assertEquals(
                    "operation-recovery-permanent-death-target-still-physical",
                    result.ambiguous().getFirst().reason()
            );
            assertEquals(CompanionPopulationOperationRecord.State.APPLYING, harness.operationState());
        }
    }

    @Test
    void permanentDeathCommitsWhenOwnerClearAndDeathComponentWereBothApplied() throws Exception {
        try (Harness harness = harness("permanent-death-ownerless-dead.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            UUID oldOwner = UUID.randomUUID();
            insertScenario(
                    harness, "profile", npcUuid, oldOwner, null,
                    CompanionLifecycleState.ACTIVE, CompanionLifecycleState.RELEASED,
                    "default", "default", OwnerPopulationOperation.OWNER_CLEAR,
                    CompanionPopulationOperationRecord.State.APPLYING, false
            );
            markPermanentDeath(harness, npcUuid);

            CompanionPopulationOperationRecoveryService.RecoveryResult result = harness.recover(List.of(
                    physicalDead(npcUuid, null, "default", 0, 0)
            ));

            assertTrue(result.complete());
            assertEquals(1, result.committed());
            assertEquals(CompanionPopulationOperationRecord.State.COMMITTED, harness.operationState());
            assertEquals(CompanionLifecycleState.RELEASED.name(), harness.state().lifecycleState());
        }
    }

    @Test
    void permanentDeathCommitsAfterTargetDisappears() throws Exception {
        try (Harness harness = harness("permanent-death-absent.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            UUID oldOwner = UUID.randomUUID();
            insertScenario(
                    harness, "profile", npcUuid, oldOwner, null,
                    CompanionLifecycleState.ACTIVE, CompanionLifecycleState.RELEASED,
                    "default", "default", OwnerPopulationOperation.OWNER_CLEAR,
                    CompanionPopulationOperationRecord.State.APPLYING, false
            );
            markPermanentDeath(harness, npcUuid);

            CompanionPopulationOperationRecoveryService.RecoveryResult result = harness.recover(List.of());

            assertTrue(result.complete());
            assertEquals(1, result.committed());
            assertEquals(CompanionPopulationOperationRecord.State.COMMITTED, harness.operationState());
        }
    }

    @Test
    void permanentReleaseFailsClosedWhenOldOwnerWriteWasNotApplied() throws Exception {
        try (Harness harness = harness("permanent-release-old-owner.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            UUID oldOwner = UUID.randomUUID();
            insertScenario(
                    harness, "profile", npcUuid, oldOwner, null,
                    CompanionLifecycleState.ACTIVE, CompanionLifecycleState.RELEASED,
                    "default", "default", OwnerPopulationOperation.OWNER_CLEAR,
                    CompanionPopulationOperationRecord.State.APPLYING, false
            );
            markPermanentRelease(harness, npcUuid);

            CompanionPopulationOperationRecoveryService.RecoveryResult result = harness.recover(List.of(
                    physical(npcUuid, oldOwner, "default", 0, 0)
            ));

            assertEquals(0, result.committed());
            assertEquals(1, result.canceled());
            assertTrue(result.complete());
            assertEquals(oldOwner, harness.state().ownerUuid());
            assertEquals(CompanionPopulationOperationRecord.State.FAILED, harness.operationState());
        }
    }

    @Test
    void permanentReleaseFailsClosedWhenEvidenceDidNotObserveOwnerState() throws Exception {
        try (Harness harness = harness("permanent-release-owner-unknown.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            UUID oldOwner = UUID.randomUUID();
            insertScenario(
                    harness, "profile", npcUuid, oldOwner, null,
                    CompanionLifecycleState.ACTIVE, CompanionLifecycleState.RELEASED,
                    "default", "default", OwnerPopulationOperation.OWNER_CLEAR,
                    CompanionPopulationOperationRecord.State.APPLYING, false
            );
            markPermanentRelease(harness, npcUuid);
            CompanionPopulationEvidence unknownOwner = new CompanionPopulationEvidence(
                    "captured-item-" + npcUuid,
                    npcUuid,
                    null,
                    false,
                    CompanionPopulationEvidence.Kind.CAPTURED_ITEM,
                    "default",
                    null,
                    null,
                    null,
                    "test"
            );

            CompanionPopulationOperationRecoveryService.RecoveryResult result = harness.recover(
                    List.of(unknownOwner)
            );

            assertFalse(result.complete());
            assertEquals("operation-recovery-owner-not-observed", result.ambiguous().getFirst().reason());
            assertEquals(oldOwner, harness.state().ownerUuid());
            assertEquals(CompanionPopulationOperationRecord.State.APPLYING, harness.operationState());
        }
    }

    @Test
    void ordinaryOwnerClearStillRequiresObservedAppliedState() throws Exception {
        try (Harness harness = harness("ordinary-owner-clear-absent.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            UUID oldOwner = UUID.randomUUID();
            insertScenario(
                    harness, "profile", npcUuid, oldOwner, null,
                    CompanionLifecycleState.ACTIVE, CompanionLifecycleState.RELEASED,
                    "default", "default", OwnerPopulationOperation.OWNER_CLEAR,
                    CompanionPopulationOperationRecord.State.APPLYING, false
            );

            CompanionPopulationOperationRecoveryService.RecoveryResult result = harness.recover(List.of());

            assertFalse(result.complete());
            assertEquals("operation-recovery-target-not-observed", result.ambiguous().getFirst().reason());
            assertEquals(oldOwner, harness.state().ownerUuid());
            assertEquals(CompanionPopulationOperationRecord.State.APPLYING, harness.operationState());
        }
    }

    @Test
    void sameOwnerCaptureRestoreAndRehomeRecoverFromLifecycleAndLocation() throws Exception {
        UUID ownerUuid = UUID.randomUUID();
        try (Harness capture = harness("same-owner-capture.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            insertScenario(
                    capture, "profile", npcUuid, ownerUuid, ownerUuid,
                    CompanionLifecycleState.ACTIVE, CompanionLifecycleState.CAPTURED,
                    "default", "default", OwnerPopulationOperation.LIFECYCLE_CHANGE,
                    CompanionPopulationOperationRecord.State.APPLYING, false
            );
            assertEquals(1, capture.recover(List.of(dormant(
                    npcUuid, ownerUuid, CompanionPopulationEvidence.Kind.CAPTURED_SNAPSHOT, "default"
            ))).committed());
            assertEquals(CompanionLifecycleState.CAPTURED.name(), capture.state().lifecycleState());
        }
        try (Harness restore = harness("same-owner-restore.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            insertScenario(
                    restore, "profile", npcUuid, ownerUuid, ownerUuid,
                    CompanionLifecycleState.CAPTURED, CompanionLifecycleState.ACTIVE,
                    "default", "default", OwnerPopulationOperation.RESTORE,
                    CompanionPopulationOperationRecord.State.APPLYING, false
            );
            assertEquals(1, restore.recover(List.of(physical(
                    npcUuid, ownerUuid, "default", 0, 0
            ))).committed());
            assertEquals(CompanionLifecycleState.UNLOADED.name(), restore.state().lifecycleState());
        }
        try (Harness rehome = harness("same-owner-rehome.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            insertScenario(
                    rehome, "profile", npcUuid, ownerUuid, ownerUuid,
                    CompanionLifecycleState.ACTIVE, CompanionLifecycleState.ACTIVE,
                    "alpha", "beta", OwnerPopulationOperation.REHOME,
                    CompanionPopulationOperationRecord.State.APPLYING, false
            );
            assertEquals(1, rehome.recover(List.of(physical(
                    npcUuid, ownerUuid, "beta", 0, 0
            ))).committed());
            assertEquals("beta", rehome.state().ownershipWorldName());
        }
    }

    @Test
    void applyingBreedingCommitsWhenChildExistsAndClosesWhenCompleteEvidenceIsAbsent() throws Exception {
        UUID ownerUuid = UUID.randomUUID();
        try (Harness present = harness("breeding-present.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            insertScenario(
                    present, "profile", npcUuid, null, ownerUuid,
                    CompanionLifecycleState.ACTIVE, CompanionLifecycleState.ACTIVE,
                    "default", "default", OwnerPopulationOperation.BREEDING,
                    CompanionPopulationOperationRecord.State.APPLYING, true
            );
            CompanionPopulationOperationRecoveryService.RecoveryResult result = present.recover(List.of(
                    physical(npcUuid, ownerUuid, "default", 0, 0)
            ));
            assertEquals(1, result.committed());
            assertEquals(ownerUuid, present.state().ownerUuid());
        }
        try (Harness absent = harness("breeding-absent.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            insertScenario(
                    absent, "profile", npcUuid, null, ownerUuid,
                    CompanionLifecycleState.ACTIVE, CompanionLifecycleState.ACTIVE,
                    "default", "default", OwnerPopulationOperation.BREEDING,
                    CompanionPopulationOperationRecord.State.APPLYING, true
            );
            CompanionPopulationOperationRecoveryService.RecoveryResult result = absent.recover(List.of(
                    dormant(npcUuid, null, CompanionPopulationEvidence.Kind.PROFILE_RECORD, "default")
            ));
            assertEquals(0, result.committed());
            assertEquals(1, result.canceled());
            assertTrue(result.complete());
        }
    }

    @Test
    void everyExplicitDormantKindCommitsItsExactLifecycle() throws Exception {
        List<DormantCase> cases = List.of(
                new DormantCase(CompanionPopulationEvidence.Kind.CAPTURED_SNAPSHOT,
                        CompanionLifecycleState.CAPTURED),
                new DormantCase(CompanionPopulationEvidence.Kind.DEATH_SNAPSHOT,
                        CompanionLifecycleState.DEAD_REVIVABLE),
                new DormantCase(CompanionPopulationEvidence.Kind.LOST_SNAPSHOT,
                        CompanionLifecycleState.LOST),
                new DormantCase(CompanionPopulationEvidence.Kind.COOP_SNAPSHOT,
                        CompanionLifecycleState.COOP)
        );
        for (DormantCase testCase : cases) {
            try (Harness harness = harness("dormant-" + testCase.lifecycle() + ".sqlite")) {
                UUID npcUuid = UUID.randomUUID();
                UUID ownerUuid = UUID.randomUUID();
                insertScenario(
                        harness, "profile", npcUuid, ownerUuid, ownerUuid,
                        CompanionLifecycleState.ACTIVE, testCase.lifecycle(),
                        "default", "default", OwnerPopulationOperation.LIFECYCLE_CHANGE,
                        CompanionPopulationOperationRecord.State.APPLYING, false
                );

                CompanionPopulationOperationRecoveryService.RecoveryResult result = harness.recover(List.of(
                        dormant(npcUuid, ownerUuid, testCase.kind(), "default")
                ));

                assertEquals(1, result.committed(), testCase.lifecycle().name());
                assertEquals(testCase.lifecycle().name(), harness.state().lifecycleState());
            }
        }
    }

    private void assertOwnerDeltaCommits(OwnerPopulationOperation operation,
                                         UUID oldOwner,
                                         UUID newOwner,
                                         String file) throws Exception {
        try (Harness harness = harness(file + ".sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            insertScenario(
                    harness, "profile", npcUuid, oldOwner, newOwner,
                    CompanionLifecycleState.ACTIVE, CompanionLifecycleState.ACTIVE,
                    "default", "default", operation,
                    CompanionPopulationOperationRecord.State.APPLYING, false
            );

            CompanionPopulationOperationRecoveryService.RecoveryResult result = harness.recover(List.of(
                    physical(npcUuid, newOwner, "default", 0, 0)
            ));

            assertEquals(1, result.committed());
            assertTrue(result.complete());
            assertEquals(newOwner, harness.state().ownerUuid());
            assertEquals(CompanionLifecycleState.UNLOADED.name(), harness.state().lifecycleState());
        }
    }

    private Harness harness(String file) throws Exception {
        return CompanionPopulationOperationRecoveryTestSupport.open(tempDir, file);
    }

    private static void markPermanentRelease(Harness harness, UUID npcUuid) throws Exception {
        updateTargetContext(
                harness,
                "{\"npcUuid\":\"" + npcUuid
                        + "\",\"world\":\"default\",\"chunkX\":0,\"chunkZ\":0,"
                        + "\"permanentRelease\":true}"
        );
    }

    private static void markPermanentDeath(Harness harness, UUID npcUuid) throws Exception {
        updateTargetContext(
                harness,
                "{\"npcUuid\":\"" + npcUuid
                        + "\",\"world\":\"default\",\"chunkX\":0,\"chunkZ\":0,"
                        + "\"permanentRelease\":true,\"permanentDeath\":true}"
        );
    }

    private record DormantCase(CompanionPopulationEvidence.Kind kind,
                               CompanionLifecycleState lifecycle) {
    }

}
