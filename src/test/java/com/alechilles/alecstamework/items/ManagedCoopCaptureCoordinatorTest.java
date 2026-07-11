package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.CaptureRequest;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationResult;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationStatus;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationKind;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureClaimValidator;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureProfileRepository.ProfileIdentity;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

import static com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.OutcomeStatus.DEDUPLICATED;
import static com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.OutcomeStatus.FAILED;
import static com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.OutcomeStatus.RETIREMENT_READY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for durable managed-coop capture coordination and callback ordering. */
class ManagedCoopCaptureCoordinatorTest {
    private static final UUID SOURCE_A = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SOURCE_B = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final ManagedCoopAuthorityKey AUTHORITY =
            new ManagedCoopAuthorityKey("world", 10, 20, 30);

    @Test
    void refreshesBothDurableCaptureTransitionsBeforeReadyCompletion() throws Exception {
        List<String> events = new ArrayList<>();
        CompletableFuture<PersistenceWriteQueue.WriteOutcome<ProfileIdentity>> profileCommit =
                new CompletableFuture<>();
        CompletableFuture<PersistenceWriteQueue.WriteOutcome<MutationResult>> claimCommit =
                new CompletableFuture<>();
        CompletableFuture<PersistenceWriteQueue.WriteOutcome<MutationResult>> retireCommit =
                new CompletableFuture<>();
        AtomicReference<CaptureRequest> claimedRequest = new AtomicReference<>();
        AtomicReference<String[]> seededTools = new AtomicReference<>();

        ManagedCoopCaptureCoordinator.ProfileGateway profiles = seed -> {
            events.add("profile_submitted");
            seededTools.set(seed.toolIds());
            return submission(profileCommit);
        };
        FakeOperations operations = new FakeOperations();
        operations.claimBehavior = request -> {
            events.add("claim_submitted");
            claimedRequest.set(request);
            return submission(claimCommit);
        };
        operations.retireBehavior = (operationId, generation, nowMs) -> {
            events.add("retirement_submitted");
            assertEquals(1L, generation);
            assertEquals(900L, nowMs);
            return submission(retireCommit);
        };
        AtomicInteger refreshCount = new AtomicInteger();
        ManagedCoopCaptureCoordinator.IndexRefreshGateway refresh = () -> {
            int call = refreshCount.incrementAndGet();
            events.add(call == 1 ? "slot_index_refreshed" : "retirement_index_refreshed");
            return refreshed(call == 1 ? 7L : 8L);
        };
        ManagedCoopCaptureCoordinator coordinator =
                new ManagedCoopCaptureCoordinator(profiles, operations, refresh, () -> 900L);

        String[] toolIds = {"tool-a"};
        ManagedCoopCaptureCoordinator.CaptureAttempt attempt = attempt(SOURCE_A, toolIds);
        toolIds[0] = "mutated";
        CompletableFuture<ManagedCoopCaptureCoordinator.CaptureOutcome> completion =
                coordinator.coordinate(attempt);
        assertEquals(List.of("profile_submitted"), events);
        assertFalse(completion.isDone());

        profileCommit.complete(committed(new ProfileIdentity("profile-a", SOURCE_A)));
        assertEquals(List.of("profile_submitted", "claim_submitted"), events);
        assertArrayEquals(new String[]{"tool-a"}, seededTools.get());
        assertFalse(completion.isDone());

        CaptureRequest request = claimedRequest.get();
        claimCommit.complete(committed(applied(operation(request, OperationState.SLOT_COMMITTED, 1L))));
        assertEquals(
                List.of("profile_submitted", "claim_submitted", "slot_index_refreshed",
                        "retirement_submitted"),
                events
        );
        assertFalse(completion.isDone());

        retireCommit.complete(committed(applied(
                operation(request, OperationState.SOURCE_RETIRE_REQUESTED, 2L))));
        ManagedCoopCaptureCoordinator.CaptureOutcome outcome = completion.get(3, TimeUnit.SECONDS);
        assertEquals(
                List.of("profile_submitted", "claim_submitted", "slot_index_refreshed",
                        "retirement_submitted", "retirement_index_refreshed"),
                events
        );
        assertEquals(RETIREMENT_READY, outcome.status());
        assertTrue(outcome.isRetirementReady());
        assertNotNull(outcome.retirementReady());
        assertEquals(SOURCE_A, outcome.retirementReady().sourceNpcUuid());
        assertEquals("profile-a", outcome.retirementReady().profileId());
        assertEquals(OperationState.SOURCE_RETIRE_REQUESTED, outcome.retirementReady().durableState());
        assertEquals(8L, outcome.retirementReady().indexRevision());
    }

