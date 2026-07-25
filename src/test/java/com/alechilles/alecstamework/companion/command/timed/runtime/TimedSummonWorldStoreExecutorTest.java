package com.alechilles.alecstamework.companion.command.timed.runtime;

import com.alechilles.alecstamework.companion.command.timed.TimedSummonTransitionRequest;
import com.alechilles.alecstamework.companion.command.timed.runtime.TimedSummonWorldAttempt.ChunkPersistence;
import com.alechilles.alecstamework.companion.command.timed.runtime.TimedSummonWorldAttempt.ReceiptProbe;
import com.alechilles.alecstamework.companion.command.timed.runtime.TimedSummonWorldAttempt.SourceProbe;
import com.alechilles.alecstamework.companion.command.timed.runtime.TimedSummonWorldAttempt.StoreProbe;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Crash-seam and exact-retirement coverage for timed summon STORE. */
class TimedSummonWorldStoreExecutorTest {
    private final TimedSummonWorldTestFixture fixture =
            new TimedSummonWorldTestFixture();

    @Test
    void receiptAndRetirementEachBecomeDurableBeforeConfirmation()
            throws Exception {
        TimedSummonTransitionRequest request = fixture.storeRequest();
        FakeTimedSummonWorldAttempts attempts =
                new FakeTimedSummonWorldAttempts();
        CompletableFuture<ChunkPersistence> receiptSave =
                attempts.enqueuePendingPersistence();
        CompletableFuture<ChunkPersistence> retirementSave =
                attempts.enqueuePendingPersistence();

        CompletableFuture<LiveOperationResult> result =
                new TimedSummonWorldExecutor().execute(
                        request,
                        fixture.operation(request, true),
                        attempts
                ).toCompletableFuture();

        assertFalse(result.isDone());
        assertFalse(attempts.events.contains("retire"));
        assertEquals(
                request.snapshot(),
                attempts.lastStoreAuthority.snapshot()
        );
        assertEquals(
                request.receiptKey(),
                attempts.lastStoreAuthority.receiptKey()
        );

        receiptSave.complete(ChunkPersistence.saved(
                TimedSummonWorldTestFixture.CHUNK
        ));
        assertFalse(result.isDone());
        assertTrue(attempts.events.contains("retire"));
        assertEquals(
                2,
                attempts.events.stream()
                        .filter(event -> event.startsWith("persist:"))
                        .count()
        );

        retirementSave.complete(ChunkPersistence.saved(
                TimedSummonWorldTestFixture.CHUNK
        ));
        LiveOperationResult resolved = result.get(5, TimeUnit.SECONDS);

        assertEquals(LiveOperationResult.Status.CONFIRMED, resolved.status());
        assertEquals(
                "timed_summon_store_receipt_and_retirement_saved_exact",
                resolved.code()
        );
        assertTrue(
                attempts.events.indexOf("persist:77")
                        < attempts.events.indexOf("retire")
        );
        assertTrue(
                attempts.events.indexOf("retire")
                        < attempts.events.lastIndexOf("persist:77")
        );
    }

    @Test
    void crashAfterReceiptInstallCannotRetireBeforeReceiptSave()
            throws Exception {
        TimedSummonTransitionRequest request = fixture.storeRequest();
        FakeTimedSummonWorldAttempts attempts =
                new FakeTimedSummonWorldAttempts();
        attempts.enqueuePendingPersistence();

        CompletableFuture<LiveOperationResult> result =
                new TimedSummonWorldExecutor().execute(
                        request,
                        fixture.operation(request, true),
                        attempts
                ).toCompletableFuture();

        assertFalse(result.isDone());
        assertFalse(attempts.events.contains("retire"));
        assertEquals(
                ReceiptProbe.exact(TimedSummonWorldTestFixture.CHUNK),
                attempts.storeProbe.receipt()
        );
    }

    @Test
    void replayWithReceiptAndAbsentSourceStillSavesRetirement()
            throws Exception {
        TimedSummonTransitionRequest request = fixture.storeRequest();
        FakeTimedSummonWorldAttempts attempts =
                new FakeTimedSummonWorldAttempts();
        attempts.storeProbe = StoreProbe.of(
                ReceiptProbe.exact(TimedSummonWorldTestFixture.CHUNK),
                SourceProbe.absent()
        );

        LiveOperationResult result = execute(request, attempts);

        assertEquals(LiveOperationResult.Status.CONFIRMED, result.status());
        assertFalse(attempts.events.contains("install-receipt"));
        assertFalse(attempts.events.contains("retire"));
        assertTrue(attempts.events.contains("persist:77"));
    }

