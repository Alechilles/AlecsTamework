package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.OwnerPopulationCapDecisionViewV2;
import com.alechilles.alecstamework.api.PopulationAdmissionToken;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.ownership.CompanionRelocationAdmissionService;
import java.util.UUID;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministically guards the queued relocation capability state machine. */
class PendingRelocationAdmissionStateTest {

    @Test
    void preparationInstallsReservedCapabilityUntilExplicitFinalClaim() {
        PendingRelocation pending = pending();
        CompanionRelocationAdmissionService.Admission admission = admission();

        assertTrue(pending.beginAdmissionPreparation());
        assertTrue(pending.installReservedAdmission(decision(
                CompanionRelocationAdmissionService.Status.RESERVED, admission
        )));
        assertEquals("RESERVED", pending.admissionPhaseName());
        assertTrue(pending.admissionReserved());
        assertFalse(pending.admissionApplying());

        assertEquals(admission, pending.beginApplyClaim());
        assertEquals("CLAIMING", pending.admissionPhaseName());
        assertEquals(PendingRelocation.ClaimCompletion.APPLYING, pending.finishApplyClaim(
                admission,
                decision(CompanionRelocationAdmissionService.Status.APPLYING, admission),
                false
        ));
        assertEquals("APPLYING", pending.admissionPhaseName());
        assertTrue(pending.physicalMutationAttempted(),
                "APPLYING must atomically arm conservative terminal handling before mutation returns.");
    }

    @Test
    void cancellationClosesBothReservedAndApplyingCapabilities() {
        PendingRelocation reserved = reserved();
        assertNotNull(reserved.beginCancellation());
        reserved.finishCancellation(false);
        assertEquals("TERMINAL", reserved.admissionPhaseName());

        PendingRelocation applying = reserved();
        CompanionRelocationAdmissionService.Admission admission = applying.beginApplyClaim();
        assertNotNull(admission);
        assertEquals(PendingRelocation.ClaimCompletion.APPLYING, applying.finishApplyClaim(
                admission,
                decision(CompanionRelocationAdmissionService.Status.APPLYING, admission),
                false
        ));
        assertNotNull(applying.beginCancellation());
        applying.finishCancellation(false);
        assertEquals("TERMINAL", applying.admissionPhaseName());
    }

    @Test
    void cancellationRequestedDuringSynchronousClaimWinsBeforeMutation() {
        PendingRelocation pending = reserved();
        CompanionRelocationAdmissionService.Admission admission = pending.beginApplyClaim();

        assertNotNull(admission);
        assertNull(pending.beginCancellation());
        assertEquals(PendingRelocation.ClaimCompletion.CANCEL_REQUIRED, pending.finishApplyClaim(
                admission,
                decision(CompanionRelocationAdmissionService.Status.APPLYING, admission),
                false
        ));
        assertEquals("CANCELING", pending.admissionPhaseName());
        assertFalse(pending.physicalMutationAttempted());
        pending.finishCancellation(false);
        assertEquals("TERMINAL", pending.admissionPhaseName());
    }

    @Test
    void commitCanOnlyStartFromApplyingAndAlwaysTerminalizesLocally() {
        PendingRelocation pending = reserved();
        assertNull(pending.beginCommit());
        CompanionRelocationAdmissionService.Admission admission = pending.beginApplyClaim();
        pending.finishApplyClaim(
                admission,
                decision(CompanionRelocationAdmissionService.Status.APPLYING, admission),
                false
        );

        assertEquals(admission, pending.beginCommit());
        assertEquals("COMMITTING", pending.admissionPhaseName());
        assertTrue(pending.admissionCommitInProgress());
        assertNull(pending.beginCommit());
        pending.finishCommit();
        assertEquals("TERMINAL", pending.admissionPhaseName());
    }

    @Test
    void confirmationRetryDoesNotForgetAnUncompensatedPhysicalAttempt() {
        PendingRelocation pending = pending();

        pending.markRelocationIssued(10L);
        pending.resetRelocationIssue();

        assertFalse(pending.relocationIssued);
        assertTrue(pending.physicalMutationAttempted());
        pending.markPhysicalMutationCompensated();
        assertFalse(pending.physicalMutationAttempted());
    }

    @Test
    void crossWorldAttemptEvidenceSurvivesTransferCompletion() {
        PendingRelocation pending = pending();

        assertFalse(pending.crossWorldTransferAttempted());
        assertTrue(pending.markCrossWorldTransferStarted());
        pending.markCrossWorldTransferFinished();

        assertTrue(pending.crossWorldTransferAttempted());
        assertFalse(pending.isCrossWorldTransferInProgress());
    }

