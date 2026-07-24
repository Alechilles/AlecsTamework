package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseLiveBoundary;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseRequest;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Dispatches captured-artifact release to the current world and flattens its durability barrier.
 */
public final class HytaleCompanionCaptureReleaseBoundary
        implements CompanionCaptureReleaseLiveBoundary {
    private static final int HOLD_RELEASE_ATTEMPTS = 3;

    private final CompanionCaptureReleaseWorldGateway gateway;
    private final Function<String, World> worldLookup;

    public HytaleCompanionCaptureReleaseBoundary(
            @Nonnull CompanionCaptureReleaseWorldGateway gateway
    ) {
        this(gateway, worldKey -> Universe.get().getWorld(worldKey));
    }

    HytaleCompanionCaptureReleaseBoundary(
            CompanionCaptureReleaseWorldGateway gateway,
            Function<String, World> worldLookup
    ) {
        if (gateway == null || worldLookup == null) {
            throw new IllegalArgumentException(
                    "Capture release world boundary dependencies are required"
            );
        }
        this.gateway = gateway;
        this.worldLookup = worldLookup;
    }

    @Override
    @Nonnull
    public CompletionStage<LiveOperationResult> applyOrResolve(
            @Nonnull CompanionCaptureReleaseRequest request,
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

        CompletableFuture<LiveOperationResult> completion =
                new CompletableFuture<>();
        try {
            scheduled.getChunkAsync(receiptChunkIndex(request.placement()))
                    .whenComplete((chunk, failure) -> {
                        if (failure != null || chunk == null) {
                            completion.complete(retry(
                                    "receipt_chunk_unavailable",
                                    failure
                            ));
                            return;
                        }
                        dispatchLoaded(
                                scheduled,
                                chunk,
                                worldKey,
                                request,
                                operation,
                                completion
                        );
                    });
        } catch (Throwable failure) {
            completion.complete(retry("world_dispatch_failed", failure));
        }
        return completion;
    }

    @Override
    @Nonnull
    public CompletionStage<Void> releaseProjectionHold(
            @Nonnull CompanionCaptureReleaseRequest request,
            @Nonnull OperationEnvelope operation
    ) {
        World scheduled = request == null
                ? null
                : findWorld(request.targetWorldKey());
        if (scheduled == null || operation == null) {
            return CompletableFuture.completedFuture(null);
        }
        return retryOnScheduler(
                () -> releaseOnWorldThread(
                        scheduled,
                        request,
                        operation
                ),
                scheduled::execute,
                HOLD_RELEASE_ATTEMPTS
        );
    }

    private void dispatchLoaded(
            World scheduled,
            WorldChunk chunk,
            String worldKey,
            CompanionCaptureReleaseRequest request,
            OperationEnvelope operation,
            CompletableFuture<LiveOperationResult> completion
    ) {
        try {
            scheduled.execute(() -> {
                if (chunk.getWorld() != scheduled
                        || chunk.getIndex() != receiptChunkIndex(
                                request.placement()
                        )) {
                    completion.complete(retry(
                            "receipt_chunk_changed",
                            null
                    ));
                    return;
                }
                dispatchOnWorldThread(
                        scheduled,
                        worldKey,
                        request,
                        operation,
                        completion
                );
            });
        } catch (Throwable failure) {
            completion.complete(retry("world_dispatch_failed", failure));
        }
    }

    private void dispatchOnWorldThread(
            World scheduled,
            String worldKey,
            CompanionCaptureReleaseRequest request,
            OperationEnvelope operation,
            CompletableFuture<LiveOperationResult> completion
    ) {
        try {
            World current = findWorld(worldKey);
            if (current == null || current != scheduled) {
                completion.complete(retry(
                        "world_instance_changed", null
                ));
                return;
            }
            Store<EntityStore> store =
                    current.getEntityStore().getStore();
            CompletionStage<LiveOperationResult> result =
                    gateway.applyOrResolve(
                            current, store, request, operation
                    );
            flatten(result, completion);
        } catch (Throwable failure) {
            completion.complete(retry("world_gateway_failed", failure));
        }
    }

    private void flatten(
            @Nullable CompletionStage<LiveOperationResult> result,
            CompletableFuture<LiveOperationResult> completion
    ) {
        if (result == null) {
            completion.complete(retry(
                    "world_gateway_returned_null", null
            ));
            return;
        }
        result.whenComplete((resolved, failure) -> {
            if (failure != null) {
                completion.complete(retry(
                        "world_gateway_failed", failure
                ));
            } else if (resolved == null) {
                completion.complete(retry(
                        "world_gateway_returned_null", null
                ));
            } else {
                completion.complete(resolved);
            }
        });
    }

    private CompletionStage<Void> releaseOnWorldThread(
            World scheduled,
            CompanionCaptureReleaseRequest request,
            OperationEnvelope operation
    ) {
        try {
            World current = findWorld(request.targetWorldKey());
            if (current != scheduled) {
                return CompletableFuture.completedFuture(null);
            }
            Store<EntityStore> store =
                    current.getEntityStore().getStore();
            CompletionStage<Void> released =
                    gateway.releaseProjectionHold(
                            current,
                            store,
                            request,
                            operation
                    );
            if (released == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException(
                                "Capture release hold returned no result"
                        )
                );
            }
            return released;
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    static CompletionStage<Void> retryOnScheduler(
            Supplier<CompletionStage<Void>> attempt,
            Consumer<Runnable> scheduler,
            int maxAttempts
    ) {
        if (attempt == null || scheduler == null || maxAttempts <= 0) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                            "Hold release retry requires an attempt, "
                                    + "scheduler, and positive limit"
                    )
            );
        }
        CompletableFuture<Void> completion = new CompletableFuture<>();
        scheduleAttempt(
                attempt,
                scheduler,
                maxAttempts,
                completion
        );
        return completion;
    }

    private static void scheduleAttempt(
            Supplier<CompletionStage<Void>> attempt,
            Consumer<Runnable> scheduler,
            int attemptsRemaining,
            CompletableFuture<Void> completion
    ) {
        try {
            scheduler.accept(() -> runAttempt(
                    attempt,
                    scheduler,
                    attemptsRemaining,
                    completion
            ));
        } catch (Throwable failure) {
            completion.completeExceptionally(failure);
        }
    }

    private static void runAttempt(
            Supplier<CompletionStage<Void>> attempt,
            Consumer<Runnable> scheduler,
            int attemptsRemaining,
            CompletableFuture<Void> completion
    ) {
        CompletionStage<Void> stage;
        try {
            stage = attempt.get();
        } catch (Throwable failure) {
            stage = CompletableFuture.failedFuture(failure);
        }
        if (stage == null) {
            stage = CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "Hold release attempt returned no result"
                    )
            );
        }
        stage.whenComplete((ignored, failure) -> {
            if (failure == null) {
                completion.complete(null);
            } else if (attemptsRemaining > 1) {
                scheduleAttempt(
                        attempt,
                        scheduler,
                        attemptsRemaining - 1,
                        completion
                );
            } else {
                completion.completeExceptionally(failure);
            }
        });
    }

    static long receiptChunkIndex(
            com.alechilles.alecstamework.companion.placement
                    .CompanionSpawnPlacement placement
    ) {
        int chunkX = ChunkUtil.chunkCoordinate(placement.x());
        int chunkZ = ChunkUtil.chunkCoordinate(placement.z());
        return ChunkUtil.indexChunk(chunkX, chunkZ);
    }

    private LiveOperationResult retry(
            String suffix,
            @Nullable Throwable failure
    ) {
        return LiveOperationResult.retryable(
                "capture_release_" + suffix, failure
        );
    }

    private CompletionStage<LiveOperationResult> completed(
            LiveOperationResult result
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
}