    @Test
    void entityAbsenceWithoutExactReceiptIsAlwaysUnknown()
            throws Exception {
        TimedSummonTransitionRequest request = fixture.storeRequest();
        FakeTimedSummonWorldAttempts attempts =
                new FakeTimedSummonWorldAttempts();
        attempts.storeProbe = StoreProbe.of(
                ReceiptProbe.absent(),
                SourceProbe.absent()
        );

        LiveOperationResult result = execute(request, attempts);

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

        LiveOperationResult result = execute(request, attempts);

        assertEquals(LiveOperationResult.Status.CONFIRMED, result.status());
        assertEquals(
                request.snapshot(),
                attempts.lastStoreAuthority.snapshot()
        );
    }

    @Test
    void retirementExceptionAfterApplyRequiresSaveAndReadback()
            throws Exception {
        TimedSummonTransitionRequest request = fixture.storeRequest();
        FakeTimedSummonWorldAttempts attempts =
                new FakeTimedSummonWorldAttempts();
        attempts.throwRetireAfterApply = true;

        LiveOperationResult result = execute(request, attempts);

        assertEquals(LiveOperationResult.Status.CONFIRMED, result.status());
        assertEquals(
                2,
                attempts.events.stream()
                        .filter(event -> event.startsWith("persist:"))
                        .count()
        );
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

        LiveOperationResult result = execute(request, attempts);

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
                SourceProbe.exact(
                        TimedSummonWorldTestFixture.CHUNK + 1
                )
        );

        LiveOperationResult result = execute(request, attempts);

        assertEquals(LiveOperationResult.Status.UNKNOWN, result.status());
        assertEquals(
                "timed_summon_store_chunk_evidence_conflict",
                result.code()
        );
    }

    @Test
    void sourceStillPresentAfterRetirementSaveIsRetryable()
            throws Exception {
        TimedSummonTransitionRequest request = fixture.storeRequest();
        FakeTimedSummonWorldAttempts attempts =
                new FakeTimedSummonWorldAttempts();
        attempts.suppressRetireApply = true;

        LiveOperationResult result = execute(request, attempts);

        assertEquals(LiveOperationResult.Status.RETRYABLE, result.status());
        assertEquals(
                "timed_summon_store_source_still_present_after_save",
                result.code()
        );
    }

    @Test
    void losingReceiptAfterRetirementNeverUsesAbsenceAsSuccess()
            throws Exception {
        TimedSummonTransitionRequest request = fixture.storeRequest();
        FakeTimedSummonWorldAttempts attempts =
                new FakeTimedSummonWorldAttempts();
        attempts.persistence.add(CompletableFuture.completedFuture(
                ChunkPersistence.saved(TimedSummonWorldTestFixture.CHUNK)
        ));
        CompletableFuture<ChunkPersistence> retirementSave =
                attempts.enqueuePendingPersistence();
        CompletableFuture<LiveOperationResult> result =
                new TimedSummonWorldExecutor().execute(
                        request,
                        fixture.operation(request, true),
                        attempts
                ).toCompletableFuture();

        attempts.storeProbe = StoreProbe.of(
                ReceiptProbe.absent(),
                SourceProbe.absent()
        );
        retirementSave.complete(ChunkPersistence.saved(
                TimedSummonWorldTestFixture.CHUNK
        ));
        LiveOperationResult resolved = result.get(5, TimeUnit.SECONDS);

        assertEquals(LiveOperationResult.Status.UNKNOWN, resolved.status());
        assertEquals(
                "timed_summon_store_receipt_readback_conflict",
                resolved.code()
        );
    }

    private LiveOperationResult execute(
            TimedSummonTransitionRequest request,
            FakeTimedSummonWorldAttempts attempts
    ) throws Exception {
        return new TimedSummonWorldExecutor().execute(
                request,
                fixture.operation(request, true),
                attempts
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
}
