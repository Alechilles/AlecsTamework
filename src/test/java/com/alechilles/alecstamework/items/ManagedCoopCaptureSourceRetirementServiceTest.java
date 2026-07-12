package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.RetirementReady;
import com.alechilles.alecstamework.items.ManagedCoopCaptureSourceRetirementService.EvidenceDecision;
import com.alechilles.alecstamework.items.ManagedCoopCaptureSourceRetirementService.LiveSourceDecision;
import com.alechilles.alecstamework.items.ManagedCoopCaptureSourceRetirementService.Outcome;
import com.alechilles.alecstamework.items.ManagedCoopCaptureSourceRetirementService.RefreshDecision;
import com.alechilles.alecstamework.items.ManagedCoopCaptureSourceRetirementService.RemovalObservation;
import com.alechilles.alecstamework.items.ManagedCoopCaptureSourceRetirementService.RetirementCommand;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationResult;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationStatus;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationKind;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static com.alechilles.alecstamework.items.ManagedCoopCaptureSourceRetirementService.OutcomeStatus.ALREADY_COMPLETE;
import static com.alechilles.alecstamework.items.ManagedCoopCaptureSourceRetirementService.OutcomeStatus.BLOCKED;
import static com.alechilles.alecstamework.items.ManagedCoopCaptureSourceRetirementService.OutcomeStatus.COMPLETED;
import static com.alechilles.alecstamework.items.ManagedCoopCaptureSourceRetirementService.OutcomeStatus.FAILED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for restart-safe managed-coop capture source retirement. */
class ManagedCoopCaptureSourceRetirementServiceTest {
    private static final UUID SOURCE = new UUID(0L, 41L);
    private static final ManagedCoopAuthorityKey AUTHORITY =
            new ManagedCoopAuthorityKey("world-a", 1, 2, 3);
    private static final String HASH = "a".repeat(64);

    @Test
    void provenAbsentSourceCompletesDurablyThenPublishesPairedRefresh() throws Exception {
        Fixture fixture = new Fixture();
        fixture.world.live = LiveSourceDecision.provenAbsent();

        Outcome outcome = fixture.service.retire(ready()).get(3, TimeUnit.SECONDS);

        assertEquals(COMPLETED, outcome.status());
        assertEquals(command(), outcome.command());
        assertEquals("world-a", fixture.world.queuedWorld);
        assertEquals(1, fixture.world.enqueueCalls.get());
        assertEquals(1, fixture.world.retireCalls.get());
        assertEquals(1, fixture.completionCalls.get());
        assertEquals(-100L, fixture.completionNow.get());
        assertEquals(1, fixture.refreshCalls.get());
    }

    @Test
    void liveSourceWaitsForExactRemovalAndDeduplicatesReplay() throws Exception {
        Fixture fixture = new Fixture();
        CompletableFuture<Outcome> first = fixture.service.retire(ready());
        CompletableFuture<Outcome> replay = fixture.service.retire(ready());

        assertSame(first, replay);
        assertFalse(first.isDone());
        assertEquals(1, fixture.world.enqueueCalls.get());
        assertEquals(0, fixture.completionCalls.get());

        CompletableFuture<Outcome> removal = fixture.service.confirmRemoved(removal());
        Outcome outcome = removal.get(3, TimeUnit.SECONDS);

        assertSame(first, removal);
        assertEquals(COMPLETED, outcome.status());
        assertEquals(1, fixture.completionCalls.get());
        assertEquals(1, fixture.refreshCalls.get());
    }

    @Test
    void markerConflictBlocksWithoutCompletingCapture() throws Exception {
        Fixture fixture = new Fixture();
        fixture.world.live = LiveSourceDecision.conflict("source_projection_marker_conflict");

        Outcome outcome = fixture.service.retire(ready()).get(3, TimeUnit.SECONDS);

        assertEquals(BLOCKED, outcome.status());
        assertEquals("source_projection_marker_conflict", outcome.detail());
        assertEquals(0, fixture.completionCalls.get());
        assertEquals(0, fixture.refreshCalls.get());
    }

    @Test
    void removalMarkerMismatchNeverStartsPersistence() throws Exception {
        Fixture fixture = new Fixture();
        fixture.evidence.acceptRemoval = false;
        RemovalObservation mismatched = new RemovalObservation(
                SOURCE, "profile-a", "wrong-operation", "MANAGED_COOP_CAPTURE_SOURCE",
                AUTHORITY.slotKey(2), SOURCE, 2L);

        Outcome outcome = fixture.service.confirmRemoved(mismatched)
                .get(3, TimeUnit.SECONDS);

        assertEquals(BLOCKED, outcome.status());
        assertTrue(outcome.detail().contains("removal_mismatch"));
        assertEquals(0, fixture.completionCalls.get());
    }

