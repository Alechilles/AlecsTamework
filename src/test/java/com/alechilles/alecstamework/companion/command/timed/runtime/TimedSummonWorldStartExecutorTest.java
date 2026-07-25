package com.alechilles.alecstamework.companion.command.timed.runtime;

import com.alechilles.alecstamework.companion.command.timed.TimedSummonTransitionRequest;
import com.alechilles.alecstamework.companion.command.timed.runtime.TimedSummonWorldAttempt.ChunkPersistence;
import com.alechilles.alecstamework.companion.command.timed.runtime.TimedSummonWorldAttempt.ProjectionProbe;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Crash-seam and exact-readback coverage for timed summon START. */
class TimedSummonWorldStartExecutorTest {
    private final TimedSummonWorldTestFixture fixture =
            new TimedSummonWorldTestFixture();

    @Test
    void spawnUsesFrozenAuthorityAndConfirmsOnlyAfterChunkReadback()
            throws Exception {
        TimedSummonTransitionRequest request = fixture.startRequest();
        FakeTimedSummonWorldAttempts attempts =
                new FakeTimedSummonWorldAttempts();
        CompletableFuture<ChunkPersistence> persistence =
                attempts.enqueuePendingPersistence();

        CompletableFuture<LiveOperationResult> result =
                new TimedSummonWorldExecutor().execute(
                        request,
                        fixture.operation(request, true),
                        attempts
                ).toCompletableFuture();

        assertFalse(result.isDone());
        assertEquals(
                List.of(
                        "probe-start",
                        "spawn",
                        "persist:" + TimedSummonWorldTestFixture.CHUNK
                ),
                attempts.events
        );
        assertEquals(
                request.snapshot(),
                attempts.lastStartAuthority.snapshot()
        );
        assertEquals(
                request.spawnPlacement(),
                attempts.lastStartAuthority.placement()
        );
        assertEquals(
                TimedSummonWorldTestFixture.OPERATION,
                attempts.lastStartAuthority.operationId()
        );

        persistence.complete(ChunkPersistence.saved(
                TimedSummonWorldTestFixture.CHUNK
        ));
        LiveOperationResult resolved = result.get(5, TimeUnit.SECONDS);

        assertEquals(LiveOperationResult.Status.CONFIRMED, resolved.status());
        assertEquals(
                "timed_summon_start_projection_saved_exact",
                resolved.code()
        );
        assertTrue(
                attempts.events.indexOf("resume")
                        < attempts.events.lastIndexOf("probe-start")
        );
    }

    @Test
    void replayOfExactProjectionStillForceSavesBeforeConfirmation()
            throws Exception {
        TimedSummonTransitionRequest request = fixture.startRequest();
        FakeTimedSummonWorldAttempts attempts =
                new FakeTimedSummonWorldAttempts();
        attempts.startProbe = ProjectionProbe.exact(
                TimedSummonWorldTestFixture.CHUNK
        );

        LiveOperationResult result = execute(request, attempts);

        assertEquals(LiveOperationResult.Status.CONFIRMED, result.status());
        assertFalse(attempts.events.contains("spawn"));
        assertTrue(attempts.events.contains(
                "persist:" + TimedSummonWorldTestFixture.CHUNK
        ));
    }

    @Test
    void spawnExceptionAfterApplyResolvesExactProjection()
            throws Exception {
        TimedSummonTransitionRequest request = fixture.startRequest();
        FakeTimedSummonWorldAttempts attempts =
                new FakeTimedSummonWorldAttempts();
        attempts.throwSpawnAfterApply = true;

        LiveOperationResult result = execute(request, attempts);

        assertEquals(LiveOperationResult.Status.CONFIRMED, result.status());
        assertEquals(3, attempts.events.stream()
                .filter("probe-start"::equals).count());
    }

    @Test
    void failedChunkSaveIsRetryableAndNeverConfirms() throws Exception {
        TimedSummonTransitionRequest request = fixture.startRequest();
        FakeTimedSummonWorldAttempts attempts =
                new FakeTimedSummonWorldAttempts();
        attempts.persistence.add(CompletableFuture.completedFuture(
                ChunkPersistence.retryable(
                        new IllegalStateException("disk unavailable")
                )
        ));

        LiveOperationResult result = execute(request, attempts);

        assertEquals(LiveOperationResult.Status.RETRYABLE, result.status());
        assertFalse(attempts.events.contains("resume"));
    }

    @Test
    void projectionAbsenceAfterSuccessfulSaveIsUnknown() throws Exception {
        TimedSummonTransitionRequest request = fixture.startRequest();
        FakeTimedSummonWorldAttempts attempts =
                new FakeTimedSummonWorldAttempts();
        CompletableFuture<ChunkPersistence> persistence =
                attempts.enqueuePendingPersistence();
        CompletableFuture<LiveOperationResult> result =
                new TimedSummonWorldExecutor().execute(
                        request,
                        fixture.operation(request, true),
                        attempts
                ).toCompletableFuture();

        attempts.startProbe = ProjectionProbe.absent();
        persistence.complete(ChunkPersistence.saved(
                TimedSummonWorldTestFixture.CHUNK
        ));
        LiveOperationResult resolved = result.get(5, TimeUnit.SECONDS);

        assertEquals(LiveOperationResult.Status.UNKNOWN, resolved.status());
        assertEquals(
                "timed_summon_start_projection_absent_after_save",
                resolved.code()
        );
    }

    @Test
    void mismatchedSavedChunkIsUnknown() throws Exception {
        TimedSummonTransitionRequest request = fixture.startRequest();
        FakeTimedSummonWorldAttempts attempts =
                new FakeTimedSummonWorldAttempts();
        attempts.persistence.add(CompletableFuture.completedFuture(
                ChunkPersistence.saved(
                        TimedSummonWorldTestFixture.CHUNK + 1
                )
        ));

        LiveOperationResult result = execute(request, attempts);

        assertEquals(LiveOperationResult.Status.UNKNOWN, result.status());
        assertEquals(
                "timed_summon_start_projection_save_conflict",
                result.code()
        );
    }

    @Test
    void incompleteOperationScopesFailBeforeWorldAccess()
            throws Exception {
        TimedSummonTransitionRequest request = fixture.startRequest();
        FakeTimedSummonWorldAttempts attempts =
                new FakeTimedSummonWorldAttempts();

        LiveOperationResult result =
                new TimedSummonWorldExecutor().execute(
                        request,
                        fixture.operation(request, false),
                        attempts
                ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(LiveOperationResult.Status.UNKNOWN, result.status());
        assertTrue(attempts.events.isEmpty());
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
