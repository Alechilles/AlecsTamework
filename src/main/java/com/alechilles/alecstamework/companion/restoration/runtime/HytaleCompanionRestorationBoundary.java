package com.alechilles.alecstamework.companion.restoration.runtime;

import com.alechilles.alecstamework.companion.restoration.CompanionRestorationLiveBoundary;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationRequest;
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
 * Dispatches restoration to the named current world and resolves ECS state only on its thread.
 *
 * <p>No {@code World}, {@code Store}, {@code Ref}, or component crosses the asynchronous
 * persistence boundary. A world replacement between scheduling and execution is treated as a
 * retry, so the next attempt resolves the new current world instead of touching stale ECS state.</p>
 */
public final class HytaleCompanionRestorationBoundary
        implements CompanionRestorationLiveBoundary {
    private final CompanionRestorationWorldGateway gateway;
    private final Function<String, World> worldLookup;

    public HytaleCompanionRestorationBoundary(
            @Nonnull CompanionRestorationWorldGateway gateway
    ) {
        this(gateway, worldKey -> Universe.get().getWorld(worldKey));
    }

    HytaleCompanionRestorationBoundary(
            @Nonnull CompanionRestorationWorldGateway gateway,
            @Nonnull Function<String, World> worldLookup
    ) {
        if (gateway == null || worldLookup == null) {
            throw new IllegalArgumentException(
                    "Restoration world boundary dependencies are required"
            );
        }
        this.gateway = gateway;
        this.worldLookup = worldLookup;
    }

    @Override
    @Nonnull
    public CompletionStage<LiveOperationResult> applyOrResolve(
            @Nonnull CompanionRestorationRequest request,
            @Nonnull OperationEnvelope operation
    ) {
        if (request == null || operation == null) {
            return LiveOperationResult.retryable(
                    "restoration_world_request_invalid",
                    null
            ).completed();
        }
        World scheduled = findWorld(request.targetWorldKey());
        if (scheduled == null) {
            return LiveOperationResult.retryable(
                    "restoration_world_unavailable",
                    null
            ).completed();
        }
        CompletableFuture<LiveOperationResult> completion =
                new CompletableFuture<>();
        try {
            scheduled.execute(() -> resolveOnWorldThread(
                    scheduled, request, operation, completion
            ));
        } catch (Throwable failure) {
            completion.complete(LiveOperationResult.retryable(
                    "restoration_world_dispatch_failed",
                    failure
            ));
        }
        return completion;
    }

    private void resolveOnWorldThread(
            World scheduled,
            CompanionRestorationRequest request,
            OperationEnvelope operation,
            CompletableFuture<LiveOperationResult> completion
    ) {
        try {
            World current = findWorld(request.targetWorldKey());
            if (current == null || current != scheduled) {
                completion.complete(LiveOperationResult.retryable(
                        "restoration_world_instance_changed",
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
                    ? LiveOperationResult.retryable(
                            "restoration_world_gateway_returned_null",
                            null
                    )
                    : result);
        } catch (Throwable failure) {
            completion.complete(LiveOperationResult.retryable(
                    "restoration_world_gateway_failed",
                    failure
            ));
        }
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
