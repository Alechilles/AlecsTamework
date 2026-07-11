package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopReleaseCoordinator.SpawnReady;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationResult;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationStatus;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationKind;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureClaimValidator;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static com.alechilles.alecstamework.items.ManagedCoopReleaseProjectionCoordinator.OutcomeStatus.DEDUPLICATED;
import static com.alechilles.alecstamework.items.ManagedCoopReleaseProjectionCoordinator.OutcomeStatus.FAILED;
import static com.alechilles.alecstamework.items.ManagedCoopReleaseProjectionCoordinator.OutcomeStatus.FINALIZED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for managed-coop release projection persistence and finalization. */
class ManagedCoopReleaseProjectionCoordinatorTest {
    private static final UUID SOURCE = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PLANNED = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID ACTUAL = PLANNED;
    private static final UUID OTHER_ACTUAL = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final ManagedCoopAuthorityKey AUTHORITY =
            new ManagedCoopAuthorityKey("world", 10, 20, 30);

    @Test
    void ordersProjectionRefreshFinalizationAndDeployedRefresh() throws Exception {
        List<String> events = new ArrayList<>();
        CompletableFuture<PersistenceWriteQueue.WriteOutcome<MutationResult>> projectionCommit =
                new CompletableFuture<>();
        CompletableFuture<PersistenceWriteQueue.WriteOutcome<MutationResult>> finalizationCommit =
                new CompletableFuture<>();
        FakeOperations operations = new FakeOperations();
        operations.markBehavior = (operationId, generation, actualUuid, nowMs) -> {
            events.add("projection_submitted");
            assertEquals(1L, generation);
            assertEquals(ACTUAL, actualUuid);
            assertEquals(150L, nowMs);
            return submission(projectionCommit);
        };
        operations.finalizeBehavior = (operationId, generation, nowMs) -> {
            events.add("finalization_submitted");
            assertEquals(2L, generation);
            assertEquals(900L, nowMs);
            return submission(finalizationCommit);
        };
        AtomicInteger refreshCount = new AtomicInteger();
        ManagedCoopReleaseProjectionCoordinator coordinator =
                new ManagedCoopReleaseProjectionCoordinator(
                        operations,
                        () -> {
                            int refresh = refreshCount.incrementAndGet();
                            events.add(refresh == 1 ? "projection_refreshed" : "finalized_refreshed");
                            return refreshed(refresh == 1 ? 10L : 11L);
                        },
                        () -> 900L
                );
        SpawnReady claim = spawnClaim();

        CompletableFuture<ManagedCoopReleaseProjectionCoordinator.ProjectionOutcome> completion =
                coordinator.coordinate(new ManagedCoopReleaseProjectionCoordinator.ProjectionAttempt(
                        claim, ACTUAL, 150L));
        assertEquals(List.of("projection_submitted"), events);
        assertFalse(completion.isDone());

        projectionCommit.complete(committed(applied(
                operation(claim, OperationState.PROJECTION_CREATED, ACTUAL))));
        assertEquals(
                List.of("projection_submitted", "projection_refreshed", "finalization_submitted"),
                events
        );
        assertFalse(completion.isDone());

        finalizationCommit.complete(committed(applied(
                operation(claim, OperationState.FINALIZED, ACTUAL))));
        ManagedCoopReleaseProjectionCoordinator.ProjectionOutcome outcome =
                completion.get(3, TimeUnit.SECONDS);
        assertEquals(
                List.of("projection_submitted", "projection_refreshed", "finalization_submitted",
                        "finalized_refreshed"),
                events
        );
        assertEquals(FINALIZED, outcome.status());
        assertTrue(outcome.finalized());
        assertNotNull(outcome.finalizedProjection());
        assertEquals(ACTUAL, outcome.finalizedProjection().actualTargetUuid());
        assertEquals(2L, outcome.finalizedProjection().deployedResidentGeneration());
        assertEquals(3L, outcome.finalizedProjection().operationGeneration());
        assertEquals(10L, outcome.finalizedProjection().projectionIndexRevision());
        assertEquals(11L, outcome.finalizedProjection().finalizedIndexRevision());
    }

