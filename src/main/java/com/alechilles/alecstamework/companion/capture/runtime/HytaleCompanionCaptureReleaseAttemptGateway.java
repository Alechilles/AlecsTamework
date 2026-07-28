package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseRequest;
import com.alechilles.alecstamework.companion.capture.runtime.CaptureReleaseWorldAttempt.AttemptGateway;
import com.alechilles.alecstamework.companion.capture.runtime.CaptureReleaseWorldAttempt.InventoryProbe;
import com.alechilles.alecstamework.companion.capture.runtime.CaptureReleaseWorldAttempt.InventoryStatus;
import com.alechilles.alecstamework.companion.capture.runtime.CaptureReleaseWorldAttempt.ProjectionProbe;
import com.alechilles.alecstamework.companion.capture.runtime.CaptureReleaseWorldAttempt.ProjectionStatus;
import com.alechilles.alecstamework.companion.capture.runtime.CaptureReleaseWorldAttempt.ReceiptPersistence;
import com.alechilles.alecstamework.companion.capture.runtime.CaptureReleaseWorldAttempt.ReplacementAttempt;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.items.HytaleCompanionProjectionSpawnExecutor;
import com.alechilles.alecstamework.items.persistence.HytaleCapturedArtifactAdapter;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Thin Hytale inventory and projection bridge for one captured-artifact release attempt. */
final class HytaleCompanionCaptureReleaseAttemptGateway
        implements AttemptGateway {
    private final HytaleCaptureReleaseInventoryGateway inventory;
    private final HytaleCaptureReleaseProjectionGateway projection;
    private final HytaleCaptureReleaseDurabilityBarrier durability;

    HytaleCompanionCaptureReleaseAttemptGateway(
            World world,
            Store<EntityStore> store,
            CompanionCaptureReleaseRequest request,
            OperationEnvelope operation,
            HytaleCapturedArtifactAdapter artifacts,
            SnapshotCodecRegistry snapshotCodecs,
            HytaleCompanionProjectionSpawnExecutor projections
    ) {
        this.inventory = new HytaleCaptureReleaseInventoryGateway(
                world,
                store,
                request,
                artifacts
        );
        this.projection = new HytaleCaptureReleaseProjectionGateway(
                world,
                store,
                request,
                operation,
                snapshotCodecs,
                projections
        );
        this.durability = new HytaleCaptureReleaseDurabilityBarrier(
                world, store, request
        );
    }

    @Override
    public InventoryProbe probeInventory() {
        return inventory.probe();
    }

    @Override
    public ReplacementAttempt replaceSourceWithReceipt() {
        return inventory.replaceSourceWithReceipt();
    }

    @Override
    public LiveOperationResult applyOrResolveProjection() {
        return projection.applyOrResolve();
    }

    @Override
    public void releaseProjectionHold() {
        projection.releaseHold();
    }

    @Override
    public CompletionStage<ReceiptPersistence> persistActorReceipt() {
        InventoryProbe inventory = probeInventory();
        if (inventory.status() == InventoryStatus.UNAVAILABLE) {
            return CompletableFuture.completedFuture(
                    ReceiptPersistence.retryable(inventory.cause())
            );
        }
        if (inventory.status() != InventoryStatus.RECEIPT) {
            return CompletableFuture.completedFuture(
                    ReceiptPersistence.conflict(inventory.cause())
            );
        }
        return durability.saveActorReceipt();
    }

    @Override
    public CompletionStage<ReceiptPersistence> persistTargetChunkReceipt() {
        ProjectionProbe projection = probeProjectionReceipt();
        if (projection.status()
                == ProjectionStatus.UNAVAILABLE) {
            return CompletableFuture.completedFuture(
                    ReceiptPersistence.retryable(projection.cause())
            );
        }
        if (projection.status()
                != ProjectionStatus.EXACT) {
            return CompletableFuture.completedFuture(
                    ReceiptPersistence.conflict(projection.cause())
            );
        }
        if (projection.chunkIndex() == null) {
            return CompletableFuture.completedFuture(
                    ReceiptPersistence.conflict(null)
            );
        }
        long expectedChunkIndex =
                this.projection.receiptChunkIndex();
        if (projection.chunkIndex() != expectedChunkIndex) {
            return CompletableFuture.completedFuture(
                    ReceiptPersistence.retryable(null)
            );
        }
        return durability.saveTargetChunkReceipt(
                expectedChunkIndex
        );
    }

    @Override
    public CompletionStage<LiveOperationResult> resumeOnWorldThread(
            Supplier<CompletionStage<LiveOperationResult>> continuation
    ) {
        return durability.resumeOnWorldThread(continuation);
    }

    @Override
    public ProjectionProbe probeProjectionReceipt() {
        return projection.probe();
    }

    @Override
    public ProjectionProbe probeProjectionReceiptInChunk(
            long expectedChunkIndex
    ) {
        return projection.probeInChunk(expectedChunkIndex);
    }
}
