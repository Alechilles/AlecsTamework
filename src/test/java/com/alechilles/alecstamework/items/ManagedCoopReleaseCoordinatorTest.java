package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationResult;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationStatus;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationKind;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.ReleaseRequest;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureClaimValidator;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
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
import java.util.function.Function;
import org.junit.jupiter.api.Test;

import static com.alechilles.alecstamework.items.ManagedCoopReleaseCoordinator.OutcomeStatus.ALREADY_PROJECTED;
import static com.alechilles.alecstamework.items.ManagedCoopReleaseCoordinator.OutcomeStatus.DEDUPLICATED;
import static com.alechilles.alecstamework.items.ManagedCoopReleaseCoordinator.OutcomeStatus.FAILED;
import static com.alechilles.alecstamework.items.ManagedCoopReleaseCoordinator.OutcomeStatus.SPAWN_READY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for managed-coop release ordering, replay, and fail-closed boundaries. */
class ManagedCoopReleaseCoordinatorTest {
    private static final UUID SOURCE_A = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SOURCE_B = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID PLANNED_A = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID PLANNED_B = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final ManagedCoopAuthorityKey AUTHORITY =
            new ManagedCoopAuthorityKey("world", 10, 20, 30);

    @Test
    void refreshesBothDurableReleaseTransitionsBeforeReadyCompletion() throws Exception {
        List<String> events = new ArrayList<>();
        CompletableFuture<PersistenceWriteQueue.WriteOutcome<MutationResult>> prepareCommit =
                new CompletableFuture<>();
        CompletableFuture<PersistenceWriteQueue.WriteOutcome<MutationResult>> claimCommit =
                new CompletableFuture<>();
        AtomicReference<ReleaseRequest> preparedRequest = new AtomicReference<>();
        FakeOperations operations = new FakeOperations();
        operations.prepareBehavior = request -> {
            events.add("prepare_submitted");
            preparedRequest.set(request);
            return submission(prepareCommit);
        };
        operations.claimBehavior = (operationId, generation, nowMs) -> {
            events.add("spawn_claim_submitted");
            assertEquals(0L, generation);
            assertEquals(900L, nowMs);
            return submission(claimCommit);
        };
        AtomicInteger refreshCount = new AtomicInteger();
        ManagedCoopReleaseCoordinator coordinator = new ManagedCoopReleaseCoordinator(
                operations,
                () -> {
                    int call = refreshCount.incrementAndGet();
                    events.add(call == 1 ? "prepare_index_refreshed" : "claim_index_refreshed");
                    return refreshed(call == 1 ? 8L : 9L);
                },
                () -> 900L
        );

        CompletableFuture<ManagedCoopReleaseCoordinator.ReleaseOutcome> completion =
                coordinator.coordinate(attempt(resident("resident-a", "profile-a", SOURCE_A, 0), PLANNED_A));
        assertEquals(List.of("prepare_submitted"), events);
        assertFalse(completion.isDone());

        ReleaseRequest request = preparedRequest.get();
        prepareCommit.complete(committed(applied(operation(request, OperationState.PREPARED, 0L))));
        assertEquals(
                List.of("prepare_submitted", "prepare_index_refreshed", "spawn_claim_submitted"),
                events
        );
        assertFalse(completion.isDone());

        claimCommit.complete(committed(applied(operation(request, OperationState.SPAWN_CLAIMED, 1L))));
        ManagedCoopReleaseCoordinator.ReleaseOutcome outcome = completion.get(3, TimeUnit.SECONDS);
        assertEquals(
                List.of("prepare_submitted", "prepare_index_refreshed", "spawn_claim_submitted",
                        "claim_index_refreshed"),
                events
        );
        assertEquals(SPAWN_READY, outcome.status());
        assertTrue(outcome.isSpawnReady());
        ManagedCoopReleaseCoordinator.SpawnReady ready = outcome.spawnReady();
        assertNotNull(ready);
        assertEquals("profile-a", ready.profileId());
        assertEquals("resident-a", ready.residentId());
        assertEquals(SOURCE_A, ready.sourceNpcUuid());
        assertEquals(PLANNED_A, ready.plannedTargetUuid());
        assertEquals(0L, ready.expectedResidentGeneration());
        assertEquals(1L, ready.releasingResidentGeneration());
        assertEquals(1L, ready.operationGeneration());
        assertEquals(9L, ready.indexRevision());
    }

