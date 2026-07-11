package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.LoadStatus;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.RecoveryFinalization;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.RecoveryOperation;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.RecoveryState;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.TransitionResult;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.TransitionStatus;
import static com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue.WriteStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for crash-boundary recovery projection reconciliation. */
class RecoveryProjectionReconciliationServiceTest {
    private static final UUID SOURCE = new UUID(0L, 1L);
    private static final UUID TARGET = new UUID(0L, 2L);

    @Test
    void spawnClaimedProjectionCommitsProjectionBeforeAttemptingFinalization() throws Exception {
        FakeOperations operations = new FakeOperations(operation(RecoveryState.SPAWN_CLAIMED, true, 0L, null));
        CompletableFuture<PersistenceWriteQueue.WriteOutcome<TransitionResult>> projectionCommit =
                new CompletableFuture<>();
        operations.projectionSubmission = new PersistenceWriteQueue.WriteSubmission<>(true, projectionCommit);
        operations.finalizationSubmission = committed(new TransitionResult(
                TransitionStatus.APPLIED,
                operation(RecoveryState.FINALIZED, false, 2L, TARGET)
        ));
        RecoveryProjectionReconciliationService service = service(operations);

        CompletableFuture<RecoveryProjectionReconciliationService.Result> result =
                service.reconcile(observation()).toCompletableFuture();

        assertEquals(1, operations.projectionCalls);
        assertEquals(0, operations.finalizationCalls);
        assertFalse(result.isDone());

        projectionCommit.complete(new PersistenceWriteQueue.WriteOutcome<>(
                WriteStatus.COMMITTED,
                new TransitionResult(
                        TransitionStatus.APPLIED,
                        operation(RecoveryState.PROJECTION_CREATED, true, 1L, TARGET)
                ),
                null,
                null
        ));

        RecoveryProjectionReconciliationService.Result reconciled = result.get(1, TimeUnit.SECONDS);
        assertEquals(RecoveryProjectionReconciliationService.Status.FINALIZED, reconciled.status());
        assertTrue(reconciled.isFinalized());
        assertEquals(1, operations.finalizationCalls);
        assertEquals(1L, operations.lastFinalization.expectedGeneration());
        assertEquals(TARGET, operations.lastFinalization.actualTargetUuid());
    }

    @Test
    void projectionCreatedResumesAtFinalizationWithoutRecordingAnotherProjection() throws Exception {
        FakeOperations operations = new FakeOperations(
                operation(RecoveryState.PROJECTION_CREATED, true, 1L, TARGET));
        operations.finalizationSubmission = committed(new TransitionResult(
                TransitionStatus.APPLIED,
                operation(RecoveryState.FINALIZED, false, 2L, TARGET)
        ));

        RecoveryProjectionReconciliationService.Result result =
                service(operations).reconcile(observation()).toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertEquals(RecoveryProjectionReconciliationService.Status.FINALIZED, result.status());
        assertEquals(0, operations.projectionCalls);
        assertEquals(1, operations.finalizationCalls);
    }

    @Test
    void finalizedProjectionUsesAtomicReplayVerificationAndCanonicalSnapshotToolIds() throws Exception {
        FakeOperations operations = new FakeOperations(operation(RecoveryState.FINALIZED, false, 2L, TARGET));
        operations.finalizationSubmission = committed(new TransitionResult(
                TransitionStatus.REPLAYED,
                operation(RecoveryState.FINALIZED, false, 2L, TARGET)
        ));
        RecoveryProjectionReconciliationService.Observation observation = observation(
                true, List.of(" tool-b ", "tool-a", "tool-b"));

        RecoveryProjectionReconciliationService.Result result =
                service(operations).reconcile(observation).toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertEquals(RecoveryProjectionReconciliationService.Status.FINALIZED_REPLAYED, result.status());
        assertEquals(0, operations.projectionCalls);
        assertEquals(1, operations.finalizationCalls);
        assertEquals(List.of("tool-a", "tool-b"), operations.lastFinalization.toolIds());
        assertEquals(1L, operations.lastFinalization.expectedGeneration());
    }

