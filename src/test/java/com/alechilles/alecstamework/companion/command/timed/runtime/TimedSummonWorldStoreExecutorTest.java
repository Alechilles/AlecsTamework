package com.alechilles.alecstamework.companion.command.timed.runtime;

import com.alechilles.alecstamework.companion.command.timed
        .TimedSummonTransitionRequest;
import com.alechilles.alecstamework.companion.command.timed.runtime
        .TimedSummonWorldAttempt.ChunkPersistence;
import com.alechilles.alecstamework.companion.command.timed.runtime
        .TimedSummonWorldAttempt.ReceiptProbe;
import com.alechilles.alecstamework.companion.command.timed.runtime
        .TimedSummonWorldAttempt.SourceProbe;
import com.alechilles.alecstamework.companion.command.timed.runtime
        .TimedSummonWorldAttempt.StoreProbe;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Crash-seam and post-durable exact-retirement coverage for timed STORE. */
class TimedSummonWorldStoreExecutorTest {
    private final TimedSummonWorldTestFixture fixture =
            new TimedSummonWorldTestFixture();

    @Test
    void livePhaseDurablyMarksSourceWithoutRetiringIt() throws Exception {
        TimedSummonTransitionRequest request = fixture.storeRequest();
        FakeTimedSummonWorldAttempts attempts =
                new FakeTimedSummonWorldAttempts();
        CompletableFuture<ChunkPersistence> receiptSave =
                attempts.enqueuePendingPersistence();

        CompletableFuture<LiveOperationResult> result =
                executeLiveAsync(request, attempts);

        assertFalse(result.isDone());
        assertFalse(attempts.events.contains("retire"));
        assertEquals(request.snapshot(), attempts.lastStoreAuthority.snapshot());
        assertEquals(
                request.receiptKey(),
                attempts.lastStoreAuthority.receiptKey()
        );

        receiptSave.complete(ChunkPersistence.saved(
                TimedSummonWorldTestFixture.CHUNK
        ));
        LiveOperationResult resolved = result.get(5, TimeUnit.SECONDS);

        assertEquals(LiveOperationResult.Status.CONFIRMED, resolved.status());
        assertEquals(
                "timed_summon_store_receipt_saved_exact",
                resolved.code()
        );
        assertFalse(attempts.events.contains("retire"));
        assertEquals(
                SourceProbe.exact(TimedSummonWorldTestFixture.CHUNK),
                attempts.storeProbe.source()
        );
    }

    @Test
    void crashAfterReceiptInstallCannotReachDurableBeforeReceiptSave()
            throws Exception {
        TimedSummonTransitionRequest request = fixture.storeRequest();
        FakeTimedSummonWorldAttempts attempts =
                new FakeTimedSummonWorldAttempts();
        attempts.enqueuePendingPersistence();

        CompletableFuture<LiveOperationResult> result =
                executeLiveAsync(request, attempts);

        assertFalse(result.isDone());
        assertFalse(attempts.events.contains("retire"));
        assertEquals(
                ReceiptProbe.exact(TimedSummonWorldTestFixture.CHUNK),
                attempts.storeProbe.receipt()
        );
    }

    @Test
    void liveReplayWithReceiptAndAbsentSourceIsUnknown() throws Exception {
        TimedSummonTransitionRequest request = fixture.storeRequest();
        FakeTimedSummonWorldAttempts attempts =
                new FakeTimedSummonWorldAttempts();
        attempts.storeProbe = StoreProbe.of(
                ReceiptProbe.exact(TimedSummonWorldTestFixture.CHUNK),
                SourceProbe.absent()
        );

        LiveOperationResult result = executeLive(request, attempts);

        assertEquals(LiveOperationResult.Status.UNKNOWN, result.status());
        assertEquals(
                "timed_summon_store_source_absent_before_durable",
                result.code()
        );
        assertFalse(attempts.events.contains("retire"));
    }

