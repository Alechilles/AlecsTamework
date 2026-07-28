package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseRequest;
import com.alechilles.alecstamework.companion.capture.runtime.CaptureReleaseWorldAttempt.AttemptGateway;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.items.HytaleCompanionProjectionSpawnExecutor;
import com.alechilles.alecstamework.items.persistence.HytaleCapturedArtifactAdapter;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

/** Production receipt-first world gateway for captured-artifact release. */
public final class HytaleCompanionCaptureReleaseWorldGateway
        implements CompanionCaptureReleaseWorldGateway {
    private final SnapshotCodecRegistry snapshotCodecs;
    private final HytaleCompanionProjectionSpawnExecutor projections;
    private final HytaleCapturedArtifactAdapter artifacts;
    private final CompanionCaptureReleaseWorldExecutor executor;
    private final WorldThreadGuard threadGuard;
    private final AttemptGatewayFactory attemptGateways;

    public HytaleCompanionCaptureReleaseWorldGateway(
            @Nonnull SnapshotCodecRegistry snapshotCodecs,
            @Nonnull HytaleCompanionProjectionSpawnExecutor projections
    ) {
        this(
                snapshotCodecs,
                projections,
                new HytaleCapturedArtifactAdapter(),
                new CompanionCaptureReleaseWorldExecutor(),
                Store::assertThread,
                null
        );
    }

    HytaleCompanionCaptureReleaseWorldGateway(
            SnapshotCodecRegistry snapshotCodecs,
            HytaleCompanionProjectionSpawnExecutor projections,
            HytaleCapturedArtifactAdapter artifacts,
            CompanionCaptureReleaseWorldExecutor executor,
            WorldThreadGuard threadGuard,
            AttemptGatewayFactory attemptGateways
    ) {
        this.snapshotCodecs = Objects.requireNonNull(
                snapshotCodecs,
                "snapshotCodecs"
        );
        this.projections = Objects.requireNonNull(projections, "projections");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.threadGuard = Objects.requireNonNull(
                threadGuard,
                "threadGuard"
        );
        this.attemptGateways = attemptGateways == null
                ? this::newAttemptGateway
                : attemptGateways;
    }

    @Override
    @Nonnull
    public CompletionStage<LiveOperationResult> applyOrResolve(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull CompanionCaptureReleaseRequest request,
            @Nonnull OperationEnvelope operation
    ) {
        try {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(store, "store");
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(operation, "operation");
            threadGuard.assertCurrent(store);
        } catch (RuntimeException | LinkageError failure) {
            return LiveOperationResult.unknown(
                    "capture_release_world_thread_unavailable",
                    failure
            ).completed();
        }
        return executor.execute(
                request,
                operation,
                attemptGateways.create(world, store, request, operation)
        );
    }

    @Override
    @Nonnull
    public CompletionStage<Void> releaseProjectionHold(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull CompanionCaptureReleaseRequest request,
            @Nonnull OperationEnvelope operation
    ) {
        try {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(store, "store");
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(operation, "operation");
            threadGuard.assertCurrent(store);
            AttemptGateway attempts = attemptGateways.create(
                    world,
                    store,
                    request,
                    operation
            );
            attempts.releaseProjectionHold();
            return CompletableFuture.completedFuture(null);
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private AttemptGateway newAttemptGateway(
            World world,
            Store<EntityStore> store,
            CompanionCaptureReleaseRequest request,
            OperationEnvelope operation
    ) {
        return new HytaleCompanionCaptureReleaseAttemptGateway(
                world,
                store,
                request,
                operation,
                artifacts,
                snapshotCodecs,
                projections
        );
    }

    @FunctionalInterface
    interface WorldThreadGuard {
        void assertCurrent(Store<EntityStore> store);
    }

    @FunctionalInterface
    interface AttemptGatewayFactory {
        AttemptGateway create(
                World world,
                Store<EntityStore> store,
                CompanionCaptureReleaseRequest request,
                OperationEnvelope operation
        );
    }
}