    @Test
    void mismatchedProjectionIdentityNeverWrites() throws Exception {
        FakeOperations operations = new FakeOperations(operation(RecoveryState.SPAWN_CLAIMED, true, 0L, null));
        RecoveryProjectionReconciliationService.Observation observation =
                new RecoveryProjectionReconciliationService.Observation(
                        TameworkProjectionIdentityComponent.KIND_RECOVERY,
                        "profile-a",
                        "operation-a",
                        SOURCE,
                        0L,
                        TARGET,
                        new UUID(0L, 99L),
                        true,
                        List.of()
                );

        RecoveryProjectionReconciliationService.Result result =
                service(operations).reconcile(observation).toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertEquals(RecoveryProjectionReconciliationService.Status.IDENTITY_CONFLICT, result.status());
        assertEquals("projection_uuid_components_disagree", result.detail());
        assertEquals(0, operations.loadCalls);
        assertNoWrites(operations);
    }

    @Test
    void markerAndOperationGenerationMismatchFailsClosed() throws Exception {
        FakeOperations operations = new FakeOperations(operation(RecoveryState.SPAWN_CLAIMED, true, 1L, null));

        RecoveryProjectionReconciliationService.Result result =
                service(operations).reconcile(observation()).toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertEquals(RecoveryProjectionReconciliationService.Status.IDENTITY_CONFLICT, result.status());
        assertEquals("operation_generation_mismatch", result.detail());
        assertNoWrites(operations);
    }

    @Test
    void unavailableCommandLinkTypeNeverReadsOrFinalizesOperation() throws Exception {
        FakeOperations operations = new FakeOperations(operation(RecoveryState.SPAWN_CLAIMED, true, 0L, null));

        RecoveryProjectionReconciliationService.Result result = service(operations)
                .reconcile(observation(false, List.of()))
                .toCompletableFuture()
                .get(1, TimeUnit.SECONDS);

        assertEquals(
                RecoveryProjectionReconciliationService.Status.TOOL_LINK_SNAPSHOT_UNAVAILABLE,
                result.status()
        );
        assertEquals(0, operations.loadCalls);
        assertNoWrites(operations);
    }

    @Test
    void registeredButAbsentCommandLinksAreAValidKnownEmptySnapshot() throws Exception {
        FakeOperations operations = new FakeOperations(
                operation(RecoveryState.PROJECTION_CREATED, true, 1L, TARGET));
        operations.finalizationSubmission = committed(new TransitionResult(
                TransitionStatus.APPLIED,
                operation(RecoveryState.FINALIZED, false, 2L, TARGET)
        ));

        RecoveryProjectionReconciliationService.Result result = service(operations)
                .reconcile(observation(true, List.of()))
                .toCompletableFuture()
                .get(1, TimeUnit.SECONDS);

        assertEquals(RecoveryProjectionReconciliationService.Status.FINALIZED, result.status());
        assertEquals(List.of(), operations.lastFinalization.toolIds());
    }

    @Test
    void projectionWriteFailureNeverAttemptsFinalization() throws Exception {
        FakeOperations operations = new FakeOperations(operation(RecoveryState.SPAWN_CLAIMED, true, 0L, null));
        operations.projectionSubmission = new PersistenceWriteQueue.WriteSubmission<>(
                true,
                CompletableFuture.completedFuture(new PersistenceWriteQueue.WriteOutcome<>(
                        WriteStatus.FAILED, null, "sqlite_failed", new IllegalStateException("failed")))
        );

        RecoveryProjectionReconciliationService.Result result =
                service(operations).reconcile(observation()).toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertEquals(RecoveryProjectionReconciliationService.Status.WRITE_FAILED, result.status());
        assertEquals(1, operations.projectionCalls);
        assertEquals(0, operations.finalizationCalls);
    }