    @Test
    void deduplicatesAnInFlightSourceBeforeSubmittingAnotherProfileWrite() throws Exception {
        CompletableFuture<PersistenceWriteQueue.WriteOutcome<ProfileIdentity>> profileCommit =
                new CompletableFuture<>();
        AtomicInteger profileCalls = new AtomicInteger();
        ManagedCoopCaptureCoordinator.ProfileGateway profiles = seed -> {
            profileCalls.incrementAndGet();
            return submission(profileCommit);
        };
        FakeOperations operations = readyOperations();
        ManagedCoopCaptureCoordinator coordinator = new ManagedCoopCaptureCoordinator(
                profiles,
                operations,
                () -> refreshed(1L),
                () -> 200L
        );
        ManagedCoopCaptureCoordinator.CaptureAttempt attempt = attempt(SOURCE_A, new String[0]);

        CompletableFuture<ManagedCoopCaptureCoordinator.CaptureOutcome> first = coordinator.coordinate(attempt);
        ManagedCoopCaptureCoordinator.CaptureOutcome duplicate =
                coordinator.coordinate(attempt).get(3, TimeUnit.SECONDS);

        assertEquals(DEDUPLICATED, duplicate.status());
        assertEquals("capture_source_already_in_flight", duplicate.detail());
        assertEquals(1, profileCalls.get());
        profileCommit.complete(committed(new ProfileIdentity("profile-a", SOURCE_A)));
        assertEquals(RETIREMENT_READY, first.get(3, TimeUnit.SECONDS).status());
    }

    @Test
    void deduplicatesDifferentSourceAliasesThatResolveToOneInFlightProfile() throws Exception {
        Map<UUID, CompletableFuture<PersistenceWriteQueue.WriteOutcome<ProfileIdentity>>> profileCommits =
                new HashMap<>();
        profileCommits.put(SOURCE_A, new CompletableFuture<>());
        profileCommits.put(SOURCE_B, new CompletableFuture<>());
        ManagedCoopCaptureCoordinator.ProfileGateway profiles = seed ->
                submission(profileCommits.get(seed.sourceNpcUuid()));
        CompletableFuture<PersistenceWriteQueue.WriteOutcome<MutationResult>> firstClaim =
                new CompletableFuture<>();
        FakeOperations operations = new FakeOperations();
        operations.claimBehavior = ignored -> submission(firstClaim);
        operations.retireBehavior = (operationId, generation, nowMs) -> {
            CaptureRequest request = operations.lastRequest.get();
            return completedSubmission(applied(
                    operation(request, OperationState.SOURCE_RETIRE_REQUESTED, 2L)));
        };
        ManagedCoopCaptureCoordinator coordinator = new ManagedCoopCaptureCoordinator(
                profiles,
                operations,
                () -> refreshed(1L),
                () -> 200L
        );

        CompletableFuture<ManagedCoopCaptureCoordinator.CaptureOutcome> first =
                coordinator.coordinate(attempt(SOURCE_A, new String[0]));
        CompletableFuture<ManagedCoopCaptureCoordinator.CaptureOutcome> second =
                coordinator.coordinate(attempt(SOURCE_B, new String[0]));
        profileCommits.get(SOURCE_A).complete(committed(new ProfileIdentity("shared-profile", SOURCE_A)));
        profileCommits.get(SOURCE_B).complete(committed(new ProfileIdentity("shared-profile", SOURCE_B)));

        ManagedCoopCaptureCoordinator.CaptureOutcome duplicate = second.get(3, TimeUnit.SECONDS);
        assertEquals(DEDUPLICATED, duplicate.status());
        assertEquals("capture_profile_already_in_flight", duplicate.detail());
        assertEquals(1, operations.claimCalls.get());
        assertFalse(first.isDone());
        CaptureRequest firstRequest = operations.lastRequest.get();
        firstClaim.complete(committed(applied(
                operation(firstRequest, OperationState.SLOT_COMMITTED, 1L))));
        assertEquals(RETIREMENT_READY, first.get(3, TimeUnit.SECONDS).status());
    }