    @Test
    void entityAbsenceWithoutExactReceiptIsUnknownBeforeDurable()
            throws Exception {
        TimedSummonTransitionRequest request = fixture.storeRequest();
        FakeTimedSummonWorldAttempts attempts =
                new FakeTimedSummonWorldAttempts();
        attempts.storeProbe = StoreProbe.of(
                ReceiptProbe.absent(),
                SourceProbe.absent()
        );

        LiveOperationResult result = executeLive(request, attempts);

        assertEquals(LiveOperationResult.Status.UNKNOWN, result.status());
        assertEquals(
                "timed_summon_store_source_absent_without_receipt",
                result.code()
        );
        assertFalse(attempts.events.stream()
                .anyMatch(event -> event.startsWith("persist:")));
    }

    @Test
    void receiptInstallExceptionAfterApplyRecoversSameOperation()
            throws Exception {
        TimedSummonTransitionRequest request = fixture.storeRequest();
        FakeTimedSummonWorldAttempts attempts =
                new FakeTimedSummonWorldAttempts();
        attempts.throwInstallAfterApply = true;

        LiveOperationResult result = executeLive(request, attempts);

        assertEquals(LiveOperationResult.Status.CONFIRMED, result.status());
        assertEquals(request.snapshot(), attempts.lastStoreAuthority.snapshot());
        assertFalse(attempts.events.contains("retire"));
    }

    @Test
    void failedReceiptSaveIsRetryableAndLeavesSourcePresent()
            throws Exception {
        TimedSummonTransitionRequest request = fixture.storeRequest();
        FakeTimedSummonWorldAttempts attempts =
                new FakeTimedSummonWorldAttempts();
        attempts.persistence.add(CompletableFuture.completedFuture(
                ChunkPersistence.retryable(
                        new IllegalStateException("disk unavailable")
                )
        ));

        LiveOperationResult result = executeLive(request, attempts);

        assertEquals(LiveOperationResult.Status.RETRYABLE, result.status());
        assertFalse(attempts.events.contains("retire"));
        assertEquals(
                SourceProbe.exact(TimedSummonWorldTestFixture.CHUNK),
                attempts.storeProbe.source()
        );
    }

    @Test
    void exactReceiptAndSourceOnDifferentChunksAreUnknown()
            throws Exception {
        TimedSummonTransitionRequest request = fixture.storeRequest();
        FakeTimedSummonWorldAttempts attempts =
                new FakeTimedSummonWorldAttempts();
        attempts.storeProbe = StoreProbe.of(
                ReceiptProbe.exact(TimedSummonWorldTestFixture.CHUNK),
                SourceProbe.exact(TimedSummonWorldTestFixture.CHUNK + 1)
        );

        LiveOperationResult result = executeLive(request, attempts);

        assertEquals(LiveOperationResult.Status.UNKNOWN, result.status());
        assertEquals(
                "timed_summon_store_chunk_evidence_conflict",
                result.code()
        );
    }

    @Test
    void durableCleanupRetiresMarkedSourceThenForceSaves() throws Exception {
        TimedSummonTransitionRequest request = fixture.storeRequest();
        FakeTimedSummonWorldAttempts attempts =
                markedSourceAttempts();
        CompletableFuture<ChunkPersistence> retirementSave =
                attempts.enqueuePendingPersistence();

        CompletableFuture<LiveOperationResult> result =
                executeCleanupAsync(request, attempts);

        assertTrue(attempts.events.contains("retire"));
        assertFalse(result.isDone());
        retirementSave.complete(ChunkPersistence.saved(
                TimedSummonWorldTestFixture.CHUNK
        ));
        LiveOperationResult resolved = result.get(5, TimeUnit.SECONDS);

        assertEquals(LiveOperationResult.Status.CONFIRMED, resolved.status());
        assertEquals(
                "timed_summon_cleanup_store_retirement_saved_exact",
                resolved.code()
        );
        assertTrue(
                attempts.events.indexOf("retire")
                        < attempts.events.indexOf("persist:77")
        );
    }

    @Test
    void durableCleanupReplayAcceptsCanonicalStoredAbsence()
            throws Exception {
        TimedSummonTransitionRequest request = fixture.storeRequest();
        FakeTimedSummonWorldAttempts attempts =
                new FakeTimedSummonWorldAttempts();
        attempts.storeProbe = StoreProbe.of(
                ReceiptProbe.absent(),
                SourceProbe.absent()
        );

        LiveOperationResult result = executeCleanup(request, attempts);

        assertEquals(LiveOperationResult.Status.CONFIRMED, result.status());
        assertEquals(
                "timed_summon_cleanup_store_source_already_absent",
                result.code()
        );
        assertFalse(attempts.events.contains("retire"));
    }

