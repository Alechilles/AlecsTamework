package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex;
import com.alechilles.alecstamework.items.LoadedNpcIdentitySnapshot;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.OwnerPopulationOperation;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationOperationRecoveryTestSupport.Harness;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationOperationRecoveryTestSupport.dormant;
import static com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationOperationRecoveryTestSupport.insertScenario;
import static com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationOperationRecoveryTestSupport.markPermanentDeath;
import static com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationOperationRecoveryTestSupport.markPermanentRelease;
import static com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationOperationRecoveryTestSupport.physical;
import static com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationOperationRecoveryTestSupport.physicalDead;
import static com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationOperationRecoveryTestSupport.updateTargetContext;
import static com.alechilles.alecstamework.ownership.reconciliation.CompanionProjectionRecoveryTestSupport.breedingMarker;
import static com.alechilles.alecstamework.ownership.reconciliation.CompanionProjectionRecoveryTestSupport.coopReleaseMarker;
import static com.alechilles.alecstamework.ownership.reconciliation.CompanionProjectionRecoveryTestSupport.operationRecord;
import static com.alechilles.alecstamework.ownership.reconciliation.CompanionProjectionRecoveryTestSupport.prepareManagedCoopRelease;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void compensatingCrashClosesOnlyAfterTheExactOldStateIsObserved() throws Exception {
        try (Harness harness = harness("compensation-old-state.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            UUID oldOwner = UUID.randomUUID();
            insertScenario(
                    harness, "profile", npcUuid, oldOwner, UUID.randomUUID(),
                    CompanionLifecycleState.ACTIVE, CompanionLifecycleState.ACTIVE,
                    "default", "default", OwnerPopulationOperation.OWNER_TRANSFER,
                    CompanionPopulationOperationRecord.State.COMPENSATING, false
            );

            CompanionPopulationOperationRecoveryService.RecoveryResult result = harness.recover(List.of(
                    physical(npcUuid, oldOwner, "default", 0, 0)
            ));

            assertTrue(result.complete());
            assertEquals(0, result.committed());
            assertEquals(1, result.canceled());
            assertEquals(CompanionPopulationOperationRecord.State.FAILED, harness.operationState());
        }
    }

    @Test
    void compensatingCrashWithPartialLiveStateRemainsQuarantined() throws Exception {
        try (Harness harness = harness("compensation-partial.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            UUID oldOwner = UUID.randomUUID();
            UUID newOwner = UUID.randomUUID();
            insertScenario(
                    harness, "profile", npcUuid, oldOwner, newOwner,
                    CompanionLifecycleState.ACTIVE, CompanionLifecycleState.ACTIVE,
                    "default", "default", OwnerPopulationOperation.OWNER_TRANSFER,
                    CompanionPopulationOperationRecord.State.COMPENSATING, false
            );

            CompanionPopulationOperationRecoveryService.RecoveryResult result = harness.recover(List.of(
                    physical(npcUuid, newOwner, "default", 0, 0)
            ));

            assertFalse(result.complete());
            assertEquals(
                    "operation-recovery-compensation-incomplete",
                    result.ambiguous().getFirst().reason()
            );
            assertEquals(
                    CompanionPopulationOperationRecord.State.COMPENSATING,
                    harness.operationState()
            );
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
    void applyingRevivableDeathCommitsCorpseAsDeadInsteadOfUnloaded() throws Exception {
        try (Harness harness = harness("revivable-death-corpse.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            insertScenario(
                    harness, "profile", npcUuid, ownerUuid, ownerUuid,
                    CompanionLifecycleState.ACTIVE, CompanionLifecycleState.DEAD_REVIVABLE,
                    "default", "default", OwnerPopulationOperation.LIFECYCLE_CHANGE,
                    CompanionPopulationOperationRecord.State.APPLYING, false
            );

            CompanionPopulationOperationRecoveryService.RecoveryResult result = harness.recover(List.of(
                    physicalDead(npcUuid, ownerUuid, "default", 0, 0)
            ));

            assertTrue(result.complete());
            assertEquals(1, result.committed());
            assertEquals(CompanionLifecycleState.DEAD_REVIVABLE.name(), harness.state().lifecycleState());
            assertEquals("default", harness.state().physicalWorldName());
        }
    }

    @Test
    void applyingReviveClosesWhenOnlyTheOldCorpseStillExists() throws Exception {
        try (Harness harness = harness("revive-corpse-still-present.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            insertScenario(
                    harness, "profile", npcUuid, ownerUuid, ownerUuid,
                    CompanionLifecycleState.DEAD_REVIVABLE, CompanionLifecycleState.ACTIVE,
                    "default", "default", OwnerPopulationOperation.RESTORE,
                    CompanionPopulationOperationRecord.State.APPLYING, false
            );

            CompanionPopulationOperationRecoveryService.RecoveryResult result = harness.recover(List.of(
                    physicalDead(npcUuid, ownerUuid, "default", 0, 0)
            ));

            assertTrue(result.complete());
            assertEquals(0, result.committed());
            assertEquals(1, result.canceled());
            assertEquals(CompanionLifecycleState.DEAD_REVIVABLE.name(), harness.state().lifecycleState());
            assertEquals(CompanionPopulationOperationRecord.State.FAILED, harness.operationState());
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
    void interruptedRosterStoreCommitsAfterCompleteScanProvesProjectionAbsent() throws Exception {
        try (Harness harness = harness("roster-store-absent.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            insertScenario(
                    harness, "profile", npcUuid, ownerUuid, ownerUuid,
                    CompanionLifecycleState.STORING, CompanionLifecycleState.ROSTER_STORED,
                    "default", "default", OwnerPopulationOperation.LIFECYCLE_CHANGE,
                    CompanionPopulationOperationRecord.State.APPLYING, false
            );

            CompanionPopulationOperationRecoveryService.RecoveryResult result =
                    harness.recover(List.of(), new LoadedNpcIdentitySnapshot(7L, true, List.of()));

            assertTrue(result.complete());
            assertEquals(1, result.committed());
            assertEquals(CompanionPopulationOperationRecord.State.COMMITTED, harness.operationState());
            assertEquals(CompanionLifecycleState.ROSTER_STORED.name(), harness.state().lifecycleState());
            assertEquals(null, harness.state().physicalWorldName());
            assertEquals(null, harness.state().physicalChunkX());
            assertEquals(null, harness.state().physicalChunkZ());
        }
    }

    @Test
    void interruptedRosterStoreRemainsAmbiguousWhenTargetIsStillLoaded() throws Exception {
        try (Harness harness = harness("roster-store-loaded.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            insertScenario(
                    harness, "profile", npcUuid, ownerUuid, ownerUuid,
                    CompanionLifecycleState.STORING, CompanionLifecycleState.ROSTER_STORED,
                    "default", "default", OwnerPopulationOperation.LIFECYCLE_CHANGE,
                    CompanionPopulationOperationRecord.State.APPLYING, false
            );
            LoadedNpcIdentitySnapshot loaded = new LoadedNpcIdentitySnapshot(
                    8L,
                    true,
                    List.of(new LoadedNpcIdentityIndex.LoadedNpcObservation(
                            npcUuid,
                            npcUuid,
                            new LoadedNpcIdentityIndex.Location("default", "store-default"),
                            null
                    ))
            );

            CompanionPopulationOperationRecoveryService.RecoveryResult result =
                    harness.recover(List.of(), loaded);

            assertFalse(result.complete());
            assertEquals(
                    "operation-recovery-live-target-not-persisted",
                    result.ambiguous().getFirst().reason()
            );
            assertEquals(CompanionPopulationOperationRecord.State.APPLYING, harness.operationState());
            assertEquals(CompanionLifecycleState.STORING.name(), harness.state().lifecycleState());
        }
    }

    @Test
    void interruptedRosterStoreRequiresACompleteLoadedIdentityScan() throws Exception {
        try (Harness harness = harness("roster-store-incomplete-scan.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            insertScenario(
                    harness, "profile", npcUuid, ownerUuid, ownerUuid,
                    CompanionLifecycleState.STORING, CompanionLifecycleState.ROSTER_STORED,
                    "default", "default", OwnerPopulationOperation.LIFECYCLE_CHANGE,
                    CompanionPopulationOperationRecord.State.APPLYING, false
            );

            CompanionPopulationOperationRecoveryService.RecoveryResult result = harness.recover(
                    List.of(), new LoadedNpcIdentitySnapshot(9L, false, List.of())
            );

            assertFalse(result.complete());
            assertEquals(
                    "operation-recovery-loaded-identities-incomplete",
                    result.ambiguous().getFirst().reason()
            );
            assertEquals(CompanionPopulationOperationRecord.State.APPLYING, harness.operationState());
            assertEquals(CompanionLifecycleState.STORING.name(), harness.state().lifecycleState());
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
    void applyingBreedingCommitsWhenChildExistsAndMakesAbsentTargetsRetryable() throws Exception {
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
        try (Harness unownedPresent = harness("breeding-unowned-present.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            insertScenario(
                    unownedPresent, "profile", npcUuid, null, null,
                    CompanionLifecycleState.ACTIVE, CompanionLifecycleState.ACTIVE,
                    "default", "default", OwnerPopulationOperation.BREEDING,
                    CompanionPopulationOperationRecord.State.APPLYING, true
            );

            CompanionPopulationOperationRecoveryService.RecoveryResult result =
                    unownedPresent.recover(List.of(physical(
                            npcUuid, null, "default", 0, 0
                    )));

            assertEquals(1, result.committed());
            assertTrue(result.complete());
            assertNull(unownedPresent.state().ownerUuid());
            assertEquals(CompanionPopulationOperationRecord.State.COMMITTED,
                    unownedPresent.operationState());
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
            assertEquals(1, result.retryable());
            assertEquals(0, result.canceled());
            assertTrue(result.complete());
            assertEquals(CompanionPopulationOperationRecord.State.RETRYABLE,
                    absent.operationState());
        }
        try (Harness unowned = harness("breeding-unowned-absent.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            insertScenario(
                    unowned, "profile", npcUuid, null, null,
                    CompanionLifecycleState.ACTIVE, CompanionLifecycleState.ACTIVE,
                    "default", "default", OwnerPopulationOperation.BREEDING,
                    CompanionPopulationOperationRecord.State.APPLYING, true
            );

            CompanionPopulationOperationRecoveryService.RecoveryResult result = unowned.recover(
                    List.of(dormant(
                            npcUuid, null, CompanionPopulationEvidence.Kind.PROFILE_RECORD, "default"
                    ))
            );

            assertEquals(0, result.committed());
            assertEquals(1, result.retryable());
            assertEquals(0, result.canceled());
            assertTrue(result.complete());
            assertEquals(CompanionPopulationOperationRecord.State.RETRYABLE,
                    unowned.operationState());
        }
    }

    @Test
    void preparedBreedingOperationRetainsItsExactReplayBaseline() throws Exception {
        try (Harness harness = harness("prepared-breeding.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            insertScenario(
                    harness, "profile", npcUuid, null, ownerUuid,
                    CompanionLifecycleState.ACTIVE, CompanionLifecycleState.ACTIVE,
                    "default", "default", OwnerPopulationOperation.BREEDING,
                    CompanionPopulationOperationRecord.State.PREPARED, true
            );

            CompanionPopulationOperationRecoveryService.RecoveryResult result =
                    harness.recover(List.of());

            assertEquals(0, result.committed());
            assertEquals(1, result.retryable());
            assertEquals(0, result.canceled());
            assertTrue(result.complete());
            assertEquals(
                    CompanionPopulationOperationRecord.State.RETRYABLE,
                    harness.operationState()
            );
            assertEquals(0L, harness.state().revision());
            assertEquals(npcUuid, harness.state().currentNpcUuid());
        }
    }

    @Test
    void loadedBreedingMarkerCannotBeMisclassifiedAsAbsentBeforeItsChunkSave() throws Exception {
        try (Harness harness = harness("prepared-breeding-live-marker.sqlite")) {
            UUID plannedNpcUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            insertScenario(
                    harness, "profile", plannedNpcUuid, null, ownerUuid,
                    CompanionLifecycleState.ACTIVE, CompanionLifecycleState.ACTIVE,
                    "default", "default", OwnerPopulationOperation.BREEDING,
                    CompanionPopulationOperationRecord.State.PREPARED, true
            );
            LoadedNpcIdentityIndex.ProjectionKey marker =
                    new LoadedNpcIdentityIndex.ProjectionKey(
                            "profile",
                            "attempt",
                            TameworkProjectionIdentityComponent.KIND_BREEDING_CHILD,
                            "child-0000",
                            plannedNpcUuid,
                            1L
                    );
            LoadedNpcIdentitySnapshot loaded = new LoadedNpcIdentitySnapshot(
                    5L,
                    true,
                    List.of(new LoadedNpcIdentityIndex.LoadedNpcObservation(
                            plannedNpcUuid,
                            plannedNpcUuid,
                            new LoadedNpcIdentityIndex.Location("default", "store-default"),
                            marker
                    ))
            );

            CompanionPopulationOperationRecoveryService.RecoveryResult result =
                    harness.recover(List.of(), loaded);

            assertFalse(result.complete());
            assertEquals(0, result.retryable());
            assertEquals(
                    "operation-recovery-projection-live-evidence-not-persisted",
                    result.ambiguous().getFirst().reason()
            );
            assertEquals(
                    CompanionPopulationOperationRecord.State.PREPARED,
                    harness.operationState()
            );
        }
    }

    @Test
    void compensatingBreedingWithNoChildClosesAsCanceled() throws Exception {
        try (Harness harness = harness("compensating-breeding-absent.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            insertScenario(
                    harness, "profile", npcUuid, null, UUID.randomUUID(),
                    CompanionLifecycleState.ACTIVE, CompanionLifecycleState.ACTIVE,
                    "default", "default", OwnerPopulationOperation.BREEDING,
                    CompanionPopulationOperationRecord.State.COMPENSATING, true
            );

            CompanionPopulationOperationRecoveryService.RecoveryResult result =
                    harness.recover(List.of());

            assertEquals(0, result.committed());
            assertEquals(1, result.canceled());
            assertTrue(result.complete());
            assertEquals(
                    CompanionPopulationOperationRecord.State.FAILED,
                    harness.operationState()
            );
        }
    }

    @Test
    void preparedBreedingWithExactPersistedMarkerCommitsInsteadOfRetrying() throws Exception {
        try (Harness harness = harness("prepared-breeding-exact-marker.sqlite")) {
            UUID plannedNpcUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            insertScenario(
                    harness, "profile", plannedNpcUuid, null, ownerUuid,
                    CompanionLifecycleState.ACTIVE, CompanionLifecycleState.ACTIVE,
                    "default", "default", OwnerPopulationOperation.BREEDING,
                    CompanionPopulationOperationRecord.State.PREPARED, true
            );

            CompanionPopulationOperationRecoveryService.RecoveryResult result = harness.recover(
                    List.of(breedingMarker(
                            plannedNpcUuid, plannedNpcUuid, plannedNpcUuid, ownerUuid, true,
                            "default", 0, 0, "exact"
                    ))
            );

            assertEquals(1, result.committed());
            assertEquals(0, result.retryable());
            assertTrue(result.complete());
            assertEquals(CompanionPopulationOperationRecord.State.COMMITTED,
                    harness.operationState());
            assertEquals(ownerUuid, harness.state().ownerUuid());
        }
    }

    @Test
    void persistedAlternateBreedingMarkerRetainsPreparedJournal() throws Exception {
        UUID plannedNpcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        try (Harness harness = harness("prepared-breeding-alternate-marker.sqlite")) {
            UUID alternateNpcUuid = UUID.randomUUID();
            insertScenario(
                    harness, "profile", plannedNpcUuid, null, ownerUuid,
                    CompanionLifecycleState.ACTIVE, CompanionLifecycleState.ACTIVE,
                    "default", "default", OwnerPopulationOperation.BREEDING,
                    CompanionPopulationOperationRecord.State.PREPARED, true
            );

            CompanionPopulationOperationRecoveryService.RecoveryResult result = harness.recover(
                    List.of(breedingMarker(
                            plannedNpcUuid, alternateNpcUuid, alternateNpcUuid, ownerUuid, true,
                            "default", 0, 0, "alternate"
                    ))
            );

            assertFalse(result.complete());
            assertEquals(0, result.retryable());
            assertEquals("operation-recovery-projection-evidence-identity-mismatch",
                    result.ambiguous().getFirst().reason());
            assertEquals(CompanionPopulationOperationRecord.State.PREPARED,
                    harness.operationState());
        }
        try (Harness harness = harness("prepared-breeding-missing-legacy-marker.sqlite")) {
            insertScenario(
                    harness, "profile", plannedNpcUuid, null, ownerUuid,
                    CompanionLifecycleState.ACTIVE, CompanionLifecycleState.ACTIVE,
                    "default", "default", OwnerPopulationOperation.BREEDING,
                    CompanionPopulationOperationRecord.State.PREPARED, true
            );
            CompanionPopulationOperationRecoveryService.RecoveryResult result = harness.recover(
                    List.of(breedingMarker(
                            plannedNpcUuid, plannedNpcUuid, null, ownerUuid, true,
                            "default", 0, 0, "missing-legacy"
                    ))
            );
            assertFalse(result.complete());
            assertEquals(0, result.retryable());
            assertEquals("operation-recovery-projection-evidence-identity-mismatch",
                    result.ambiguous().getFirst().reason());
            assertEquals(CompanionPopulationOperationRecord.State.PREPARED,
                    harness.operationState());
        }
    }

    @Test
    void exactBreedingMarkerWithUnknownOwnerRemainsAmbiguous() throws Exception {
        UUID plannedNpcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        try (Harness harness = harness("breeding-marker-owner-unknown.sqlite")) {
            insertScenario(
                    harness, "profile", plannedNpcUuid, null, ownerUuid,
                    CompanionLifecycleState.ACTIVE, CompanionLifecycleState.ACTIVE,
                    "default", "default", OwnerPopulationOperation.BREEDING,
                    CompanionPopulationOperationRecord.State.APPLYING, true
            );

            CompanionPopulationOperationRecoveryService.RecoveryResult result = harness.recover(
                    List.of(breedingMarker(
                            plannedNpcUuid, plannedNpcUuid, plannedNpcUuid, ownerUuid, false,
                            "default", 0, 0, "unknown-owner"
                    ))
            );

            assertFalse(result.complete());
            assertEquals("operation-recovery-projection-owner-mismatch",
                    result.ambiguous().getFirst().reason());
            assertEquals(CompanionPopulationOperationRecord.State.APPLYING,
                    harness.operationState());
        }
        try (Harness harness = harness("breeding-marker-location-mismatch.sqlite")) {
            insertScenario(
                    harness, "profile", plannedNpcUuid, null, ownerUuid,
                    CompanionLifecycleState.ACTIVE, CompanionLifecycleState.ACTIVE,
                    "default", "default", OwnerPopulationOperation.BREEDING,
                    CompanionPopulationOperationRecord.State.APPLYING, true
            );
            CompanionPopulationOperationRecoveryService.RecoveryResult result = harness.recover(
                    List.of(breedingMarker(
                            plannedNpcUuid, plannedNpcUuid, plannedNpcUuid, ownerUuid, true,
                            "other", 0, 0, "wrong-location"
                    ))
            );
            assertEquals("operation-recovery-projection-location-mismatch",
                    result.ambiguous().getFirst().reason());
            assertEquals(CompanionPopulationOperationRecord.State.APPLYING,
                    harness.operationState());
        }
    }

    @Test
    void duplicateExactBreedingMarkersAndOrdinaryLocationMismatchStayAmbiguous() throws Exception {
        UUID plannedNpcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        try (Harness duplicate = harness("breeding-marker-duplicate.sqlite")) {
            insertScenario(
                    duplicate, "profile", plannedNpcUuid, null, ownerUuid,
                    CompanionLifecycleState.ACTIVE, CompanionLifecycleState.ACTIVE,
                    "default", "default", OwnerPopulationOperation.BREEDING,
                    CompanionPopulationOperationRecord.State.APPLYING, true
            );
            CompanionPopulationOperationRecoveryService.RecoveryResult result = duplicate.recover(
                    List.of(
                            breedingMarker(plannedNpcUuid, plannedNpcUuid, plannedNpcUuid,
                                    ownerUuid, true, "default", 0, 0, "duplicate-a"),
                            breedingMarker(plannedNpcUuid, plannedNpcUuid, plannedNpcUuid,
                                    ownerUuid, true, "default", 0, 0, "duplicate-b")
                    )
            );
            assertEquals("operation-recovery-projection-evidence-duplicated",
                    result.ambiguous().getFirst().reason());
        }
        try (Harness mismatch = harness("breeding-marker-ordinary-mismatch.sqlite")) {
            insertScenario(
                    mismatch, "profile", plannedNpcUuid, null, ownerUuid,
                    CompanionLifecycleState.ACTIVE, CompanionLifecycleState.ACTIVE,
                    "default", "default", OwnerPopulationOperation.BREEDING,
                    CompanionPopulationOperationRecord.State.APPLYING, true
            );
            CompanionPopulationOperationRecoveryService.RecoveryResult result = mismatch.recover(
                    List.of(
                            breedingMarker(plannedNpcUuid, plannedNpcUuid, plannedNpcUuid,
                                    ownerUuid, true, "default", 0, 0, "marker"),
                            physical(plannedNpcUuid, ownerUuid, "other", 0, 0)
                    )
            );
            assertEquals("operation-recovery-projection-ordinary-evidence-mismatch",
                    result.ambiguous().getFirst().reason());
        }
        try (Harness agrees = harness("breeding-marker-ordinary-agrees.sqlite")) {
            insertScenario(
                    agrees, "profile", plannedNpcUuid, null, ownerUuid,
                    CompanionLifecycleState.ACTIVE, CompanionLifecycleState.ACTIVE,
                    "default", "default", OwnerPopulationOperation.BREEDING,
                    CompanionPopulationOperationRecord.State.APPLYING, true
            );
            CompanionPopulationOperationRecoveryService.RecoveryResult result = agrees.recover(
                    List.of(
                            breedingMarker(plannedNpcUuid, plannedNpcUuid, plannedNpcUuid,
                                    ownerUuid, true, "default", 0, 0, "marker"),
                            physical(plannedNpcUuid, ownerUuid, "default", 0, 0)
                    )
            );
            assertEquals(1, result.committed());
            assertTrue(result.complete());
        }
    }

    @Test
    void malformedBreedingProjectionMetadataDoesNotBecomeRetryable() throws Exception {
        try (Harness harness = harness("breeding-marker-metadata-invalid.sqlite")) {
            UUID plannedNpcUuid = UUID.randomUUID();
            insertScenario(
                    harness, "profile", plannedNpcUuid, null, UUID.randomUUID(),
                    CompanionLifecycleState.ACTIVE, CompanionLifecycleState.ACTIVE,
                    "default", "default", OwnerPopulationOperation.BREEDING,
                    CompanionPopulationOperationRecord.State.APPLYING, true
            );
            updateTargetContext(harness,
                    "{\"idempotencyKey\":\"attempt\",\"plannedNpcUuid\":\""
                            + plannedNpcUuid
                            + "\",\"world\":\"default\",\"chunkX\":0,\"chunkZ\":0}");

            CompanionPopulationOperationRecoveryService.RecoveryResult result =
                    harness.recover(List.of());

            assertFalse(result.complete());
            assertEquals(0, result.retryable());
            assertEquals("operation-recovery-projection-metadata-invalid",
                    result.ambiguous().getFirst().reason());
            assertEquals(CompanionPopulationOperationRecord.State.APPLYING,
                    harness.operationState());
        }
    }

    @Test
    void exactPersistedBreedingCorpseNeverRecoversAsALiveChild() throws Exception {
        try (Harness harness = harness("breeding-marker-dead.sqlite")) {
            UUID plannedNpcUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            insertScenario(
                    harness, "profile", plannedNpcUuid, null, ownerUuid,
                    CompanionLifecycleState.ACTIVE, CompanionLifecycleState.ACTIVE,
                    "default", "default", OwnerPopulationOperation.BREEDING,
                    CompanionPopulationOperationRecord.State.APPLYING, true
            );
            CompanionPopulationOperationRecoveryService.RecoveryResult result = harness.recover(
                    List.of(breedingMarker(
                            plannedNpcUuid, plannedNpcUuid, plannedNpcUuid, ownerUuid, true,
                            "default", 0, 0, "dead", true
                    ))
            );

            assertFalse(result.complete());
            assertEquals(0, result.committed());
            assertEquals(0, result.retryable());
            assertEquals(CompanionPopulationOperationRecord.State.APPLYING,
                    harness.operationState());
        }
    }

    @Test
    void managedCoopPersistedMarkerResolvesExactAndQuarantinesAlternateIdentity() throws Exception {
        UUID previousNpcUuid = UUID.randomUUID();
        UUID plannedNpcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        ManagedCoopAuthorityKey authority = new ManagedCoopAuthorityKey("default", 10, 20, 30);
        try (Harness exact = harness("coop-release-marker-exact.sqlite")) {
            prepareManagedCoopRelease(
                    exact, previousNpcUuid, plannedNpcUuid, ownerUuid, authority);
            CompanionPopulationOperationRecoveryService.RecoveryResult result = exact.recover(
                    List.of(coopReleaseMarker(
                            previousNpcUuid, plannedNpcUuid, plannedNpcUuid, ownerUuid,
                            authority, "exact"
                    ))
            );
            assertEquals(1, result.committed(), result.toString());
            assertTrue(result.complete());
            assertEquals(plannedNpcUuid, exact.state().currentNpcUuid());
        }
        try (Harness alternate = harness("coop-release-marker-alternate.sqlite")) {
            UUID alternateNpcUuid = UUID.randomUUID();
            prepareManagedCoopRelease(
                    alternate, previousNpcUuid, plannedNpcUuid, ownerUuid, authority);
            CompanionPopulationOperationRecoveryService.RecoveryResult result = alternate.recover(
                    List.of(coopReleaseMarker(
                            previousNpcUuid, alternateNpcUuid, alternateNpcUuid, ownerUuid,
                            authority, "alternate"
                    ))
            );
            assertFalse(result.complete());
            assertEquals("operation-recovery-projection-evidence-identity-mismatch",
                    result.ambiguous().getFirst().reason());
            assertEquals(CompanionPopulationOperationRecord.State.APPLYING,
                    alternate.operationState());
        }
    }

    @Test
    void nonProjectionContextsDoNotInventProjectionMetadataFailures() {
        UUID ownerUuid = UUID.randomUUID();
        for (String target : new String[]{null, " ", "{\"npcUuid\":\""
                + UUID.randomUUID() + "\"}"}) {
            CompanionPopulationOperationRecord operation = operationRecord(
                    OwnerPopulationOperation.RESTORE,
                    CompanionPopulationOperationRecord.State.PREPARED,
                    ownerUuid,
                    CompanionLifecycleState.COOP,
                    ownerUuid,
                    CompanionLifecycleState.ACTIVE,
                    target
            );
            CompanionOperationProjectionExpectationResolver.Resolution resolution =
                    CompanionOperationProjectionExpectationResolver.resolve(
                            operation, new CompanionPopulationEvidenceSet(List.of()));
            assertNull(resolution.ambiguityReason());
            assertNull(resolution.exactEvidence());
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

    private record DormantCase(CompanionPopulationEvidence.Kind kind,
                               CompanionLifecycleState lifecycle) {
    }

}