    @Test
    void replaysRetirementRequestedAndCompleteWithoutAnotherRetirementWrite() throws Exception {
        for (OperationState state : List.of(OperationState.SOURCE_RETIRE_REQUESTED, OperationState.COMPLETE)) {
            AtomicInteger retireCalls = new AtomicInteger();
            FakeOperations operations = new FakeOperations();
            operations.claimBehavior = request -> completedSubmission(idempotent(
                    operation(request, state, state == OperationState.COMPLETE ? 3L : 2L)
            ));
            operations.retireBehavior = (operationId, generation, nowMs) -> {
                retireCalls.incrementAndGet();
                throw new AssertionError("replay state is already retirement-safe");
            };
            ManagedCoopCaptureCoordinator coordinator = coordinator(
                    SOURCE_A, "profile-a", operations, () -> refreshed(4L));

            ManagedCoopCaptureCoordinator.CaptureOutcome outcome =
                    coordinator.coordinate(attempt(SOURCE_A, new String[0])).get(3, TimeUnit.SECONDS);
            ManagedCoopCaptureCoordinator.CaptureOutcome replay =
                    coordinator.coordinate(attempt(SOURCE_A, new String[0])).get(3, TimeUnit.SECONDS);

            assertEquals(RETIREMENT_READY, outcome.status());
            assertEquals(state, outcome.retirementReady().durableState());
            assertEquals(RETIREMENT_READY, replay.status());
            assertEquals(state, replay.retirementReady().durableState());
            assertEquals(2, operations.claimCalls.get());
            assertEquals(0, retireCalls.get());
        }
    }

    @Test
    void rejectsProfileAndClaimFailuresWithoutRefreshingOrRetiring() throws Exception {
        AtomicInteger claimCalls = new AtomicInteger();
        AtomicInteger refreshCalls = new AtomicInteger();
        FakeOperations operations = new FakeOperations();
        operations.claimBehavior = request -> {
            claimCalls.incrementAndGet();
            return completedSubmission(new MutationResult(MutationStatus.CONFLICT, null, "slot_conflict"));
        };
        operations.retireBehavior = (operationId, generation, nowMs) -> {
            throw new AssertionError("failed capture must not retire");
        };
        ManagedCoopCaptureCoordinator rejectedProfile = new ManagedCoopCaptureCoordinator(
                seed -> rejectedSubmission("profile_queue_closed"),
                operations,
                () -> {
                    refreshCalls.incrementAndGet();
                    return refreshed(1L);
                },
                () -> 200L
        );
        ManagedCoopCaptureCoordinator.CaptureOutcome profileFailure =
                rejectedProfile.coordinate(attempt(SOURCE_A, new String[0])).get(3, TimeUnit.SECONDS);
        assertEquals(FAILED, profileFailure.status());
        assertTrue(profileFailure.detail().contains("profile_ensure_not_committed"));
        assertEquals(0, claimCalls.get());

        ManagedCoopCaptureCoordinator claimConflict = coordinator(
                SOURCE_A,
                "profile-a",
                operations,
                () -> {
                    refreshCalls.incrementAndGet();
                    return refreshed(1L);
                }
        );
        ManagedCoopCaptureCoordinator.CaptureOutcome conflict =
                claimConflict.coordinate(attempt(SOURCE_A, new String[0])).get(3, TimeUnit.SECONDS);
        assertEquals(FAILED, conflict.status());
        assertTrue(conflict.detail().contains("capture_claim_conflict:slot_conflict"));
        assertEquals(0, refreshCalls.get());
    }