    @Test
    void durableCleanupNeverRemovesPresentSourceWithoutExactMarker()
            throws Exception {
        TimedSummonTransitionRequest request = fixture.storeRequest();
        FakeTimedSummonWorldAttempts attempts =
                new FakeTimedSummonWorldAttempts();

        LiveOperationResult result = executeCleanup(request, attempts);

        assertEquals(LiveOperationResult.Status.UNKNOWN, result.status());
        assertEquals(
                "timed_summon_cleanup_store_exact_receipt_missing",
                result.code()
        );
        assertFalse(attempts.events.contains("retire"));
    }

    @Test
    void cleanupRetirementExceptionAfterApplyStillRequiresChunkSave()
            throws Exception {
        TimedSummonTransitionRequest request = fixture.storeRequest();
        FakeTimedSummonWorldAttempts attempts = markedSourceAttempts();
        attempts.throwRetireAfterApply = true;

        LiveOperationResult result = executeCleanup(request, attempts);

        assertEquals(LiveOperationResult.Status.CONFIRMED, result.status());
        assertTrue(attempts.events.contains("persist:77"));
    }

    @Test
    void sourceStillPresentAfterCleanupSaveIsRetryable() throws Exception {
        TimedSummonTransitionRequest request = fixture.storeRequest();
        FakeTimedSummonWorldAttempts attempts = markedSourceAttempts();
        attempts.suppressRetireApply = true;

        LiveOperationResult result = executeCleanup(request, attempts);

        assertEquals(LiveOperationResult.Status.RETRYABLE, result.status());
        assertEquals(
                "timed_summon_cleanup_store_source_still_present_after_save",
                result.code()
        );
    }

    @Test
    void cleanupCannotRunBeforeDurablePhase() throws Exception {
        TimedSummonTransitionRequest request = fixture.storeRequest();
        FakeTimedSummonWorldAttempts attempts = markedSourceAttempts();

        LiveOperationResult result =
                new TimedSummonDurableCleanupExecutor().execute(
                        request,
                        fixture.operation(request, true),
                        attempts
                ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(LiveOperationResult.Status.UNKNOWN, result.status());
        assertEquals(
                "timed_summon_cleanup_operation_invariant_mismatch",
                result.code()
        );
        assertFalse(attempts.events.contains("retire"));
    }

    private FakeTimedSummonWorldAttempts markedSourceAttempts() {
        FakeTimedSummonWorldAttempts attempts =
                new FakeTimedSummonWorldAttempts();
        attempts.storeProbe = StoreProbe.of(
                ReceiptProbe.exact(TimedSummonWorldTestFixture.CHUNK),
                SourceProbe.exact(TimedSummonWorldTestFixture.CHUNK)
        );
        return attempts;
    }

    private CompletableFuture<LiveOperationResult> executeLiveAsync(
            TimedSummonTransitionRequest request,
            FakeTimedSummonWorldAttempts attempts
    ) {
        return new TimedSummonWorldExecutor().execute(
                request,
                fixture.operation(request, true),
                attempts
        ).toCompletableFuture();
    }

    private LiveOperationResult executeLive(
            TimedSummonTransitionRequest request,
            FakeTimedSummonWorldAttempts attempts
    ) throws Exception {
        return executeLiveAsync(request, attempts).get(5, TimeUnit.SECONDS);
    }

    private CompletableFuture<LiveOperationResult> executeCleanupAsync(
            TimedSummonTransitionRequest request,
            FakeTimedSummonWorldAttempts attempts
    ) {
        return new TimedSummonDurableCleanupExecutor().execute(
                request,
                fixture.durableOperation(request),
                attempts
        ).toCompletableFuture();
    }

    private LiveOperationResult executeCleanup(
            TimedSummonTransitionRequest request,
            FakeTimedSummonWorldAttempts attempts
    ) throws Exception {
        return executeCleanupAsync(request, attempts)
                .get(5, TimeUnit.SECONDS);
    }
}