    @Test
    void deduplicatesConcurrentResidentAndProfileReleaseCalls() throws Exception {
        CompletableFuture<PersistenceWriteQueue.WriteOutcome<MutationResult>> prepareCommit =
                new CompletableFuture<>();
        FakeOperations operations = new FakeOperations();
        operations.prepareBehavior = request -> submission(prepareCommit);
        operations.claimBehavior = (operationId, generation, nowMs) -> {
            ReleaseRequest request = operations.lastRequest.get();
            return completedSubmission(applied(operation(request, OperationState.SPAWN_CLAIMED, 1L)));
        };
        ManagedCoopReleaseCoordinator coordinator = new ManagedCoopReleaseCoordinator(
                operations, () -> refreshed(1L), () -> 200L);
        ManagedCoopReleaseCoordinator.ReleaseAttempt firstAttempt =
                attempt(resident("resident-a", "profile-a", SOURCE_A, 0), PLANNED_A);

        CompletableFuture<ManagedCoopReleaseCoordinator.ReleaseOutcome> first =
                coordinator.coordinate(firstAttempt);
        ManagedCoopReleaseCoordinator.ReleaseOutcome residentDuplicate =
                coordinator.coordinate(firstAttempt).get(3, TimeUnit.SECONDS);
        ManagedCoopReleaseCoordinator.ReleaseOutcome profileDuplicate = coordinator.coordinate(
                attempt(resident("resident-b", "profile-a", SOURCE_B, 1), PLANNED_B)
        ).get(3, TimeUnit.SECONDS);

        assertEquals(DEDUPLICATED, residentDuplicate.status());
        assertEquals("release_resident_already_in_flight", residentDuplicate.detail());
        assertEquals(DEDUPLICATED, profileDuplicate.status());
        assertEquals("release_profile_already_in_flight", profileDuplicate.detail());
        assertEquals(1, operations.prepareCalls.get());

        ReleaseRequest request = operations.lastRequest.get();
        prepareCommit.complete(committed(applied(operation(request, OperationState.PREPARED, 0L))));
        assertEquals(SPAWN_READY, first.get(3, TimeUnit.SECONDS).status());
    }

    @Test
    void replaysSpawnClaimedProjectedAndFinalizedWithoutSecondSpawnClaim() throws Exception {
        for (OperationState state : List.of(
                OperationState.SPAWN_CLAIMED,
                OperationState.PROJECTION_CREATED,
                OperationState.FINALIZED)) {
            List<String> operationIds = new ArrayList<>();
            FakeOperations operations = new FakeOperations();
            operations.prepareBehavior = request -> {
                operationIds.add(request.operationId());
                return completedSubmission(idempotent(operation(request, state, generation(state))));
            };
            operations.claimBehavior = (operationId, generation, nowMs) -> {
                throw new AssertionError("replay state must not issue a second spawn claim");
            };
            ManagedCoopReleaseCoordinator coordinator = new ManagedCoopReleaseCoordinator(
                    operations, () -> refreshed(4L), () -> 200L);
            ManagedCoopReleaseCoordinator.ReleaseAttempt attempt =
                    attempt(resident("resident-a", "profile-a", SOURCE_A, 0), PLANNED_A);

            ManagedCoopReleaseCoordinator.ReleaseOutcome first =
                    coordinator.coordinate(attempt).get(3, TimeUnit.SECONDS);
            ManagedCoopReleaseCoordinator.ReleaseOutcome replay =
                    coordinator.coordinate(attempt).get(3, TimeUnit.SECONDS);

            assertEquals(state == OperationState.SPAWN_CLAIMED ? SPAWN_READY : ALREADY_PROJECTED,
                    first.status());
            assertEquals(first.status(), replay.status());
            assertEquals(state == OperationState.SPAWN_CLAIMED, first.spawnReady().spawnRequired());
            assertEquals(state, first.spawnReady().durableState());
            assertEquals(2, operations.prepareCalls.get());
            assertEquals(operationIds.get(0), operationIds.get(1));
            assertEquals(0, operations.claimCalls.get());
        }
    }