    @Test
    void successfulMutationThatRemainsPreparedFailsClosedBeforeIndexRefresh() throws Exception {
        AtomicInteger refreshCalls = new AtomicInteger();
        FakeOperations operations = new FakeOperations();
        operations.claimBehavior = request -> completedSubmission(applied(
                operation(request, OperationState.PREPARED, 0L)));
        operations.retireBehavior = (operationId, generation, nowMs) -> {
            throw new AssertionError("PREPARED is not retirement-safe");
        };
        ManagedCoopCaptureCoordinator coordinator = coordinator(
                SOURCE_A,
                "profile-a",
                operations,
                () -> {
                    refreshCalls.incrementAndGet();
                    return refreshed(1L);
                }
        );

        ManagedCoopCaptureCoordinator.CaptureOutcome outcome =
                coordinator.coordinate(attempt(SOURCE_A, new String[0])).get(3, TimeUnit.SECONDS);

        assertEquals(FAILED, outcome.status());
        assertTrue(outcome.detail().contains("capture_claim_operation_identity_or_state_mismatch"));
        assertEquals(0, refreshCalls.get());
    }

    @Test
    void indexOrRetirementCommitFailureNeverEmitsReady() throws Exception {
        FakeOperations indexOperations = new FakeOperations();
        indexOperations.claimBehavior = request -> completedSubmission(applied(
                operation(request, OperationState.SLOT_COMMITTED, 1L)));
        indexOperations.retireBehavior = (operationId, generation, nowMs) -> {
            throw new AssertionError("rejected refresh must block retirement");
        };
        ManagedCoopCaptureCoordinator indexFailure = coordinator(
                SOURCE_A,
                "profile-a",
                indexOperations,
                () -> new ManagedCoopResidentIndexRefreshService.RefreshResult(
                        ManagedCoopResidentIndexRefreshService.RefreshStatus.REJECTED,
                        0L,
                        "sql_failure"
                )
        );
        ManagedCoopCaptureCoordinator.CaptureOutcome rejected =
                indexFailure.coordinate(attempt(SOURCE_A, new String[0])).get(3, TimeUnit.SECONDS);
        assertEquals(FAILED, rejected.status());
        assertTrue(rejected.detail().contains("resident_index_refresh_rejected:sql_failure"));

        FakeOperations retireOperations = new FakeOperations();
        retireOperations.claimBehavior = request -> completedSubmission(applied(
                operation(request, OperationState.SLOT_COMMITTED, 1L)));
        retireOperations.retireBehavior = (operationId, generation, nowMs) ->
                failedSubmission("retirement_write_failed");
        ManagedCoopCaptureCoordinator retireFailure = coordinator(
                SOURCE_A, "profile-a", retireOperations, () -> refreshed(2L));
        ManagedCoopCaptureCoordinator.CaptureOutcome failedRetirement =
                retireFailure.coordinate(attempt(SOURCE_A, new String[0])).get(3, TimeUnit.SECONDS);
        assertEquals(FAILED, failedRetirement.status());
        assertTrue(failedRetirement.detail().contains("source_retirement_request_not_committed"));
        assertNull(failedRetirement.retirementReady());

        AtomicInteger refreshCalls = new AtomicInteger();
        FakeOperations postRetirementRefreshOperations = readyOperations();
        ManagedCoopCaptureCoordinator postRetirementRefreshFailure = coordinator(
                SOURCE_A,
                "profile-a",
                postRetirementRefreshOperations,
                () -> refreshCalls.incrementAndGet() == 1
                        ? refreshed(1L)
                        : new ManagedCoopResidentIndexRefreshService.RefreshResult(
                                ManagedCoopResidentIndexRefreshService.RefreshStatus.REJECTED,
                                1L,
                                "operation_snapshot_failed"
                        )
        );
        ManagedCoopCaptureCoordinator.CaptureOutcome staleOperationIndex =
                postRetirementRefreshFailure.coordinate(attempt(SOURCE_A, new String[0]))
                        .get(3, TimeUnit.SECONDS);
        assertEquals(FAILED, staleOperationIndex.status());
        assertTrue(staleOperationIndex.detail().contains(
                "source_retirement_index_refresh_rejected:operation_snapshot_failed"));
        assertNull(staleOperationIndex.retirementReady());
    }