    @Test
    void deduplicatesSameTargetAndRejectsDifferentTargetForInFlightOperation() throws Exception {
        CompletableFuture<PersistenceWriteQueue.WriteOutcome<MutationResult>> projectionCommit =
                new CompletableFuture<>();
        FakeOperations operations = new FakeOperations();
        operations.markBehavior = (operationId, generation, actualUuid, nowMs) -> submission(projectionCommit);
        operations.finalizeBehavior = (operationId, generation, nowMs) -> completedSubmission(applied(
                operation(spawnClaim(), OperationState.FINALIZED, ACTUAL)));
        ManagedCoopReleaseProjectionCoordinator coordinator =
                new ManagedCoopReleaseProjectionCoordinator(
                        operations,
                        () -> refreshed(1L),
                        () -> 200L
                );
        SpawnReady claim = spawnClaim();
        ManagedCoopReleaseProjectionCoordinator.ProjectionAttempt attempt =
                new ManagedCoopReleaseProjectionCoordinator.ProjectionAttempt(claim, ACTUAL, 150L);

        CompletableFuture<ManagedCoopReleaseProjectionCoordinator.ProjectionOutcome> first =
                coordinator.coordinate(attempt);
        ManagedCoopReleaseProjectionCoordinator.ProjectionOutcome duplicate =
                coordinator.coordinate(attempt).get(3, TimeUnit.SECONDS);
        ManagedCoopReleaseProjectionCoordinator.ProjectionOutcome conflict = coordinator.coordinate(
                new ManagedCoopReleaseProjectionCoordinator.ProjectionAttempt(claim, OTHER_ACTUAL, 150L)
        ).get(3, TimeUnit.SECONDS);

        assertEquals(DEDUPLICATED, duplicate.status());
        assertEquals("release_projection_operation_already_in_flight", duplicate.detail());
        assertEquals(FAILED, conflict.status());
        assertEquals("release_projection_target_in_flight_conflict", conflict.detail());
        assertEquals(1, operations.markCalls.get());

        projectionCommit.complete(committed(applied(
                operation(claim, OperationState.PROJECTION_CREATED, ACTUAL))));
        assertEquals(FINALIZED, first.get(3, TimeUnit.SECONDS).status());
    }

    @Test
    void replaysProjectedAndFinalizedWithSameUuidWithoutRecordingAnotherTarget() throws Exception {
        for (OperationState replayState : List.of(
                OperationState.PROJECTION_CREATED,
                OperationState.FINALIZED)) {
            FakeOperations operations = new FakeOperations();
            operations.markBehavior = (operationId, generation, actualUuid, nowMs) -> {
                if (!ACTUAL.equals(actualUuid)) {
                    return completedSubmission(new MutationResult(
                            MutationStatus.CONFLICT, null, "projection_uuid_conflict"));
                }
                return completedSubmission(idempotent(operation(spawnClaim(), replayState, ACTUAL)));
            };
            operations.finalizeBehavior = (operationId, generation, nowMs) -> {
                if (replayState == OperationState.FINALIZED) {
                    throw new AssertionError("FINALIZED replay must not finalize again");
                }
                return completedSubmission(applied(
                        operation(spawnClaim(), OperationState.FINALIZED, ACTUAL)));
            };
            AtomicInteger refreshes = new AtomicInteger();
            ManagedCoopReleaseProjectionCoordinator coordinator =
                    new ManagedCoopReleaseProjectionCoordinator(
                            operations,
                            () -> refreshed(refreshes.incrementAndGet()),
                            () -> 200L
                    );
            SpawnReady claim = spawnClaim();

            ManagedCoopReleaseProjectionCoordinator.ProjectionOutcome replay = coordinator.coordinate(
                    new ManagedCoopReleaseProjectionCoordinator.ProjectionAttempt(claim, ACTUAL, 150L)
            ).get(3, TimeUnit.SECONDS);
            assertEquals(FINALIZED, replay.status());
            assertEquals(replayState == OperationState.FINALIZED ? 1 : 2, refreshes.get());
            assertEquals(replayState == OperationState.FINALIZED ? 0 : 1, operations.finalizeCalls.get());

            ManagedCoopReleaseProjectionCoordinator.ProjectionOutcome secondTarget = coordinator.coordinate(
                    new ManagedCoopReleaseProjectionCoordinator.ProjectionAttempt(
                            claim, OTHER_ACTUAL, 151L)
            ).get(3, TimeUnit.SECONDS);
            assertEquals(FAILED, secondTarget.status());
            assertTrue(secondTarget.detail().contains("projection_created_conflict:projection_uuid_conflict"));
            assertEquals(replayState == OperationState.FINALIZED ? 0 : 1,
                    operations.finalizeCalls.get());
        }
    }