    @Test
    void preparedClaimResultAndIdentityMismatchFailBeforeReady() throws Exception {
        AtomicInteger refreshCalls = new AtomicInteger();
        FakeOperations preparedOperations = new FakeOperations();
        preparedOperations.prepareBehavior = request -> completedSubmission(applied(
                operation(request, OperationState.PREPARED, 0L)));
        preparedOperations.claimBehavior = (operationId, generation, nowMs) -> {
            ReleaseRequest request = preparedOperations.lastRequest.get();
            return completedSubmission(applied(operation(request, OperationState.PREPARED, 0L)));
        };
        ManagedCoopReleaseCoordinator preparedCoordinator = new ManagedCoopReleaseCoordinator(
                preparedOperations,
                () -> {
                    refreshCalls.incrementAndGet();
                    return refreshed(1L);
                },
                () -> 200L
        );
        ManagedCoopReleaseCoordinator.ReleaseOutcome prepared = preparedCoordinator.coordinate(
                attempt(resident("resident-a", "profile-a", SOURCE_A, 0), PLANNED_A)
        ).get(3, TimeUnit.SECONDS);
        assertEquals(FAILED, prepared.status());
        assertTrue(prepared.detail().contains("release_spawn_claim_operation_identity_or_state_mismatch"));
        assertEquals(1, refreshCalls.get());

        FakeOperations mismatchedOperations = new FakeOperations();
        mismatchedOperations.prepareBehavior = request -> completedSubmission(applied(
                operationWithPlanned(request, OperationState.PREPARED, 0L, PLANNED_B)));
        mismatchedOperations.claimBehavior = (operationId, generation, nowMs) -> {
            throw new AssertionError("identity mismatch must not claim spawn");
        };
        ManagedCoopReleaseCoordinator mismatchedCoordinator = new ManagedCoopReleaseCoordinator(
                mismatchedOperations,
                () -> {
                    throw new AssertionError("identity mismatch must not refresh index");
                },
                () -> 200L
        );
        ManagedCoopReleaseCoordinator.ReleaseOutcome mismatch = mismatchedCoordinator.coordinate(
                attempt(resident("resident-a", "profile-a", SOURCE_A, 0), PLANNED_A)
        ).get(3, TimeUnit.SECONDS);
        assertEquals(FAILED, mismatch.status());
        assertTrue(mismatch.detail().contains("release_prepare_operation_identity_or_state_mismatch"));
    }

    @Test
    void prepareConflictAndRefreshFailureNeverClaimSpawn() throws Exception {
        AtomicInteger refreshCalls = new AtomicInteger();
        FakeOperations conflictOperations = new FakeOperations();
        conflictOperations.prepareBehavior = request -> completedSubmission(
                new MutationResult(MutationStatus.CONFLICT, null, "resident_conflict"));
        conflictOperations.claimBehavior = (operationId, generation, nowMs) -> {
            throw new AssertionError("prepare conflict must not claim spawn");
        };
        ManagedCoopReleaseCoordinator conflictCoordinator = new ManagedCoopReleaseCoordinator(
                conflictOperations,
                () -> {
                    refreshCalls.incrementAndGet();
                    return refreshed(1L);
                },
                () -> 200L
        );
        ManagedCoopReleaseCoordinator.ReleaseOutcome conflict = conflictCoordinator.coordinate(
                attempt(resident("resident-a", "profile-a", SOURCE_A, 0), PLANNED_A)
        ).get(3, TimeUnit.SECONDS);
        assertEquals(FAILED, conflict.status());
        assertTrue(conflict.detail().contains("release_prepare_conflict:resident_conflict"));
        assertEquals(0, refreshCalls.get());

        FakeOperations refreshOperations = new FakeOperations();
        refreshOperations.prepareBehavior = request -> completedSubmission(applied(
                operation(request, OperationState.PREPARED, 0L)));
        refreshOperations.claimBehavior = (operationId, generation, nowMs) -> {
            throw new AssertionError("failed refresh must not claim spawn");
        };
        ManagedCoopReleaseCoordinator refreshCoordinator = new ManagedCoopReleaseCoordinator(
                refreshOperations,
                () -> new ManagedCoopResidentIndexRefreshService.RefreshResult(
                        ManagedCoopResidentIndexRefreshService.RefreshStatus.REJECTED,
                        0L,
                        "sql_failure"
                ),
                () -> 200L
        );
        ManagedCoopReleaseCoordinator.ReleaseOutcome refreshFailure = refreshCoordinator.coordinate(
                attempt(resident("resident-a", "profile-a", SOURCE_A, 0), PLANNED_A)
        ).get(3, TimeUnit.SECONDS);
        assertEquals(FAILED, refreshFailure.status());
        assertTrue(refreshFailure.detail().contains("resident_index_refresh_rejected:sql_failure"));

        AtomicInteger postClaimRefreshes = new AtomicInteger();
        FakeOperations postClaimOperations = new FakeOperations();
        postClaimOperations.prepareBehavior = request -> completedSubmission(applied(
                operation(request, OperationState.PREPARED, 0L)));
        postClaimOperations.claimBehavior = (operationId, generation, nowMs) -> {
            ReleaseRequest request = postClaimOperations.lastRequest.get();
            return completedSubmission(applied(
                    operation(request, OperationState.SPAWN_CLAIMED, 1L)));
        };
        ManagedCoopReleaseCoordinator postClaimRefreshFailure = new ManagedCoopReleaseCoordinator(
                postClaimOperations,
                () -> postClaimRefreshes.incrementAndGet() == 1
                        ? refreshed(1L)
                        : new ManagedCoopResidentIndexRefreshService.RefreshResult(
                                ManagedCoopResidentIndexRefreshService.RefreshStatus.REJECTED,
                                1L,
                                "operation_snapshot_failed"
                        ),
                () -> 200L
        );
        ManagedCoopReleaseCoordinator.ReleaseOutcome staleOperationIndex =
                postClaimRefreshFailure.coordinate(
                        attempt(resident("resident-a", "profile-a", SOURCE_A, 0), PLANNED_A)
                ).get(3, TimeUnit.SECONDS);
        assertEquals(FAILED, staleOperationIndex.status());
        assertTrue(staleOperationIndex.detail().contains(
                "spawn_claim_index_refresh_rejected:operation_snapshot_failed"));
        assertNull(staleOperationIndex.spawnReady());
    }