    @Test
    void removalEvidenceFailureIsContainedAtTheRefSystemBoundary() throws Exception {
        Fixture fixture = new Fixture();
        fixture.evidence.removalFailure = new IllegalStateException("index_failed");

        Outcome outcome = fixture.service.confirmRemoved(removal())
                .get(3, TimeUnit.SECONDS);

        assertEquals(BLOCKED, outcome.status());
        assertTrue(outcome.detail().contains("removal_evidence_failed"));
        assertEquals(0, fixture.completionCalls.get());
    }

    @Test
    void worldThreadRevalidationFailureCompletesInsteadOfStrandingReplay() throws Exception {
        Fixture fixture = new Fixture();
        fixture.evidence.revalidationFailure =
                new IllegalStateException("trust_epoch_changed");

        Outcome outcome = fixture.service.retire(ready()).get(3, TimeUnit.SECONDS);

        assertEquals(BLOCKED, outcome.status());
        assertTrue(outcome.detail().contains("retirement_revalidation_failed"));
        assertEquals(0, fixture.world.retireCalls.get());
        assertEquals(0, fixture.completionCalls.get());
    }

    @Test
    void completionRevalidationFailureNeverSubmitsTheTerminalWrite() throws Exception {
        Fixture fixture = new Fixture();
        fixture.world.live = LiveSourceDecision.provenAbsent();
        fixture.evidence.revalidationFailure =
                new IllegalStateException("trust_epoch_changed");
        fixture.evidence.failRevalidationAt = 2;

        Outcome outcome = fixture.service.retire(ready()).get(3, TimeUnit.SECONDS);

        assertEquals(BLOCKED, outcome.status());
        assertTrue(outcome.detail().contains("completion_revalidation_failed"));
        assertEquals(0, fixture.completionCalls.get());
    }

    @Test
    void completionFailureLeavesRefreshUntouchedAndAllowsRetry() throws Exception {
        Fixture fixture = new Fixture();
        fixture.world.live = LiveSourceDecision.provenAbsent();
        fixture.completionFailure = new IllegalStateException("write_failed");

        Outcome failed = fixture.service.retire(ready()).get(3, TimeUnit.SECONDS);
        fixture.completionFailure = null;
        Outcome retried = fixture.service.retire(ready()).get(3, TimeUnit.SECONDS);

        assertEquals(FAILED, failed.status());
        assertTrue(failed.detail().contains("write_failed"));
        assertEquals(COMPLETED, retried.status());
        assertEquals(2, fixture.completionCalls.get());
        assertEquals(1, fixture.refreshCalls.get());
    }

    @Test
    void refreshFailureReportsFailureAfterExactCompletionCommit() throws Exception {
        Fixture fixture = new Fixture();
        fixture.world.live = LiveSourceDecision.provenAbsent();
        fixture.refreshAccepted = false;

        Outcome outcome = fixture.service.retire(ready()).get(3, TimeUnit.SECONDS);

        assertEquals(FAILED, outcome.status());
        assertEquals("paired_refresh_rejected", outcome.detail());
        assertEquals(1, fixture.completionCalls.get());
        assertEquals(1, fixture.refreshCalls.get());
    }

    @Test
    void invalidCompletionIdentityFailsClosed() throws Exception {
        Fixture fixture = new Fixture();
        fixture.world.live = LiveSourceDecision.provenAbsent();
        fixture.completionMutation = new MutationResult(
                MutationStatus.IDEMPOTENT,
                completedOperation("different-operation"),
                null
        );

        Outcome outcome = fixture.service.retire(ready()).get(3, TimeUnit.SECONDS);

        assertEquals(FAILED, outcome.status());
        assertEquals("capture_completion_identity_mismatch", outcome.detail());
        assertEquals(0, fixture.refreshCalls.get());
    }

    @Test
    void unloadedSourceNeverCompletesCaptureWithoutDurableAbsenceProof() throws Exception {
        Fixture fixture = new Fixture();
        fixture.world.live = LiveSourceDecision.unavailable(
                "source_not_loaded_absence_unproven");

        Outcome outcome = fixture.service.retire(ready()).get(3, TimeUnit.SECONDS);

        assertEquals(FAILED, outcome.status());
        assertEquals("source_not_loaded_absence_unproven", outcome.detail());
        assertEquals(0, fixture.completionCalls.get());
        assertEquals(0, fixture.refreshCalls.get());
    }

