package com.alechilles.alecstamework.companion.command.timed.runtime;

import com.alechilles.alecstamework.companion.command.timed.TimedSummonTransitionRequest;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Frozen chunk loading and live-versus-cleanup routing tests. */
class HytaleTimedSummonBoundaryTest {
    private final TimedSummonWorldTestFixture fixture =
            new TimedSummonWorldTestFixture();

    @Test
    void startWaitsForFrozenChunkBeforeEnteringWorldGateway()
            throws Exception {
        CompletableFuture<HytaleTimedSummonBoundary.ChunkLoad> loaded =
                new CompletableFuture<>();
        AtomicInteger liveCalls = new AtomicInteger();
        HytaleTimedSummonBoundary boundary = boundary(
                liveCalls,
                new AtomicInteger(),
                ignored -> loaded
        );
        TimedSummonTransitionRequest request = fixture.startRequest();

        CompletableFuture<LiveOperationResult> result =
                boundary.applyOrResolve(
                        request, fixture.operation(request, true)
                ).toCompletableFuture();

        assertFalse(result.isDone());
        assertEquals(0, liveCalls.get());
        loaded.complete(
                HytaleTimedSummonBoundary.ChunkLoad.success()
        );
        assertEquals(
                LiveOperationResult.Status.CONFIRMED,
                result.get(5, TimeUnit.SECONDS).status()
        );
        assertEquals(1, liveCalls.get());
    }

    @Test
    void storeNeverLoadsOrGuessesAChunk() throws Exception {
        AtomicInteger liveCalls = new AtomicInteger();
        AtomicInteger loads = new AtomicInteger();
        HytaleTimedSummonBoundary boundary = boundary(
                liveCalls,
                new AtomicInteger(),
                ignored -> {
                    loads.incrementAndGet();
                    throw new AssertionError(
                            "STORE must not guess a source chunk"
                    );
                }
        );
        TimedSummonTransitionRequest request = fixture.storeRequest();

        LiveOperationResult result = boundary.applyOrResolve(
                request, fixture.operation(request, true)
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(LiveOperationResult.Status.CONFIRMED, result.status());
        assertEquals(1, liveCalls.get());
        assertEquals(0, loads.get());
    }

    @Test
    void startChunkLoaderFailureIsRetryableWithoutWorldAccess()
            throws Exception {
        AtomicInteger liveCalls = new AtomicInteger();
        HytaleTimedSummonBoundary boundary = boundary(
                liveCalls,
                new AtomicInteger(),
                ignored -> {
                    throw new IllegalStateException("chunk load failed");
                }
        );
        TimedSummonTransitionRequest request = fixture.startRequest();

        LiveOperationResult result = boundary.applyOrResolve(
                request, fixture.operation(request, true)
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(LiveOperationResult.Status.RETRYABLE, result.status());
        assertEquals(
                "timed_summon_start_chunk_unavailable",
                result.code()
        );
        assertEquals(0, liveCalls.get());
    }

    @Test
    void durableCleanupUsesCleanupGateway() throws Exception {
        AtomicInteger liveCalls = new AtomicInteger();
        AtomicInteger cleanupCalls = new AtomicInteger();
        HytaleTimedSummonBoundary boundary = boundary(
                liveCalls,
                cleanupCalls,
                ignored -> CompletableFuture.completedFuture(
                        HytaleTimedSummonBoundary.ChunkLoad.success()
                )
        );
        TimedSummonTransitionRequest request = fixture.storeRequest();

        LiveOperationResult result = boundary.cleanupAfterDurable(
                request, fixture.durableOperation(request)
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(LiveOperationResult.Status.CONFIRMED, result.status());
        assertEquals(0, liveCalls.get());
        assertEquals(1, cleanupCalls.get());
    }

    private HytaleTimedSummonBoundary boundary(
            AtomicInteger liveCalls,
            AtomicInteger cleanupCalls,
            HytaleTimedSummonBoundary.StartChunkLoader loader
    ) {
        return new HytaleTimedSummonBoundary(
                (world, store, request, operation) -> {
                    liveCalls.incrementAndGet();
                    return confirmed();
                },
                (world, store, request, operation) -> {
                    cleanupCalls.incrementAndGet();
                    return confirmed();
                },
                (code, worldKey, request, operation, gateway) ->
                        gateway.applyOrResolveAsync(
                                null, null, request, operation
                        ),
                loader
        );
    }

    private CompletionStage<LiveOperationResult> confirmed() {
        return LiveOperationResult.confirmed(
                "timed_summon_boundary_test"
        ).completed();
    }
}