    @Test
    void failedSpawnClaimAndInvalidResidentNeverEmitSpawnReady() throws Exception {
        FakeOperations operations = new FakeOperations();
        operations.prepareBehavior = request -> completedSubmission(applied(
                operation(request, OperationState.PREPARED, 0L)));
        operations.claimBehavior = (operationId, generation, nowMs) -> failedSubmission("write_failed");
        ManagedCoopReleaseCoordinator coordinator = new ManagedCoopReleaseCoordinator(
                operations, () -> refreshed(1L), () -> 200L);
        ManagedCoopReleaseCoordinator.ReleaseOutcome failedClaim = coordinator.coordinate(
                attempt(resident("resident-a", "profile-a", SOURCE_A, 0), PLANNED_A)
        ).get(3, TimeUnit.SECONDS);
        assertEquals(FAILED, failedClaim.status());
        assertTrue(failedClaim.detail().contains("release_spawn_claim_not_committed:write_failed"));
        assertNull(failedClaim.spawnReady());

        ResidentRecord releasing = withState(
                resident("resident-a", "profile-a", SOURCE_A, 0), ResidentState.RELEASING);
        ManagedCoopReleaseCoordinator.ReleaseOutcome invalid = coordinator.coordinate(
                attempt(releasing, PLANNED_A)
        ).get(3, TimeUnit.SECONDS);
        assertEquals(FAILED, invalid.status());
        assertTrue(invalid.detail().contains("release requires one active committed HOUSED resident"));

        ResidentRecord base = resident("resident-a", "profile-a", SOURCE_A, 0);
        String wrongContextJson = base.snapshotJson().replace("coop_chicken", "coop_duck");
        ManagedCoopReleaseCoordinator.ReleaseOutcome badSnapshot = coordinator.coordinate(
                attempt(withSnapshot(base, wrongContextJson), PLANNED_A)
        ).get(3, TimeUnit.SECONDS);
        assertEquals(FAILED, badSnapshot.status());
        assertTrue(badSnapshot.detail().contains("snapshot metadata does not match resident context"));
        assertEquals(1, operations.prepareCalls.get());
    }