    @Test
    void optimisticClaimConflictReturnsToPreparationInsteadOfTerminalizing() {
        PendingRelocation pending = reserved();
        CompanionRelocationAdmissionService.Admission admission = pending.beginApplyClaim();

        assertEquals(PendingRelocation.ClaimCompletion.RETRY_REQUIRED, pending.finishApplyClaim(
                admission,
                new CompanionRelocationAdmissionService.Decision(
                        CompanionRelocationAdmissionService.Status.DENIED,
                        "claim-occupancy-state-mismatch",
                        null
                ),
                true
        ));
        assertEquals("NONE", pending.admissionPhaseName());
        assertFalse(pending.physicalMutationAttempted());
        assertTrue(pending.beginAdmissionPreparation());
    }

    @Test
    void repeatedRecallIntentCoalescesWithoutTreatingAnotherCommandAsDuplicate() {
        PendingRelocation original = pendingAt(new Vector3d(10.0, 20.0, 30.0), "Follow");
        PendingRelocation movedPlayerRetry = pendingAt(new Vector3d(14.0, 20.0, 34.0), "Follow");
        PendingRelocation differentCommand = pendingAt(new Vector3d(10.0, 20.0, 30.0), "Hold");
        PendingRelocation differentWorld = pendingAt(
                new Vector3d(10.0, 20.0, 30.0), "Follow", "another-world"
        );

        assertTrue(original.hasSameCommandIntent(movedPlayerRetry));
        assertFalse(original.hasSameCommandIntent(differentCommand));
        assertFalse(original.hasSameCommandIntent(differentWorld));
    }

    /** Regression for two worlds that use the same chunk coordinates during a transfer. */
    @Test
    void chunkRequestCooldownIsScopedByWorldAndCoordinates() {
        PendingRelocation pending = pending();

        assertTrue(pending.shouldRequestChunk("source", 4, 9, 1_000L, 1_500L));
        assertFalse(pending.shouldRequestChunk("source", 4, 9, 1_200L, 1_500L));
        assertTrue(pending.shouldRequestChunk("destination", 4, 9, 1_200L, 1_500L));
    }

    @Test
    void chunkReadinessIsScopedByWorldAndCoordinates() {
        PendingRelocation pending = pending();

        pending.markChunkReady("destination", 4, 9);

        assertTrue(pending.isChunkReady("destination", 4, 9));
        assertFalse(pending.isChunkReady("source", 4, 9));
        assertFalse(pending.isChunkReady("destination", 5, 9));
    }

    private static PendingRelocation reserved() {
        PendingRelocation pending = pending();
        pending.beginAdmissionPreparation();
        CompanionRelocationAdmissionService.Admission admission = admission();
        pending.installReservedAdmission(decision(
                CompanionRelocationAdmissionService.Status.RESERVED, admission
        ));
        return pending;
    }

    private static PendingRelocation pending() {
        return pendingAt(new Vector3d(10.0, 20.0, 30.0), "Follow");
    }

    private static PendingRelocation pendingAt(Vector3d destination, String state) {
        return pendingAt(destination, state, "default");
    }

    private static PendingRelocation pendingAt(Vector3d destination, String state, String worldName) {
        return new PendingRelocation(
                UUID.fromString("00000000-0000-0000-0000-000000000501"),
                destination,
                worldName,
                null,
                null,
                null,
                UUID.fromString("00000000-0000-0000-0000-000000000502"),
                true,
                true,
                state,
                "Default",
                0L,
                0L,
                true,
                TwCompanionConfig.TransferFailurePolicy.QueueForRecall,
                null,
                CompanionRelocationAdmissionService.ForcePolicy.ENFORCE
        );
    }

    private static CompanionRelocationAdmissionService.Admission admission() {
        return new CompanionRelocationAdmissionService.Admission(new PopulationAdmissionToken(
                UUID.fromString("00000000-0000-0000-0000-000000000503"),
                UUID.fromString("00000000-0000-0000-0000-000000000504"),
                Long.MAX_VALUE,
                1L,
                "test:1",
                OwnerPopulationCapDecisionViewV2.Readiness.READY
        ));
    }

    private static CompanionRelocationAdmissionService.Decision decision(
            CompanionRelocationAdmissionService.Status status,
            CompanionRelocationAdmissionService.Admission admission
    ) {
        return new CompanionRelocationAdmissionService.Decision(status, status.name(), admission);
    }
}
