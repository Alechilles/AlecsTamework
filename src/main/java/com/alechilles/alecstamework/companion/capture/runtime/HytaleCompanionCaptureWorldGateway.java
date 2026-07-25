package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.items.persistence.HytaleCapturedArtifactAdapter;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.runtime.player
        .HytalePlayerDurabilityBarrier;
import com.alechilles.alecstamework.persistence.runtime.HytaleAsyncWorldOperationGateway;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Production receipt-first world gateway for exact live companion capture. */
public final class HytaleCompanionCaptureWorldGateway
        implements CompanionCaptureWorldGateway,
        HytaleAsyncWorldOperationGateway<CompanionCaptureRequest> {
    private final CompanionCaptureWorldExecutor executor;
    private final CompanionCaptureTameWorldExecutor tameExecutor;
    private final HytaleCapturedArtifactAdapter artifacts;
    private final ComponentType<
            EntityStore,
            TameworkCaptureSourceReceiptsComponent> receiptType;

    public HytaleCompanionCaptureWorldGateway(
            @Nonnull ComponentType<
                    EntityStore,
                    TameworkCaptureSourceReceiptsComponent> receiptType
    ) {
        this(
                new CompanionCaptureWorldExecutor(),
                new CompanionCaptureTameWorldExecutor(),
                new HytaleCapturedArtifactAdapter(),
                receiptType
        );
    }

    HytaleCompanionCaptureWorldGateway(
            @Nonnull CompanionCaptureWorldExecutor executor,
            @Nonnull HytaleCapturedArtifactAdapter artifacts,
            @Nonnull ComponentType<
                    EntityStore,
                    TameworkCaptureSourceReceiptsComponent> receiptType
    ) {
        this(
                executor,
                new CompanionCaptureTameWorldExecutor(),
                artifacts,
                receiptType
        );
    }

    HytaleCompanionCaptureWorldGateway(
            @Nonnull CompanionCaptureWorldExecutor executor,
            @Nonnull CompanionCaptureTameWorldExecutor tameExecutor,
            @Nonnull HytaleCapturedArtifactAdapter artifacts,
            @Nonnull ComponentType<
                    EntityStore,
                    TameworkCaptureSourceReceiptsComponent> receiptType
    ) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.tameExecutor = Objects.requireNonNull(
                tameExecutor, "tameExecutor"
        );
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.receiptType = Objects.requireNonNull(
                receiptType, "receiptType"
        );
    }

    @Override
    @Nonnull
    public LiveOperationResult applyOrResolve(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull CompanionCaptureRequest request,
            @Nonnull OperationEnvelope operation
    ) {
        if (world == null || store == null) {
            return LiveOperationResult.unknown(
                    "capture_world_context_missing",
                    null
            );
        }
        try {
            store.assertThread();
        } catch (RuntimeException | LinkageError failure) {
            return LiveOperationResult.unknown(
                    "capture_world_thread_unavailable",
                    failure
            );
        }
        HytaleCompanionCaptureAttemptGateway attempts = attempts(
                world, store, request
        );
        return request.failedAttempt() || request.tameAndCommandLink()
                ? LiveOperationResult.retryable(
                "capture_async_source_barrier_required", null
        )
                : executor.execute(request, operation, attempts);
    }

    @Override
    @Nonnull
    public CompletionStage<LiveOperationResult> applyOrResolveAsync(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull CompanionCaptureRequest request,
            @Nonnull OperationEnvelope operation
    ) {
        LiveOperationResult invalid = validate(world, store);
        if (invalid != null) {
            return invalid.completed();
        }
        if (request.tameAndCommandLink()) {
            return tameExecutor.execute(
                    request,
                    operation,
                    new HytaleCompanionCaptureTameAttemptGateway(
                            world,
                            store,
                            request,
                            operation,
                            artifacts,
                            receiptType
                    )
            );
        }
        HytaleCompanionCaptureAttemptGateway attempts = attempts(
                world, store, request
        );
        if (!request.failedAttempt()) {
            return completed(executor.execute(
                    request, operation, attempts
            ));
        }
        HytalePlayerDurabilityBarrier durability =
                new HytalePlayerDurabilityBarrier(
                        world,
                        store,
                        request.targetWorldKey(),
                        request.source().actorUuid()
                );
        ResolvedCaptureSourceWorldExecutor.SpendProbe probe =
                attempts.probe();
        if (probe.status()
                != ResolvedCaptureSourceWorldExecutor.SpendStatus.SOURCE) {
            return finishResolvedSpend(
                    request, operation, attempts, durability
            );
        }
        ResolvedCaptureSourceWorldExecutor.ReceiptAttempt receipt =
                attempts.installReceipt();
        if (receipt.status()
                != ResolvedCaptureSourceWorldExecutor.ReceiptStatus
                .RECEIPTED) {
            return completed(executor.execute(
                    request, operation, attempts
            ));
        }
        return durability.saveActor().thenCompose(saved -> {
            if (!saved.saved()) {
                return completed(LiveOperationResult.retryable(
                        "capture_source_receipt_save_failed",
                        saved.failure()
                ));
            }
            return durability.resumeOnWorldThread(
                    () -> finishResolvedSpend(
                            request, operation, attempts, durability
                    ),
                    () -> LiveOperationResult.retryable(
                            "capture_world_instance_changed", null
                    )
            );
        });
    }

    private CompletionStage<LiveOperationResult> finishResolvedSpend(
            CompanionCaptureRequest request,
            OperationEnvelope operation,
            HytaleCompanionCaptureAttemptGateway attempts,
            HytalePlayerDurabilityBarrier durability
    ) {
        LiveOperationResult result = executor.execute(
                request, operation, attempts
        );
        if (result.status() != LiveOperationResult.Status.CONFIRMED
                || !attempts.consumedThisCall()) {
            return completed(result);
        }
        return durability.saveActor().thenApply(saved -> saved.saved()
                ? result
                : LiveOperationResult.retryable(
                "capture_source_spend_save_failed",
                saved.failure()
        ));
    }

    private HytaleCompanionCaptureAttemptGateway attempts(
            World world,
            Store<EntityStore> store,
            CompanionCaptureRequest request
    ) {
        return new HytaleCompanionCaptureAttemptGateway(
                world, store, request, artifacts, receiptType
        );
    }

    private LiveOperationResult validate(
            World world,
            Store<EntityStore> store
    ) {
        if (world == null || store == null) {
            return LiveOperationResult.unknown(
                    "capture_world_context_missing", null
            );
        }
        try {
            store.assertThread();
            return null;
        } catch (RuntimeException | LinkageError failure) {
            return LiveOperationResult.unknown(
                    "capture_world_thread_unavailable", failure
            );
        }
    }

    private CompletionStage<LiveOperationResult> completed(
            LiveOperationResult result
    ) {
        return CompletableFuture.completedFuture(result);
    }
}
