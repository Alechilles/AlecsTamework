package com.alechilles.alecstamework.companion.revival.runtime;

import com.alechilles.alecstamework.companion.revival.PaidRevivalLiveBoundary;
import com.alechilles.alecstamework.companion.revival.PaidRevivalLiveResult;
import com.alechilles.alecstamework.companion.revival.PaidRevivalRequest;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Loads the frozen target chunk and dispatches paid revival to its world.
 */
public final class HytalePaidRevivalBoundary
        implements PaidRevivalLiveBoundary {
    private final WorldGateway gateway;
    private final Function<String, World> worldLookup;

    public HytalePaidRevivalBoundary(
            @Nonnull HytalePaidRevivalWorldGateway gateway
    ) {
        this(
                gateway == null ? null : gateway::applyOrResolve,
                worldKey -> Universe.get().getWorld(worldKey)
        );
    }

    HytalePaidRevivalBoundary(
            WorldGateway gateway,
            Function<String, World> worldLookup
    ) {
        if (gateway == null || worldLookup == null) {
            throw new IllegalArgumentException(
                    "Paid revival world dependencies are required"
            );
        }
        this.gateway = gateway;
        this.worldLookup = worldLookup;
    }

    @Override
    @Nonnull
    public CompletionStage<PaidRevivalLiveResult> applyOrResolve(
            @Nonnull PaidRevivalRequest request,
            @Nonnull OperationEnvelope operation
    ) {
        if (request == null || operation == null) {
            return completed(retry("world_request_invalid", null));
        }
        String worldKey = request.targetWorldKey();
        World scheduled = findWorld(worldKey);
        if (scheduled == null) {
            return completed(retry("world_unavailable", null));
        }
        CompletableFuture<PaidRevivalLiveResult> completion =
                new CompletableFuture<>();
        try {
            scheduled.getChunkAsync(chunkIndex(request))
                    .whenComplete((chunk, failure) -> {
                        if (failure != null || chunk == null) {
                            completion.complete(retry(
                                    "target_chunk_unavailable", failure
                            ));
                        } else {
                            dispatchLoaded(
                                    scheduled,
                                    chunk,
                                    request,
                                    operation,
                                    completion
                            );
                        }
                    });
        } catch (Throwable failure) {
            completion.complete(retry(
                    "world_dispatch_failed", failure
            ));
        }
        return completion;
    }

    private void dispatchLoaded(
            World scheduled,
            WorldChunk chunk,
            PaidRevivalRequest request,
            OperationEnvelope operation,
            CompletableFuture<PaidRevivalLiveResult> completion
    ) {
        try {
            scheduled.execute(() -> dispatchOnWorldThread(
                    scheduled, chunk, request, operation, completion
            ));
        } catch (Throwable failure) {
            completion.complete(retry(
                    "world_dispatch_failed", failure
            ));
        }
    }

    private void dispatchOnWorldThread(
            World scheduled,
            WorldChunk chunk,
            PaidRevivalRequest request,
            OperationEnvelope operation,
            CompletableFuture<PaidRevivalLiveResult> completion
    ) {
        try {
            World current = findWorld(request.targetWorldKey());
            if (current != scheduled
                    || chunk.getWorld() != scheduled
                    || chunk.getIndex() != chunkIndex(request)) {
                completion.complete(retry(
                        "world_instance_changed", null
                ));
                return;
            }
            CompletionStage<PaidRevivalLiveResult> stage =
                    gateway.applyOrResolve(
                            current,
                            current.getEntityStore().getStore(),
                            request,
                            operation
                    );
            flatten(stage, completion);
        } catch (Throwable failure) {
            completion.complete(retry("world_gateway_failed", failure));
        }
    }

    private void flatten(
            @Nullable CompletionStage<PaidRevivalLiveResult> stage,
            CompletableFuture<PaidRevivalLiveResult> completion
    ) {
        if (stage == null) {
            completion.complete(retry(
                    "world_gateway_returned_null", null
            ));
            return;
        }
        stage.whenComplete((result, failure) ->
                completion.complete(failure != null
                        ? retry("world_gateway_failed", failure)
                        : result == null
                        ? retry("world_gateway_returned_null", null)
                        : result));
    }

    static long chunkIndex(PaidRevivalRequest request) {
        int chunkX = ChunkUtil.chunkCoordinate(request.placement().x());
        int chunkZ = ChunkUtil.chunkCoordinate(request.placement().z());
        return ChunkUtil.indexChunk(chunkX, chunkZ);
    }

    private PaidRevivalLiveResult retry(
            String suffix,
            @Nullable Throwable failure
    ) {
        return PaidRevivalLiveResult.retryable(
                "paid_revival_" + suffix, failure
        );
    }

    private CompletionStage<PaidRevivalLiveResult> completed(
            PaidRevivalLiveResult result
    ) {
        return CompletableFuture.completedFuture(result);
    }

    @Nullable
    private World findWorld(String worldKey) {
        try {
            return worldLookup.apply(worldKey);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @FunctionalInterface
    interface WorldGateway {
        CompletionStage<PaidRevivalLiveResult> applyOrResolve(
                World world,
                Store<EntityStore> store,
                PaidRevivalRequest request,
                OperationEnvelope operation
        );
    }
}
