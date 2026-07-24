package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Small immutable evidence vocabulary for one capture-release world attempt.
 *
 * <p>The executor owns ordering; Hytale gateways only produce these exact observations and
 * durability receipts. Keeping the protocol separate prevents either collaborator becoming a
 * mixed orchestration-and-platform god class.</p>
 */
final class CaptureReleaseWorldAttempt {
    private CaptureReleaseWorldAttempt() {
    }

    enum InventoryStatus {
        RECEIPT,
        SOURCE,
        UNAVAILABLE,
        CONFLICT
    }

    record InventoryProbe(
            @Nonnull InventoryStatus status,
            @Nullable Throwable cause
    ) {
        static InventoryProbe receipt() {
            return new InventoryProbe(InventoryStatus.RECEIPT, null);
        }

        static InventoryProbe source() {
            return new InventoryProbe(InventoryStatus.SOURCE, null);
        }

        static InventoryProbe unavailable(@Nullable Throwable cause) {
            return new InventoryProbe(InventoryStatus.UNAVAILABLE, cause);
        }

        static InventoryProbe conflict(@Nullable Throwable cause) {
            return new InventoryProbe(InventoryStatus.CONFLICT, cause);
        }
    }

    enum ReplacementStatus {
        RECEIPT,
        SOURCE_UNCHANGED,
        UNAVAILABLE,
        AMBIGUOUS
    }

    record ReplacementAttempt(
            @Nonnull ReplacementStatus status,
            @Nullable Throwable cause
    ) {
        static ReplacementAttempt receipt() {
            return new ReplacementAttempt(
                    ReplacementStatus.RECEIPT,
                    null
            );
        }

        static ReplacementAttempt sourceUnchanged(
                @Nullable Throwable cause
        ) {
            return new ReplacementAttempt(
                    ReplacementStatus.SOURCE_UNCHANGED,
                    cause
            );
        }

        static ReplacementAttempt unavailable(@Nullable Throwable cause) {
            return new ReplacementAttempt(
                    ReplacementStatus.UNAVAILABLE,
                    cause
            );
        }

        static ReplacementAttempt ambiguous(@Nullable Throwable cause) {
            return new ReplacementAttempt(
                    ReplacementStatus.AMBIGUOUS,
                    cause
            );
        }
    }

    enum ProjectionStatus {
        EXACT,
        MOVED,
        ABSENT,
        UNAVAILABLE,
        CONFLICT
    }

    record ProjectionProbe(
            @Nonnull ProjectionStatus status,
            @Nullable Long chunkIndex,
            @Nullable Throwable cause
    ) {
        static ProjectionProbe exact(long chunkIndex) {
            return new ProjectionProbe(
                    ProjectionStatus.EXACT,
                    chunkIndex,
                    null
            );
        }

        static ProjectionProbe moved(long chunkIndex) {
            return new ProjectionProbe(
                    ProjectionStatus.MOVED,
                    chunkIndex,
                    null
            );
        }

        static ProjectionProbe absent() {
            return new ProjectionProbe(
                    ProjectionStatus.ABSENT,
                    null,
                    null
            );
        }

        static ProjectionProbe unavailable(@Nullable Throwable cause) {
            return new ProjectionProbe(
                    ProjectionStatus.UNAVAILABLE,
                    null,
                    cause
            );
        }

        static ProjectionProbe conflict(@Nullable Throwable cause) {
            return new ProjectionProbe(
                    ProjectionStatus.CONFLICT,
                    null,
                    cause
            );
        }
    }

    enum ReceiptPersistenceStatus {
        SAVED,
        RETRYABLE,
        CONFLICT
    }

    record ReceiptPersistence(
            @Nonnull ReceiptPersistenceStatus status,
            @Nullable Long targetChunkIndex,
            @Nullable Throwable cause
    ) {
        static ReceiptPersistence saved() {
            return new ReceiptPersistence(
                    ReceiptPersistenceStatus.SAVED,
                    null,
                    null
            );
        }

        static ReceiptPersistence savedTargetChunk(long chunkIndex) {
            return new ReceiptPersistence(
                    ReceiptPersistenceStatus.SAVED,
                    chunkIndex,
                    null
            );
        }

        static ReceiptPersistence retryable(@Nullable Throwable cause) {
            return new ReceiptPersistence(
                    ReceiptPersistenceStatus.RETRYABLE,
                    null,
                    cause
            );
        }

        static ReceiptPersistence conflict(@Nullable Throwable cause) {
            return new ReceiptPersistence(
                    ReceiptPersistenceStatus.CONFLICT,
                    null,
                    cause
            );
        }
    }

    interface AttemptGateway {
        @Nonnull
        InventoryProbe probeInventory();

        @Nonnull
        ReplacementAttempt replaceSourceWithReceipt();

        @Nonnull
        LiveOperationResult applyOrResolveProjection();

        @Nonnull
        CompletionStage<ReceiptPersistence> persistActorReceipt();

        @Nonnull
        CompletionStage<ReceiptPersistence> persistTargetChunkReceipt();

        @Nonnull
        CompletionStage<LiveOperationResult> resumeOnWorldThread(
                @Nonnull Supplier<CompletionStage<LiveOperationResult>>
                        continuation
        );

        @Nonnull
        ProjectionProbe probeProjectionReceipt();

        @Nonnull
        ProjectionProbe probeProjectionReceiptInChunk(
                long expectedChunkIndex
        );

        /** Releases the runtime-only movement hold after canonical publication. */
        default void releaseProjectionHold() {
        }
    }
}
