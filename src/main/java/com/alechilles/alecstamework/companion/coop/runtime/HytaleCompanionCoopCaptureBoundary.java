package com.alechilles.alecstamework.companion.coop.runtime;

import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureLiveBoundary;
import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureRequest;
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
 * Dispatches durable coop capture to the current world and flattens its asynchronous chunk save.
 */
public final class HytaleCompanionCoopCaptureBoundary
        implements CompanionCoopCaptureLiveBoundary {
    private final CompanionCoopCaptureWorldGateway gateway;
    private final Function<String, World> worldLookup;

    public HytaleCompanionCoopCaptureBoundary(
            @Nonnull CompanionCoopCaptureWorldGateway gateway
    ) {
        this(gateway, worldKey -> Universe.get().getWorld(worldKey));
    }

    HytaleCompanionCoopCaptureBoundary(
            CompanionCoopCaptureWorldGateway gateway,
            Function<String, World> worldLookup
    ) {
        if (gateway == null || worldLookup == null) {
            throw new IllegalArgumentException(
                    "Coop capture world boundary dependencies are required"
            );
        }
        this.gateway = gateway;
        this.worldLookup = worldLookup;
    }

    @Override
    @Nonnull
    public CompletionStage<LiveOperationResult> applyOrResolve(
            @Nonnull CompanionCoopCaptureRequest request,
            @Nonnull OperationEnvelope operation
    ) {
        if (request == null || operation == null) {
            return retryable("world_request_invalid", null);
        }
        String worldKey = request.source().sourceWorldKey();
        World scheduled = findWorld(worldKey);
        if (scheduled == null) {
            return retryable("world_unavailable", null);
        }

        CompletableFuture<LiveOperationResult> completion =
                new CompletableFuture<>();
        try {
            scheduled.execute(() -> dispatchOnWorldThread(
                    scheduled, worldKey, request, operation, completion
            ));
        } catch (Throwable failure) {
            completion.complete(retry("world_dispatch_failed", failure));
        }
        return completion;
    }

    private void dispatchOnWorldThread(
            World scheduled,
            String worldKey,
            CompanionCoopCaptureRequest request,
            OperationEnvelope operation,
            CompletableFuture<LiveOperationResult> completion
    ) {
        try {
            World current = findWorld(worldKey);
            if (current == null || current != scheduled) {
                completion.complete(retry("world_instance_changed", null));
                return;
            }
            Store<EntityStore> store =
                    current.getEntityStore().getStore();
            CompletionStage<LiveOperationResult> result =
                    gateway.applyOrResolve(
                            current, store, request, operation
                    );
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
        } catch (Throwable failure) {
            completion.complete(retry("world_gateway_failed", failure));
        }
    }

    private CompletionStage<LiveOperationResult> retryable(
            String suffix,
            @Nullable Throwable failure
    ) {
        return CompletableFuture.completedFuture(retry(suffix, failure));
    }

    private LiveOperationResult retry(
            String suffix,
            @Nullable Throwable failure
    ) {
        return LiveOperationResult.retryable(
                "coop_capture_" + suffix, failure
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