    @Test
    void coordinatorSourceHasNoGameRuntimeDependency() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/ManagedCoopReleaseCoordinator.java"));
        assertFalse(source.contains("import com.hypixel.hytale"));
        assertFalse(source.matches("(?s).*\\b(World|Store|Ref|NPCEntity|Component)\\b.*"));
    }

    private ManagedCoopReleaseCoordinator.ReleaseAttempt attempt(ResidentRecord resident, UUID plannedUuid) {
        return new ManagedCoopReleaseCoordinator.ReleaseAttempt(resident, plannedUuid, 100L);
    }

    private ResidentRecord resident(String residentId,
                                    String profileId,
                                    UUID sourceUuid,
                                    int slot) {
        String snapshot = "{\"version\":\"1\",\"npcUuid\":\"" + sourceUuid
                + "\",\"coopId\":\"coop_chicken\",\"residentSlot\":" + slot
                + ",\"roleId\":\"mob_chicken\",\"capturedAtMs\":100}";
        return new ResidentRecord(
                residentId,
                AUTHORITY,
                "coop_chicken",
                slot,
                profileId,
                "mob_chicken",
                sourceUuid,
                sourceUuid,
                null,
                snapshot,
                ManagedCoopCaptureClaimValidator.snapshotSha256(snapshot),
                1,
                ResidentState.HOUSED,
                0L,
                true,
                100L,
                0L,
                100L,
                100L
        );
    }

    private ResidentRecord withState(ResidentRecord source, ResidentState state) {
        return new ResidentRecord(
                source.residentId(), source.authorityKey(), source.coopId(), source.residentSlot(),
                source.profileId(), source.roleId(), source.residentUuid(), source.sourceNpcUuid(),
                source.deployedNpcUuid(), source.snapshotJson(), source.snapshotHash(), source.snapshotVersion(),
                state, source.generation(), source.active(), source.capturedAtMs(), source.releasedAtMs(),
                source.createdAtMs(), source.updatedAtMs()
        );
    }

    private ResidentRecord withSnapshot(ResidentRecord source, String snapshotJson) {
        return new ResidentRecord(
                source.residentId(), source.authorityKey(), source.coopId(), source.residentSlot(),
                source.profileId(), source.roleId(), source.residentUuid(), source.sourceNpcUuid(),
                source.deployedNpcUuid(), snapshotJson,
                ManagedCoopCaptureClaimValidator.snapshotSha256(snapshotJson), source.snapshotVersion(),
                source.state(), source.generation(), source.active(), source.capturedAtMs(), source.releasedAtMs(),
                source.createdAtMs(), source.updatedAtMs()
        );
    }

    private static long generation(OperationState state) {
        return switch (state) {
            case SPAWN_CLAIMED -> 1L;
            case PROJECTION_CREATED -> 2L;
            case FINALIZED -> 3L;
            default -> 0L;
        };
    }

    private static MutationResult applied(OperationRecord operation) {
        return new MutationResult(MutationStatus.APPLIED, operation, null);
    }

    private static MutationResult idempotent(OperationRecord operation) {
        return new MutationResult(MutationStatus.IDEMPOTENT, operation, null);
    }

    private static OperationRecord operation(ReleaseRequest request,
                                             OperationState state,
                                             long generation) {
        return operationWithPlanned(request, state, generation, request.plannedTargetUuid());
    }

    private static OperationRecord operationWithPlanned(ReleaseRequest request,
                                                        OperationState state,
                                                        long generation,
                                                        UUID plannedTargetUuid) {
        boolean active = state != OperationState.FINALIZED;
        UUID actualTargetUuid = state == OperationState.PROJECTION_CREATED || state == OperationState.FINALIZED
                ? request.plannedTargetUuid()
                : null;
        return new OperationRecord(
                request.operationId(),
                OperationKind.RELEASE,
                request.profileId(),
                request.authorityKey(),
                request.coopId(),
                request.residentSlot(),
                null,
                plannedTargetUuid,
                actualTargetUuid,
                state,
                request.snapshotHash(),
                request.expectedResidentGeneration(),
                generation,
                0,
                active,
                100L,
                100L,
                active ? 0L : 200L,
                null
        );
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

    private static final class FakeOperations implements ManagedCoopReleaseCoordinator.OperationGateway {
        private final AtomicInteger prepareCalls = new AtomicInteger();
        private final AtomicInteger claimCalls = new AtomicInteger();
        private final AtomicReference<ReleaseRequest> lastRequest = new AtomicReference<>();
        private Function<ReleaseRequest, PersistenceWriteQueue.WriteSubmission<MutationResult>> prepareBehavior;
        private ClaimBehavior claimBehavior;

        @Override
        public PersistenceWriteQueue.WriteSubmission<MutationResult> prepareRelease(ReleaseRequest request) {
            prepareCalls.incrementAndGet();
            lastRequest.set(request);
            return prepareBehavior.apply(request);
        }

        @Override
        public PersistenceWriteQueue.WriteSubmission<MutationResult> claimSpawn(
                String operationId,
                long expectedGeneration,
                long nowMs) {
            claimCalls.incrementAndGet();
            return claimBehavior.apply(operationId, expectedGeneration, nowMs);
        }
    }

    @FunctionalInterface
    private interface ClaimBehavior {
        PersistenceWriteQueue.WriteSubmission<MutationResult> apply(
                String operationId,
                long expectedGeneration,
                long nowMs);
    }
}