    @Test
    void completedReplayDoesNoWorldOrPersistenceWork() throws Exception {
        Fixture fixture = new Fixture();
        fixture.evidence.alreadyComplete = true;

        Outcome outcome = fixture.service.retire(ready()).get(3, TimeUnit.SECONDS);

        assertEquals(ALREADY_COMPLETE, outcome.status());
        assertEquals(0, fixture.world.enqueueCalls.get());
        assertEquals(0, fixture.completionCalls.get());
    }

    private static RetirementReady ready() {
        return new RetirementReady(
                SOURCE, "profile-a", "resident-a", "capture-a", AUTHORITY,
                "coop-a", 2, HASH, 2L, OperationState.SOURCE_RETIRE_REQUESTED, 7L
        );
    }

    private static RetirementCommand command() {
        return new RetirementCommand(
                SOURCE, "profile-a", "resident-a", "capture-a", AUTHORITY,
                "coop-a", 2, HASH, 0L, 2L
        );
    }

    private static RemovalObservation removal() {
        return new RemovalObservation(
                SOURCE, "profile-a", "capture-a", "MANAGED_COOP_CAPTURE_SOURCE",
                AUTHORITY.slotKey(2), SOURCE, 2L
        );
    }

    private static OperationRecord completedOperation(String operationId) {
        return new OperationRecord(
                operationId, OperationKind.CAPTURE, "profile-a", AUTHORITY,
                "coop-a", 2, SOURCE, null, null, OperationState.COMPLETE,
                HASH, 0L, 3L, 0, false, -500L, -100L, -100L, null
        );
    }

    private static final class Fixture {
        private final FakeEvidence evidence = new FakeEvidence();
        private final FakeWorld world = new FakeWorld();
        private final AtomicInteger completionCalls = new AtomicInteger();
        private final AtomicLong completionNow = new AtomicLong();
        private final AtomicInteger refreshCalls = new AtomicInteger();
        private volatile Throwable completionFailure;
        private volatile MutationResult completionMutation = new MutationResult(
                MutationStatus.APPLIED, completedOperation("capture-a"), null);
        private volatile boolean refreshAccepted = true;
        private final ManagedCoopCaptureSourceRetirementService service =
                new ManagedCoopCaptureSourceRetirementService(
                        evidence,
                        world,
                        (command, nowMs) -> {
                            completionCalls.incrementAndGet();
                            completionNow.set(nowMs);
                            return completionFailure != null
                                    ? CompletableFuture.failedFuture(completionFailure)
                                    : CompletableFuture.completedFuture(completionMutation);
                        },
                        () -> {
                            refreshCalls.incrementAndGet();
                            return new RefreshDecision(
                                    refreshAccepted,
                                    refreshAccepted ? null : "paired_refresh_rejected");
                        },
                        () -> -100L
                );
    }

    private static final class FakeEvidence
            implements ManagedCoopCaptureSourceRetirementService.StateEvidenceGateway {
        private boolean acceptRemoval = true;
        private boolean alreadyComplete;
        private RuntimeException removalFailure;
        private RuntimeException revalidationFailure;
        private int failRevalidationAt;
        private final AtomicInteger revalidationCalls = new AtomicInteger();

        @Override
        public EvidenceDecision resolve(RetirementReady ready) {
            return alreadyComplete
                    ? EvidenceDecision.alreadyComplete()
                    : EvidenceDecision.active(command());
        }

        @Override
        public EvidenceDecision resolve(RemovalObservation observation) {
            if (removalFailure != null) {
                throw removalFailure;
            }
            return acceptRemoval
                    ? EvidenceDecision.active(command())
                    : EvidenceDecision.rejected("removal_mismatch");
        }

        @Override
        public EvidenceDecision revalidate(RetirementCommand candidate) {
            int call = revalidationCalls.incrementAndGet();
            if (revalidationFailure != null
                    && (failRevalidationAt == 0 || failRevalidationAt == call)) {
                throw revalidationFailure;
            }
            return EvidenceDecision.active(command());
        }
    }

    private static final class FakeWorld
            implements ManagedCoopCaptureSourceRetirementService.WorldGateway {
        private final AtomicInteger enqueueCalls = new AtomicInteger();
        private final AtomicInteger retireCalls = new AtomicInteger();
        private volatile String queuedWorld;
        private volatile LiveSourceDecision live = LiveSourceDecision.despawnRequested();

        @Override
        public boolean enqueue(String worldName, Runnable task) {
            enqueueCalls.incrementAndGet();
            queuedWorld = worldName;
            task.run();
            return true;
        }

        @Override
        public LiveSourceDecision retire(RetirementCommand command) {
            retireCalls.incrementAndGet();
            return live;
        }
    }
}
