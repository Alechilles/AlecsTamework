package com.alechilles.alecstamework.companion.command.timed.runtime;

import com.alechilles.alecstamework.companion.command.timed
        .TimedSummonLiveBoundary;
import com.alechilles.alecstamework.companion.command.timed
        .TimedSummonTransitionRequest;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.runtime
        .HytaleAsyncWorldOperationGateway;
import com.alechilles.alecstamework.persistence.runtime
        .HytaleWorldOperationDispatcher;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Loads frozen START chunks and enters the shared current-world dispatcher.
 *
 * <p>STORE never guesses a source chunk from mutable state. If its exact alias is not loaded, the
 * world gateway returns retryable or unknown evidence and ordinary operation recovery retries the
 * same durable request.</p>
 */
public final class HytaleTimedSummonBoundary
        implements TimedSummonLiveBoundary {
    private final WorldDispatch dispatcher;
    private final HytaleAsyncWorldOperationGateway<
            TimedSummonTransitionRequest> liveGateway;
    private final HytaleAsyncWorldOperationGateway<
            TimedSummonTransitionRequest> cleanupGateway;
    private final StartChunkLoader startChunks;

    public HytaleTimedSummonBoundary(
            @Nonnull HytaleTimedSummonWorldGateway gateway
    ) {
        this(
                liveGateway(gateway),
                cleanupGateway(gateway),
                dispatch(new HytaleWorldOperationDispatcher()),
                request -> loadFrozenChunk(
                        request,
                        worldKey -> Universe.get().getWorld(worldKey)
                )
        );
    }

    HytaleTimedSummonBoundary(
            HytaleAsyncWorldOperationGateway<
                    TimedSummonTransitionRequest> liveGateway,
            HytaleAsyncWorldOperationGateway<
                    TimedSummonTransitionRequest> cleanupGateway,
            WorldDispatch dispatcher,
            StartChunkLoader startChunks
    ) {
        if (liveGateway == null || cleanupGateway == null
                || dispatcher == null || startChunks == null) {
            throw new IllegalArgumentException(
                    "Timed summon world dependencies are required"
            );
        }
        this.liveGateway = liveGateway;
        this.cleanupGateway = cleanupGateway;
        this.dispatcher = dispatcher;
        this.startChunks = startChunks;
    }

    @Override
    @Nonnull
    public CompletionStage<LiveOperationResult> applyOrResolve(
            @Nonnull TimedSummonTransitionRequest request,
            @Nonnull OperationEnvelope operation
    ) {
        return dispatch(request, operation, false);
    }

    @Override
    @Nonnull
    public CompletionStage<LiveOperationResult> cleanupAfterDurable(
            @Nonnull TimedSummonTransitionRequest request,
            @Nonnull OperationEnvelope durableOperation
    ) {
        return dispatch(request, durableOperation, true);
    }

    private CompletionStage<LiveOperationResult> dispatch(
            TimedSummonTransitionRequest request,
            OperationEnvelope operation,
            boolean cleanup
    ) {
        if (request == null || operation == null) {
            return retry("world_request_invalid", null).completed();
        }
        return request.starting()
                ? loadStart(request).thenCompose(result ->
                result.loaded()
                        ? dispatchLoaded(request, operation, cleanup)
                        : retry(
                                "start_chunk_unavailable",
                                result.failure()
                        ).completed()
        )
                : dispatchLoaded(request, operation, cleanup);
    }

    private CompletionStage<ChunkLoad> loadStart(
            TimedSummonTransitionRequest request
    ) {
        try {
            CompletionStage<ChunkLoad> loaded = startChunks.load(request);
            return loaded == null
                    ? CompletableFuture.completedFuture(
                            ChunkLoad.failed(null)
                    )
                    : loaded;
        } catch (Throwable failure) {
            return CompletableFuture.completedFuture(
                    ChunkLoad.failed(failure)
            );
        }
    }

    private CompletionStage<LiveOperationResult> dispatchLoaded(
            TimedSummonTransitionRequest request,
            OperationEnvelope operation,
            boolean cleanup
    ) {
        return dispatcher.apply(
                cleanup
                        ? "timed_summon_cleanup"
                        : "timed_summon",
                request.worldKey(),
                request,
                operation,
                cleanup ? cleanupGateway : liveGateway
        );
    }

    private static CompletionStage<ChunkLoad> loadFrozenChunk(
            TimedSummonTransitionRequest request,
            Function<String, World> worldLookup
    ) {
        World scheduled = findWorld(
                request.worldKey(), worldLookup
        );
        if (scheduled == null || request.spawnPlacement() == null) {
            return CompletableFuture.completedFuture(
                    ChunkLoad.failed(null)
            );
        }
        long expected = chunkIndex(request);
        try {
            CompletionStage<WorldChunk> loaded =
                    scheduled.getChunkAsync(expected);
            if (loaded == null) {
                return CompletableFuture.completedFuture(
                        ChunkLoad.failed(null)
                );
            }
            return loaded.handle((chunk, failure) ->
                    failure != null
                            || chunk == null
                            || chunk.getWorld() != scheduled
                            || chunk.getIndex() != expected
                            ? ChunkLoad.failed(failure)
                            : ChunkLoad.success()
            );
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(
                    ChunkLoad.failed(failure)
            );
        }
    }

    private static long chunkIndex(
            TimedSummonTransitionRequest request
    ) {
        int x = ChunkUtil.chunkCoordinate(request.spawnPlacement().x());
        int z = ChunkUtil.chunkCoordinate(request.spawnPlacement().z());
        return ChunkUtil.indexChunk(x, z);
    }

    @Nullable
    private static World findWorld(
            String worldKey,
            Function<String, World> worldLookup
    ) {
        try {
            return worldLookup.apply(worldKey);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private LiveOperationResult retry(
            String suffix,
            @Nullable Throwable failure
    ) {
        return LiveOperationResult.retryable(
                "timed_summon_" + suffix, failure
        );
    }

    @FunctionalInterface
    interface WorldDispatch {
        CompletionStage<LiveOperationResult> apply(
                String operationCode,
                String worldKey,
                TimedSummonTransitionRequest request,
                OperationEnvelope operation,
                HytaleAsyncWorldOperationGateway<
                        TimedSummonTransitionRequest> gateway
        );
    }

    private static WorldDispatch dispatch(
            HytaleWorldOperationDispatcher dispatcher
    ) {
        return dispatcher::applyOrResolveAsync;
    }

    private static HytaleAsyncWorldOperationGateway<
            TimedSummonTransitionRequest> liveGateway(
            HytaleTimedSummonWorldGateway gateway
    ) {
        if (gateway == null) {
            throw new IllegalArgumentException(
                    "Timed summon world gateway is required"
            );
        }
        return gateway::applyOrResolveAsync;
    }

    private static HytaleAsyncWorldOperationGateway<
            TimedSummonTransitionRequest> cleanupGateway(
            HytaleTimedSummonWorldGateway gateway
    ) {
        if (gateway == null) {
            throw new IllegalArgumentException(
                    "Timed summon world gateway is required"
            );
        }
        return gateway::cleanupAfterDurable;
    }

    @FunctionalInterface
    interface StartChunkLoader {
        CompletionStage<ChunkLoad> load(
                TimedSummonTransitionRequest request
        );
    }

    record ChunkLoad(
            boolean loaded,
            @Nullable Throwable failure
    ) {
        static ChunkLoad success() {
            return new ChunkLoad(true, null);
        }

        static ChunkLoad failed(@Nullable Throwable failure) {
            return new ChunkLoad(false, failure);
        }
    }
}
