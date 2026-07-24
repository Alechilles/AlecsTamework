package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseRequest;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

/**
 * Asynchronous world-thread inventory/projection receipt durability for captured-artifact release.
 */
@FunctionalInterface
public interface CompanionCaptureReleaseWorldGateway {
    @Nonnull
    CompletionStage<LiveOperationResult> applyOrResolve(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull CompanionCaptureReleaseRequest request,
            @Nonnull OperationEnvelope operation
    );

    /** Releases a runtime-only projection hold after canonical publication. */
    @Nonnull
    default CompletionStage<Void> releaseProjectionHold(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull CompanionCaptureReleaseRequest request,
            @Nonnull OperationEnvelope operation
    ) {
        return CompletableFuture.completedFuture(null);
    }
}