    @Test
    void rejectsUnclaimedOrNonDeterministicSpawnDataBeforePersistence() throws Exception {
        FakeOperations operations = new FakeOperations();
        operations.markBehavior = (operationId, generation, actualUuid, nowMs) -> {
            throw new AssertionError("invalid claim must not persist projection");
        };
        operations.finalizeBehavior = (operationId, generation, nowMs) -> {
            throw new AssertionError("invalid claim must not finalize");
        };
        ManagedCoopReleaseProjectionCoordinator coordinator =
                new ManagedCoopReleaseProjectionCoordinator(
                        operations,
                        () -> {
                            throw new AssertionError("invalid claim must not refresh");
                        },
                        () -> 200L
                );
        SpawnReady valid = spawnClaim();
        SpawnReady alreadyProjected = copyClaim(
                valid,
                valid.operationId(),
                OperationState.PROJECTION_CREATED,
                false,
                ACTUAL
        );
        SpawnReady wrongOperation = copyClaim(
                valid,
                "managed-coop-release:" + "0".repeat(64),
                OperationState.SPAWN_CLAIMED,
                true,
                null
        );

        ManagedCoopReleaseProjectionCoordinator.ProjectionOutcome projected = coordinator.coordinate(
                new ManagedCoopReleaseProjectionCoordinator.ProjectionAttempt(
                        alreadyProjected, ACTUAL, 150L)
        ).get(3, TimeUnit.SECONDS);
        ManagedCoopReleaseProjectionCoordinator.ProjectionOutcome nonDeterministic = coordinator.coordinate(
                new ManagedCoopReleaseProjectionCoordinator.ProjectionAttempt(
                        wrongOperation, ACTUAL, 150L)
        ).get(3, TimeUnit.SECONDS);

        assertEquals(FAILED, projected.status());
        assertTrue(projected.detail().contains("unconsumed SPAWN_CLAIMED"));
        assertEquals(FAILED, nonDeterministic.status());
        assertTrue(nonDeterministic.detail().contains("operationId is not deterministic"));
        assertEquals(0, operations.markCalls.get());
    }

