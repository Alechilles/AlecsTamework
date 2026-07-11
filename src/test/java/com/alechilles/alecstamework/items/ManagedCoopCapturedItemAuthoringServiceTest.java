package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.ManagedCoopCapturedItemAuthoringService.AuthoringResult;
import com.alechilles.alecstamework.items.ManagedCoopCapturedItemAuthoringService.AuthoringStatus;
import com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService.CancellationReason;
import com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService.CancellationResult;
import com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService.CancellationStatus;
import com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService.MatchKind;
import com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService.SnapshotHandoff;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Capture-crate authoring coverage for breeding cancellation and strict portable snapshots. */
class ManagedCoopCapturedItemAuthoringServiceTest {
    private static final UUID SOURCE = new UUID(0L, 121L);

    @Test
    void safeBreedingCancellationProducesStrictManagedEnvelope() {
        ManagedCoopCapturedItemAuthoringService service = service();

        AuthoringResult result = service.authorFromHandoff(
                "profile-a", SOURCE, "Mob_Chicken",
                handoff(CancellationStatus.NOT_FOUND, portableSnapshot()));

        assertTrue(result.prepared());
        assertEquals("profile-a", result.profileId());
        assertTrue(new ManagedCoopCapturedItemEnvelopeCodec()
                .decode("Tool_Capture_Crate", result.envelopeJson()).found());
    }

    @Test
    void closedOrRejectedBreedingScopeFailsClosedBeforeEnvelopeAuthoring() {
        ManagedCoopCapturedItemAuthoringService service = service();

        for (CancellationStatus status : new CancellationStatus[]{
                CancellationStatus.SCOPE_CLOSED, CancellationStatus.REJECTED}) {
            AuthoringResult result = service.authorFromHandoff(
                    "profile-a", SOURCE, "Mob_Chicken",
                    handoff(status, portableSnapshot()));

            assertEquals(AuthoringStatus.FAILED, result.status());
            assertEquals("breeding_capture_cancellation_rejected", result.detail());
            assertNull(result.envelopeJson());
        }
    }

    @Test
    void mismatchedOrIncompletePortableSnapshotFailsClosed() {
        ManagedCoopCapturedItemAuthoringService service = service();
        CoopResidentStateSnapshot housed = new CoopResidentStateSnapshot(
                SOURCE, "coop-a", 0, "Mob_Chicken",
                null, null, null, null, null, null, null, null, null, null, null, null,
                1.0, -100L);

        AuthoringResult result = service.authorFromHandoff(
                "profile-a", SOURCE, "Mob_Chicken",
                handoff(CancellationStatus.ALREADY_TERMINAL, housed));

        assertEquals(AuthoringStatus.FAILED, result.status());
        assertEquals("portable_snapshot_incomplete_or_mismatched", result.detail());
    }

    private ManagedCoopCapturedItemAuthoringService service() {
        return new ManagedCoopCapturedItemAuthoringService(
                sourceUuid -> "profile-a",
                (targetRef, store, sourceNpcUuid, profileId, roleId) -> {
                    throw new AssertionError("not used by pure authoring test");
                },
                new ManagedCoopCapturedItemEnvelopeCodec());
    }

    private SnapshotHandoff<CoopResidentStateSnapshot> handoff(
            CancellationStatus status,
            CoopResidentStateSnapshot snapshot) {
        return new SnapshotHandoff<>(
                new CancellationResult(
                        status,
                        CancellationReason.CAPTURE_CRATE,
                        MatchKind.NONE,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()),
                snapshot);
    }

    private CoopResidentStateSnapshot portableSnapshot() {
        return new CoopResidentStateSnapshot(
                SOURCE, null, -1, "Mob_Chicken",
                null, null, null, null, null, null, null, null, null, null, null, null,
                1.0, -100L);
    }
}
