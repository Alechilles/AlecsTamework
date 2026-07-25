package com.alechilles.alecstamework.companion.coop.runtime;

import com.alechilles.alecstamework.companion.coop.CoopCapturedItemSourceEvidence;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.runtime.player.InventoryOperationReceipt;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Engine-neutral live inventory boundary for captured-item coop intake.
 *
 * <p>Implementations must compare the generic player receipt and the frozen inventory position
 * in one world-thread probe. Mutations are exact compare-and-set operations against the immutable
 * artifacts retained by the operation payload.</p>
 */
public interface CompanionCoopCapturedItemAttempt {

    /** Reads the exact receipt and inventory states without mutating either. */
    @Nonnull
    CompositeProbe probe(
            @Nonnull InventoryOperationReceipt expectedReceipt,
            @Nonnull CoopCapturedItemSourceEvidence source
    );

    /** Installs only the exact generic receipt, idempotently. */
    @Nonnull
    ReceiptMutation installReceipt(
            @Nonnull InventoryOperationReceipt expectedReceipt
    );

    /** Replaces only the exact source artifact with its exact marked receipt artifact. */
    @Nonnull
    ArtifactMutation markSource(
            @Nonnull CoopCapturedItemSourceEvidence source
    );

    /** Removes only the exact marked receipt artifact after durable operation commit. */
    @Nonnull
    ArtifactMutation retireMarkedArtifact(
            @Nonnull CoopCapturedItemSourceEvidence source
    );

    /** Removes only the exact generic receipt after durable artifact retirement. */
    @Nonnull
    ReceiptMutation removeReceipt(
            @Nonnull InventoryOperationReceipt expectedReceipt
    );

    /** Persists the actor carrying the receipt and inventory mutation. */
    @Nonnull
    CompletionStage<SaveResult> persistActor();

    /**
     * Re-enters the exact actor world thread before invoking the continuation.
     *
     * <p>The continuation itself may return an asynchronous actor save.</p>
     */
    @Nonnull
    CompletionStage<LiveOperationResult> resumeOnActorWorldThread(
            @Nonnull Supplier<CompletionStage<LiveOperationResult>>
                    continuation
    );

    enum ReceiptState {
        EXACT,
        ABSENT,
        CONFLICT,
        UNAVAILABLE
    }

    enum ArtifactState {
        SOURCE,
        MARKED,
        ABSENT,
        CONFLICT,
        UNAVAILABLE
    }

    record CompositeProbe(
            @Nonnull ReceiptState receiptState,
            @Nonnull ArtifactState artifactState,
            @Nullable Throwable cause
    ) {
        public static CompositeProbe of(
                ReceiptState receiptState,
                ArtifactState artifactState
        ) {
            return new CompositeProbe(receiptState, artifactState, null);
        }

        public static CompositeProbe unavailable(
                @Nullable Throwable cause
        ) {
            return new CompositeProbe(
                    ReceiptState.UNAVAILABLE,
                    ArtifactState.UNAVAILABLE,
                    cause
            );
        }
    }

    enum ReceiptMutationStatus {
        EXACT,
        RETRYABLE,
        CONFLICT
    }

    record ReceiptMutation(
            @Nonnull ReceiptMutationStatus status,
            @Nullable Throwable cause
    ) {
        public static ReceiptMutation exact() {
            return new ReceiptMutation(
                    ReceiptMutationStatus.EXACT, null
            );
        }

        public static ReceiptMutation retryable(
                @Nullable Throwable cause
        ) {
            return new ReceiptMutation(
                    ReceiptMutationStatus.RETRYABLE, cause
            );
        }

        public static ReceiptMutation conflict(
                @Nullable Throwable cause
        ) {
            return new ReceiptMutation(
                    ReceiptMutationStatus.CONFLICT, cause
            );
        }
    }

    enum ArtifactMutationStatus {
        MARKED,
        ABSENT,
        RETRYABLE,
        CONFLICT
    }

    record ArtifactMutation(
            @Nonnull ArtifactMutationStatus status,
            @Nullable Throwable cause
    ) {
        public static ArtifactMutation marked() {
            return new ArtifactMutation(
                    ArtifactMutationStatus.MARKED, null
            );
        }

        public static ArtifactMutation absent() {
            return new ArtifactMutation(
                    ArtifactMutationStatus.ABSENT, null
            );
        }

        public static ArtifactMutation retryable(
                @Nullable Throwable cause
        ) {
            return new ArtifactMutation(
                    ArtifactMutationStatus.RETRYABLE, cause
            );
        }

        public static ArtifactMutation conflict(
                @Nullable Throwable cause
        ) {
            return new ArtifactMutation(
                    ArtifactMutationStatus.CONFLICT, cause
            );
        }
    }

    enum SaveStatus {
        SAVED,
        RETRYABLE,
        CONFLICT
    }

    record SaveResult(
            @Nonnull SaveStatus status,
            @Nullable Throwable cause
    ) {
        public static SaveResult saved() {
            return new SaveResult(SaveStatus.SAVED, null);
        }

        public static SaveResult retryable(@Nullable Throwable cause) {
            return new SaveResult(SaveStatus.RETRYABLE, cause);
        }

        public static SaveResult conflict(@Nullable Throwable cause) {
            return new SaveResult(SaveStatus.CONFLICT, cause);
        }
    }
}