    @Test
    void finalizedReplayConflictIsReportedInsteadOfAssumedSuccessful() throws Exception {
        FakeOperations operations = new FakeOperations(operation(RecoveryState.FINALIZED, false, 2L, TARGET));
        operations.finalizationSubmission = committed(new TransitionResult(
                TransitionStatus.STATE_CONFLICT,
                operation(RecoveryState.FINALIZED, false, 2L, TARGET)
        ));

        RecoveryProjectionReconciliationService.Result result =
                service(operations).reconcile(observation()).toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertEquals(RecoveryProjectionReconciliationService.Status.TRANSITION_CONFLICT, result.status());
        assertEquals("finalize_state_conflict", result.detail());
        assertFalse(result.isFinalized());
    }

    @Test
    void missingOperationDoesNotCreateOrClaimOne() throws Exception {
        FakeOperations operations = new FakeOperations(null);

        RecoveryProjectionReconciliationService.Result result =
                service(operations).reconcile(observation()).toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertEquals(RecoveryProjectionReconciliationService.Status.OPERATION_NOT_FOUND, result.status());
        assertNoWrites(operations);
    }

    private RecoveryProjectionReconciliationService service(FakeOperations operations) {
        return new RecoveryProjectionReconciliationService(operations, Runnable::run);
    }

    private RecoveryProjectionReconciliationService.Observation observation() {
        return observation(true, List.of("tool-a"));
    }

    private RecoveryProjectionReconciliationService.Observation observation(
            boolean toolIdsAvailable,
            List<String> toolIds) {
        return new RecoveryProjectionReconciliationService.Observation(
                TameworkProjectionIdentityComponent.KIND_RECOVERY,
                "profile-a",
                "operation-a",
                SOURCE,
                0L,
                TARGET,
                TARGET,
                toolIdsAvailable,
                toolIds
        );
    }

    private RecoveryOperation operation(RecoveryState state,
                                        boolean active,
                                        long generation,
                                        UUID actualTarget) {
        return new RecoveryOperation(
                "operation-a",
                "profile-a",
                SOURCE,
                TARGET,
                actualTarget,
                state,
                active,
                generation,
                1,
                1L,
                1L,
                state == RecoveryState.FINALIZED ? 1L : 0L,
                null
        );
    }

    private PersistenceWriteQueue.WriteSubmission<TransitionResult> committed(TransitionResult result) {
        return new PersistenceWriteQueue.WriteSubmission<>(
                true,
                CompletableFuture.completedFuture(new PersistenceWriteQueue.WriteOutcome<>(
                        WriteStatus.COMMITTED, result, null, null))
        );
    }

    private void assertNoWrites(FakeOperations operations) {
        assertEquals(0, operations.projectionCalls);
        assertEquals(0, operations.finalizationCalls);
        assertNull(operations.lastFinalization);
    }

    private static final class FakeOperations
            implements RecoveryProjectionReconciliationService.RecoveryOperations {
        private RecoveryOperation loaded;
        private int loadCalls;
        private int projectionCalls;
        private int finalizationCalls;
        private RecoveryFinalization lastFinalization;
        private PersistenceWriteQueue.WriteSubmission<TransitionResult> projectionSubmission;
        private PersistenceWriteQueue.WriteSubmission<TransitionResult> finalizationSubmission;

        private FakeOperations(RecoveryOperation loaded) {
            this.loaded = loaded;
        }

        @Override
        public NpcRecoveryOperationRepository.LoadResult loadByOperationId(String operationId) {
            loadCalls++;
            return loaded == null
                    ? new NpcRecoveryOperationRepository.LoadResult(LoadStatus.NOT_FOUND, null, null)
                    : new NpcRecoveryOperationRepository.LoadResult(LoadStatus.FOUND, loaded, null);
        }

        @Override
        public PersistenceWriteQueue.WriteSubmission<TransitionResult> recordProjectionCreated(
                String operationId,
                String profileId,
                UUID actualTargetUuid,
                long expectedGeneration) {
            projectionCalls++;
            return projectionSubmission;
        }

        @Override
        public PersistenceWriteQueue.WriteSubmission<TransitionResult> finalizeRecovery(
                RecoveryFinalization finalization) {
            finalizationCalls++;
            lastFinalization = finalization;
            return finalizationSubmission;
        }
    }
}