    @Test
    void projectionIdentityAndGenerationMismatchFailBeforeRefreshOrFinalize() throws Exception {
        AtomicInteger refreshes = new AtomicInteger();
        FakeOperations operations = new FakeOperations();
        operations.markBehavior = (operationId, generation, actualUuid, nowMs) -> {
            SpawnReady claim = spawnClaim();
            OperationRecord mismatched = operation(claim, OperationState.PROJECTION_CREATED, ACTUAL);
            mismatched = new OperationRecord(
                    mismatched.operationId(), mismatched.kind(), "other-profile", mismatched.authorityKey(),
                    mismatched.coopId(), mismatched.residentSlot(), mismatched.sourceNpcUuid(),
                    mismatched.plannedTargetUuid(), mismatched.actualTargetUuid(), mismatched.state(),
                    mismatched.snapshotHash(), mismatched.expectedResidentGeneration(), mismatched.generation(),
                    mismatched.retryCount(), mismatched.active(), mismatched.createdAtMs(),
                    mismatched.updatedAtMs(), mismatched.completedAtMs(), mismatched.lastError()
            );
            return completedSubmission(applied(mismatched));
        };
        operations.finalizeBehavior = (operationId, generation, nowMs) -> {
            throw new AssertionError("identity mismatch must not finalize");
        };
        ManagedCoopReleaseProjectionCoordinator coordinator =
                new ManagedCoopReleaseProjectionCoordinator(
                        operations,
                        () -> {
                            refreshes.incrementAndGet();
                            return refreshed(1L);
                        },
                        () -> 200L
                );

        ManagedCoopReleaseProjectionCoordinator.ProjectionOutcome outcome = coordinator.coordinate(
                new ManagedCoopReleaseProjectionCoordinator.ProjectionAttempt(
                        spawnClaim(), ACTUAL, 150L)
        ).get(3, TimeUnit.SECONDS);

        assertEquals(FAILED, outcome.status());
        assertTrue(outcome.detail().contains("projection_created_operation_identity_or_generation_mismatch"));
        assertEquals(0, refreshes.get());
        assertEquals(0, operations.finalizeCalls.get());
    }

    @Test
    void refreshAndWriteFailuresNeverReportFinalized() throws Exception {
        SpawnReady claim = spawnClaim();
        FakeOperations projectionWriteFailure = new FakeOperations();
        projectionWriteFailure.markBehavior = (operationId, generation, actualUuid, nowMs) ->
                failedSubmission("projection_write_failed");
        projectionWriteFailure.finalizeBehavior = (operationId, generation, nowMs) -> {
            throw new AssertionError("projection write failure must not finalize");
        };
        ManagedCoopReleaseProjectionCoordinator failedProjection = coordinator(
                projectionWriteFailure, () -> refreshed(1L));
        ManagedCoopReleaseProjectionCoordinator.ProjectionOutcome projectionFailure =
                failedProjection.coordinate(attempt(claim, ACTUAL)).get(3, TimeUnit.SECONDS);
        assertEquals(FAILED, projectionFailure.status());
        assertTrue(projectionFailure.detail().contains("projection_created_not_committed"));

        FakeOperations refreshFailure = projectedOperations(claim);
        ManagedCoopReleaseProjectionCoordinator failedRefresh = coordinator(
                refreshFailure,
                () -> new ManagedCoopResidentIndexRefreshService.RefreshResult(
                        ManagedCoopResidentIndexRefreshService.RefreshStatus.REJECTED,
                        0L,
                        "sql_failure"
                )
        );
        ManagedCoopReleaseProjectionCoordinator.ProjectionOutcome rejectedRefresh =
                failedRefresh.coordinate(attempt(claim, ACTUAL)).get(3, TimeUnit.SECONDS);
        assertEquals(FAILED, rejectedRefresh.status());
        assertTrue(rejectedRefresh.detail().contains("projection_index_refresh_rejected:sql_failure"));
        assertEquals(0, refreshFailure.finalizeCalls.get());

        FakeOperations finalizeFailure = projectedOperations(claim);
        finalizeFailure.finalizeBehavior = (operationId, generation, nowMs) ->
                failedSubmission("finalize_write_failed");
        ManagedCoopReleaseProjectionCoordinator failedFinalize = coordinator(
                finalizeFailure, () -> refreshed(1L));
        ManagedCoopReleaseProjectionCoordinator.ProjectionOutcome finalizationFailure =
                failedFinalize.coordinate(attempt(claim, ACTUAL)).get(3, TimeUnit.SECONDS);
        assertEquals(FAILED, finalizationFailure.status());
        assertTrue(finalizationFailure.detail().contains("release_finalize_not_committed"));
    }