    @Test
    void coordinatorSourceHasNoGameRuntimeDependency() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/ManagedCoopCaptureCoordinator.java"));
        assertFalse(source.contains("import com.hypixel.hytale"));
        assertFalse(source.matches("(?s).*\\b(World|Store|Ref|NPCEntity|Component)\\b.*"));
    }

    private ManagedCoopCaptureCoordinator coordinator(UUID sourceUuid,
                                                       String profileId,
                                                       FakeOperations operations,
                                                       ManagedCoopCaptureCoordinator.IndexRefreshGateway refresh) {
        return new ManagedCoopCaptureCoordinator(
                seed -> completedSubmission(new ProfileIdentity(profileId, sourceUuid)),
                operations,
                refresh,
                () -> 200L
        );
    }

    private FakeOperations readyOperations() {
        FakeOperations operations = new FakeOperations();
        operations.claimBehavior = request -> completedSubmission(applied(
                operation(request, OperationState.SLOT_COMMITTED, 1L)));
        operations.retireBehavior = (operationId, generation, nowMs) -> {
            CaptureRequest request = operations.lastRequest.get();
            return completedSubmission(applied(
                    operation(request, OperationState.SOURCE_RETIRE_REQUESTED, 2L)));
        };
        return operations;
    }

    private ManagedCoopCaptureCoordinator.CaptureAttempt attempt(UUID sourceUuid, String[] toolIds) {
        String snapshot = "{\"version\":\"1\",\"npcUuid\":\"" + sourceUuid
                + "\",\"coopId\":\"coop_chicken\",\"residentSlot\":0,"
                + "\"roleId\":\"mob_chicken\",\"capturedAtMs\":100}";
        return new ManagedCoopCaptureCoordinator.CaptureAttempt(
                AUTHORITY,
                "Coop_Chicken",
                0,
                sourceUuid,
                "Mob_Chicken",
                null,
                "Chicken",
                toolIds,
                snapshot,
                ManagedCoopCaptureClaimValidator.snapshotSha256(snapshot),
                1,
                0L,
                100L
        );
    }

    private static MutationResult applied(OperationRecord operation) {
        return new MutationResult(MutationStatus.APPLIED, operation, null);
    }

    private static MutationResult idempotent(OperationRecord operation) {
        return new MutationResult(MutationStatus.IDEMPOTENT, operation, null);
    }

    private static OperationRecord operation(CaptureRequest request,
                                             OperationState state,
                                             long generation) {
        boolean active = state != OperationState.COMPLETE;
        return new OperationRecord(
                request.operationId(),
                OperationKind.CAPTURE,
                request.profileId(),
                request.authorityKey(),
                request.coopId(),
                request.residentSlot(),
                request.sourceNpcUuid(),
                null,
                null,
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

    private static <T> PersistenceWriteQueue.WriteSubmission<T> rejectedSubmission(String reason) {
        return new PersistenceWriteQueue.WriteSubmission<>(
                false,
                CompletableFuture.completedFuture(new PersistenceWriteQueue.WriteOutcome<>(
                        PersistenceWriteQueue.WriteStatus.REJECTED, null, reason, null))
        );
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

    private static final class FakeOperations implements ManagedCoopCaptureCoordinator.OperationGateway {
        private final AtomicInteger claimCalls = new AtomicInteger();
        private final AtomicReference<CaptureRequest> lastRequest = new AtomicReference<>();
        private Function<CaptureRequest, PersistenceWriteQueue.WriteSubmission<MutationResult>> claimBehavior;
        private RetirementBehavior retireBehavior;

        @Override
        public PersistenceWriteQueue.WriteSubmission<MutationResult> claimCapture(CaptureRequest request) {
            claimCalls.incrementAndGet();
            lastRequest.set(request);
            return claimBehavior.apply(request);
        }

        @Override
        public PersistenceWriteQueue.WriteSubmission<MutationResult> requestSourceRetirement(
                String operationId,
                long expectedGeneration,
                long nowMs) {
            return retireBehavior.apply(operationId, expectedGeneration, nowMs);
        }
    }

    @FunctionalInterface
    private interface RetirementBehavior {
        PersistenceWriteQueue.WriteSubmission<MutationResult> apply(
                String operationId,
                long expectedGeneration,
                long nowMs);
    }
}
