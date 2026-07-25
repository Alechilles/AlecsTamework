package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Re-resolves current world and ECS state for all replacement live-operation boundaries.
 *
 * <p>Only immutable requests and operation envelopes cross the persistence boundary. The current
 * store is resolved inside {@link World#execute(Runnable)}, and replacing the named world before
 * execution yields a retry instead of allowing stale ECS access.</p>
 */
public final class HytaleWorldOperationDispatcher {
    private final Function<String, World> worldLookup;

    public HytaleWorldOperationDispatcher() {
        this(worldKey -> Universe.get().getWorld(worldKey));
    }

    public HytaleWorldOperationDispatcher(
            @Nonnull Function<String, World> worldLookup
    ) {
        if (worldLookup == null) {
            throw new IllegalArgumentException("World lookup is required");
        }
        this.worldLookup = worldLookup;
    }

    /** Dispatches one typed request and normalizes every world-boundary failure as retryable. */
    @Nonnull
    public <R> CompletionStage<LiveOperationResult> applyOrResolve(
            @Nonnull String operationCode,
            @Nonnull String worldKey,
            @Nonnull R request,
            @Nonnull OperationEnvelope operation,
            @Nonnull HytaleWorldOperationGateway<R> gateway
    ) {
        if (operationCode == null || operationCode.isBlank()
                || worldKey == null || worldKey.isBlank()
                || request == null || operation == null || gateway == null) {
            return retry(operationCode, "world_request_invalid", null)
                    .completed();
        }
        String code = operationCode.trim();
        String targetWorld = worldKey.trim();
        World scheduled = findWorld(targetWorld);
        if (scheduled == null) {
            return retry(code, "world_unavailable", null).completed();
        }
        CompletableFuture<LiveOperationResult> completion =
                new CompletableFuture<>();
        try {
            scheduled.execute(() -> resolveOnWorldThread(
                    scheduled,
                    code,
                    targetWorld,
                    request,
                    operation,
                    gateway,
                    completion
            ));
        } catch (Throwable failure) {
            completion.complete(retry(
                    code, "world_dispatch_failed", failure
            ));
        }
        return completion;
    }

    /** Dispatches an asynchronous durability boundary without ECS work in its callbacks. */
    @Nonnull
    public <R> CompletionStage<LiveOperationResult> applyOrResolveAsync(
            @Nonnull String operationCode,
            @Nonnull String worldKey,
            @Nonnull R request,
            @Nonnull OperationEnvelope operation,
            @Nonnull HytaleAsyncWorldOperationGateway<R> gateway
    ) {
        if (gateway == null) {
            return retry(
                    operationCode, "world_request_invalid", null
            ).completed();
        }
        return dispatchAsync(
                operationCode,
                worldKey,
                request,
                operation,
                gateway
        );
    }

    private <R> CompletionStage<LiveOperationResult> dispatchAsync(
            String operationCode,
            String worldKey,
            R request,
            OperationEnvelope operation,
            HytaleAsyncWorldOperationGateway<R> gateway
    ) {
        if (operationCode == null || operationCode.isBlank()
                || worldKey == null || worldKey.isBlank()
                || request == null || operation == null) {
            return retry(operationCode, "world_request_invalid", null)
                    .completed();
        }
        String code = operationCode.trim();
        String targetWorld = worldKey.trim();
        World scheduled = findWorld(targetWorld);
        if (scheduled == null) {
            return retry(code, "world_unavailable", null).completed();
        }
        CompletableFuture<LiveOperationResult> completion =
                new CompletableFuture<>();
        try {
            scheduled.execute(() -> resolveAsyncOnWorldThread(
                    scheduled,
                    code,
                    targetWorld,
                    request,
                    operation,
                    gateway,
                    completion
            ));
        } catch (Throwable failure) {
            completion.complete(retry(
                    code, "world_dispatch_failed", failure
            ));
        }
        return completion;
    }

    private <R> void resolveAsyncOnWorldThread(
            World scheduled,
            String operationCode,
            String worldKey,
            R request,
            OperationEnvelope operation,
            HytaleAsyncWorldOperationGateway<R> gateway,
            CompletableFuture<LiveOperationResult> completion
    ) {
        try {
            World current = findWorld(worldKey);
            if (current == null || current != scheduled) {
                completion.complete(retry(
                        operationCode, "world_instance_changed", null
                ));
                return;
            }
            Store<EntityStore> store =
                    current.getEntityStore().getStore();
            CompletionStage<LiveOperationResult> stage =
                    gateway.applyOrResolveAsync(
                            current, store, request, operation
                    );
            if (stage == null) {
                completion.complete(retry(
                        operationCode,
                        "world_gateway_returned_null",
                        null
                ));
                return;
            }
            stage.whenComplete((result, failure) -> {
                if (failure != null) {
                    completion.complete(retry(
                            operationCode,
                            "world_gateway_failed",
                            failure
                    ));
                } else {
                    completion.complete(result == null
                            ? retry(
                            operationCode,
                            "world_gateway_returned_null",
                            null
                    )
                            : result);
                }
            });
        } catch (Throwable failure) {
            completion.complete(retry(
                    operationCode, "world_gateway_failed", failure
            ));
        }
    }

    private <R> void resolveOnWorldThread(
            World scheduled,
            String operationCode,
            String worldKey,
            R request,
            OperationEnvelope operation,
            HytaleWorldOperationGateway<R> gateway,
            CompletableFuture<LiveOperationResult> completion
    ) {
        try {
            World current = findWorld(worldKey);
            if (current == null || current != scheduled) {
                completion.complete(retry(
                        operationCode,
                        "world_instance_changed",
                        null
                ));
                return;
            }
            Store<EntityStore> store =
                    current.getEntityStore().getStore();
            LiveOperationResult result = gateway.applyOrResolve(
                    current, store, request, operation
            );
            completion.complete(result == null
                    ? retry(
                            operationCode,
                            "world_gateway_returned_null",
                            null
                    )
                    : result);
        } catch (Throwable failure) {
            completion.complete(retry(
                    operationCode, "world_gateway_failed", failure
            ));
        }
    }

    private LiveOperationResult retry(
            String operationCode,
            String suffix,
            Throwable failure
    ) {
        String prefix = operationCode == null || operationCode.isBlank()
                ? "persistence"
                : operationCode.trim();
        return LiveOperationResult.retryable(
                prefix + "_" + suffix, failure
        );
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