    @Test
    void postFinalizeRefreshFailureRequiresSameUuidReplayRepair() throws Exception {
        SpawnReady claim = spawnClaim();
        FakeOperations operations = projectedOperations(claim);
        AtomicInteger refreshes = new AtomicInteger();
        ManagedCoopReleaseProjectionCoordinator coordinator = new ManagedCoopReleaseProjectionCoordinator(
                operations,
                () -> {
                    int call = refreshes.incrementAndGet();
                    return call == 1
                            ? refreshed(1L)
                            : new ManagedCoopResidentIndexRefreshService.RefreshResult(
                                    ManagedCoopResidentIndexRefreshService.RefreshStatus.REJECTED,
                                    1L,
                                    "publish_failed"
                            );
                },
                () -> 200L
        );

        ManagedCoopReleaseProjectionCoordinator.ProjectionOutcome outcome =
                coordinator.coordinate(attempt(claim, ACTUAL)).get(3, TimeUnit.SECONDS);

        assertEquals(FAILED, outcome.status());
        assertTrue(outcome.detail().contains("finalized_index_refresh_rejected:publish_failed"));
        assertNull(outcome.finalizedProjection());
        assertEquals(1, operations.finalizeCalls.get());
    }

    @Test
    void coordinatorSourceHasNoGameRuntimeDependency() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/ManagedCoopReleaseProjectionCoordinator.java"));
        assertFalse(source.contains("import com.hypixel.hytale"));
        assertFalse(source.matches("(?s).*\\b(World|Store|Ref|NPCEntity|Component)\\b.*"));
    }

    private ManagedCoopReleaseProjectionCoordinator coordinator(
            FakeOperations operations,
            ManagedCoopReleaseProjectionCoordinator.IndexRefreshGateway refresh) {
        return new ManagedCoopReleaseProjectionCoordinator(operations, refresh, () -> 200L);
    }

    private FakeOperations projectedOperations(SpawnReady claim) {
        FakeOperations operations = new FakeOperations();
        operations.markBehavior = (operationId, generation, actualUuid, nowMs) -> completedSubmission(applied(
                operation(claim, OperationState.PROJECTION_CREATED, actualUuid)));
        operations.finalizeBehavior = (operationId, generation, nowMs) -> completedSubmission(applied(
                operation(claim, OperationState.FINALIZED, ACTUAL)));
        return operations;
    }

    private ManagedCoopReleaseProjectionCoordinator.ProjectionAttempt attempt(
            SpawnReady claim,
            UUID actualTargetUuid) {
        return new ManagedCoopReleaseProjectionCoordinator.ProjectionAttempt(
                claim, actualTargetUuid, 150L);
    }

    private SpawnReady spawnClaim() {
        String snapshotHash = "a".repeat(64);
        String identity = token("resident-a")
                + token("profile-a")
                + token(AUTHORITY.authorityId())
                + token("coop_chicken")
                + token("0")
                + token(SOURCE.toString())
                + token(PLANNED.toString())
                + token(snapshotHash)
                + token("0");
        String operationId = "managed-coop-release:"
                + ManagedCoopCaptureClaimValidator.snapshotSha256(identity);
        return new SpawnReady(
                operationId,
                "profile-a",
                "resident-a",
                AUTHORITY,
                "coop_chicken",
                0,
                SOURCE,
                PLANNED,
                null,
                snapshotHash,
                0L,
                1L,
                1L,
                OperationState.SPAWN_CLAIMED,
                5L,
                true
        );
    }

    private SpawnReady copyClaim(SpawnReady source,
                                 String operationId,
                                 OperationState state,
                                 boolean spawnRequired,
                                 UUID actualTargetUuid) {
        return new SpawnReady(
                operationId, source.profileId(), source.residentId(), source.authorityKey(),
                source.coopId(), source.residentSlot(), source.sourceNpcUuid(), source.plannedTargetUuid(),
                actualTargetUuid, source.snapshotHash(), source.expectedResidentGeneration(),
                source.releasingResidentGeneration(), source.operationGeneration(), state,
                source.indexRevision(), spawnRequired
        );
    }

    private static String token(String value) {
        return value.length() + ":" + value;
    }

    private static OperationRecord operation(SpawnReady claim,
                                             OperationState state,
                                             UUID actualTargetUuid) {
        boolean active = state != OperationState.FINALIZED;
        long generation = state == OperationState.PROJECTION_CREATED ? 2L : 3L;
        return new OperationRecord(
                claim.operationId(),
                OperationKind.RELEASE,
                claim.profileId(),
                claim.authorityKey(),
                claim.coopId(),
                claim.residentSlot(),
                null,
                claim.plannedTargetUuid(),
                actualTargetUuid,
                state,
                claim.snapshotHash(),
                claim.expectedResidentGeneration(),
                generation,
                0,
                active,
                100L,
                100L,
                active ? 0L : 200L,
                null
        );
    }

    private static MutationResult applied(OperationRecord operation) {
        return new MutationResult(MutationStatus.APPLIED, operation, null);
    }

    private static MutationResult idempotent(OperationRecord operation) {
        return new MutationResult(MutationStatus.IDEMPOTENT, operation, null);
    }

    private static ManagedCoopResidentIndexRefreshService.RefreshResult refreshed(long revision) {
        return new ManagedCoopResidentIndexRefreshService.RefreshResult(
                ManagedCoopResidentIndexRefreshService.RefreshStatus.REFRESHED,
                revision,
                null
        );
    }

    private static <T> PersistenceWriteQueue.WriteSubmission<T> completedSubmission(T value) {
        return new PersistenceWriteQueue.WriteSubmission<>(
                true,
                CompletableFuture.completedFuture(committed(value))
        );
    }

    private static <T> PersistenceWriteQueue.WriteSubmission<T> submission(
            CompletableFuture<PersistenceWriteQueue.WriteOutcome<T>> completion) {
        return new PersistenceWriteQueue.WriteSubmission<>(true, completion);
    }

    private static <T> PersistenceWriteQueue.WriteSubmission<T> failedSubmission(String reason) {
        return new PersistenceWriteQueue.WriteSubmission<>(
                true,
                CompletableFuture.completedFuture(new PersistenceWriteQueue.WriteOutcome<>(
                        PersistenceWriteQueue.WriteStatus.FAILED, null, reason, null))
        );
    }

    private static <T> PersistenceWriteQueue.WriteOutcome<T> committed(T value) {
        return new PersistenceWriteQueue.WriteOutcome<>(
                PersistenceWriteQueue.WriteStatus.COMMITTED, value, null, null);
    }

    private static final class FakeOperations
            implements ManagedCoopReleaseProjectionCoordinator.OperationGateway {
        private final AtomicInteger markCalls = new AtomicInteger();
        private final AtomicInteger finalizeCalls = new AtomicInteger();
        private final AtomicReference<UUID> lastActualTarget = new AtomicReference<>();
        private MarkBehavior markBehavior;
        private FinalizeBehavior finalizeBehavior;

        @Override
        public PersistenceWriteQueue.WriteSubmission<MutationResult> markProjectionCreated(
                String operationId,
                long expectedGeneration,
                UUID actualTargetUuid,
                long nowMs) {
            markCalls.incrementAndGet();
            lastActualTarget.set(actualTargetUuid);
            return markBehavior.apply(operationId, expectedGeneration, actualTargetUuid, nowMs);
        }

        @Override
        public PersistenceWriteQueue.WriteSubmission<MutationResult> finalizeRelease(
                String operationId,
                long expectedGeneration,
                long nowMs) {
            finalizeCalls.incrementAndGet();
            return finalizeBehavior.apply(operationId, expectedGeneration, nowMs);
        }
    }

    @FunctionalInterface
    private interface MarkBehavior {
        PersistenceWriteQueue.WriteSubmission<MutationResult> apply(
                String operationId,
                long expectedGeneration,
                UUID actualTargetUuid,
                long nowMs);
    }

    @FunctionalInterface
    private interface FinalizeBehavior {
        PersistenceWriteQueue.WriteSubmission<MutationResult> apply(
                String operationId,
                long expectedGeneration,
                long nowMs);
    }
}
