package com.alechilles.alecstamework.companion.command.timed.runtime;

import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Typed observations and mutations used by the timed live-world protocol. */
final class TimedSummonWorldAttempt {
    private TimedSummonWorldAttempt() {
    }

    enum EvidenceStatus {
        EXACT,
        ABSENT,
        RETRYABLE,
        CONFLICT
    }

    record ProjectionProbe(
            @Nonnull EvidenceStatus status,
            @Nullable Long chunkIndex,
            @Nullable Throwable cause
    ) {
        ProjectionProbe {
            requireChunk(status, chunkIndex, "projection");
        }

        static ProjectionProbe exact(long chunkIndex) {
            return new ProjectionProbe(
                    EvidenceStatus.EXACT, chunkIndex, null
            );
        }

        static ProjectionProbe absent() {
            return new ProjectionProbe(
                    EvidenceStatus.ABSENT, null, null
            );
        }

        static ProjectionProbe retryable(@Nullable Throwable cause) {
            return new ProjectionProbe(
                    EvidenceStatus.RETRYABLE, null, cause
            );
        }

        static ProjectionProbe conflict(@Nullable Throwable cause) {
            return new ProjectionProbe(
                    EvidenceStatus.CONFLICT, null, cause
            );
        }
    }

    record ReceiptProbe(
            @Nonnull EvidenceStatus status,
            @Nullable Long chunkIndex,
            @Nullable Throwable cause
    ) {
        ReceiptProbe {
            requireChunk(status, chunkIndex, "retirement receipt");
        }

        static ReceiptProbe exact(long chunkIndex) {
            return new ReceiptProbe(
                    EvidenceStatus.EXACT, chunkIndex, null
            );
        }

        static ReceiptProbe absent() {
            return new ReceiptProbe(
                    EvidenceStatus.ABSENT, null, null
            );
        }

        static ReceiptProbe retryable(@Nullable Throwable cause) {
            return new ReceiptProbe(
                    EvidenceStatus.RETRYABLE, null, cause
            );
        }

        static ReceiptProbe conflict(@Nullable Throwable cause) {
            return new ReceiptProbe(
                    EvidenceStatus.CONFLICT, null, cause
            );
        }
    }

    record SourceProbe(
            @Nonnull EvidenceStatus status,
            @Nullable Long chunkIndex,
            @Nullable Throwable cause
    ) {
        SourceProbe {
            requireChunk(status, chunkIndex, "source");
        }

        static SourceProbe exact(long chunkIndex) {
            return new SourceProbe(
                    EvidenceStatus.EXACT, chunkIndex, null
            );
        }

        static SourceProbe absent() {
            return new SourceProbe(
                    EvidenceStatus.ABSENT, null, null
            );
        }

        static SourceProbe retryable(@Nullable Throwable cause) {
            return new SourceProbe(
                    EvidenceStatus.RETRYABLE, null, cause
            );
        }

        static SourceProbe conflict(@Nullable Throwable cause) {
            return new SourceProbe(
                    EvidenceStatus.CONFLICT, null, cause
            );
        }
    }

    record StoreProbe(
            @Nonnull ReceiptProbe receipt,
            @Nonnull SourceProbe source
    ) {
        StoreProbe {
            if (receipt == null || source == null) {
                throw new IllegalArgumentException(
                        "Complete timed store evidence is required"
                );
            }
        }

        static StoreProbe of(
                ReceiptProbe receipt,
                SourceProbe source
        ) {
            return new StoreProbe(receipt, source);
        }
    }

    enum MutationStatus {
        EXACT,
        RETRYABLE,
        CONFLICT
    }

    record MutationAttempt(
            @Nonnull MutationStatus status,
            @Nullable Long chunkIndex,
            @Nullable Throwable cause
    ) {
        MutationAttempt {
            if (status == null
                    || (status == MutationStatus.EXACT)
                    != (chunkIndex != null)) {
                throw new IllegalArgumentException(
                        "Timed world mutation evidence is inconsistent"
                );
            }
        }

        static MutationAttempt exact(long chunkIndex) {
            return new MutationAttempt(
                    MutationStatus.EXACT, chunkIndex, null
            );
        }

        static MutationAttempt retryable(@Nullable Throwable cause) {
            return new MutationAttempt(
                    MutationStatus.RETRYABLE, null, cause
            );
        }

        static MutationAttempt conflict(@Nullable Throwable cause) {
            return new MutationAttempt(
                    MutationStatus.CONFLICT, null, cause
            );
        }
    }

    enum PersistenceStatus {
        SAVED,
        RETRYABLE,
        CONFLICT
    }

    record ChunkPersistence(
            @Nonnull PersistenceStatus status,
            @Nullable Long chunkIndex,
            @Nullable Throwable cause
    ) {
        ChunkPersistence {
            if (status == null
                    || (status == PersistenceStatus.SAVED)
                    != (chunkIndex != null)) {
                throw new IllegalArgumentException(
                        "Timed chunk persistence evidence is inconsistent"
                );
            }
        }

        static ChunkPersistence saved(long chunkIndex) {
            return new ChunkPersistence(
                    PersistenceStatus.SAVED, chunkIndex, null
            );
        }

        static ChunkPersistence retryable(@Nullable Throwable cause) {
            return new ChunkPersistence(
                    PersistenceStatus.RETRYABLE, null, cause
            );
        }

        static ChunkPersistence conflict(@Nullable Throwable cause) {
            return new ChunkPersistence(
                    PersistenceStatus.CONFLICT, null, cause
            );
        }
    }

    interface AttemptGateway {
        @Nonnull
        ProjectionProbe probeStart(
                @Nonnull TimedSummonWorldAuthority.Start authority
        );

        @Nonnull
        MutationAttempt spawnExact(
                @Nonnull TimedSummonWorldAuthority.Start authority
        );

        @Nonnull
        StoreProbe probeStore(
                @Nonnull TimedSummonWorldAuthority.Store authority
        );

        @Nonnull
        MutationAttempt installRetirementReceipt(
                @Nonnull TimedSummonWorldAuthority.Store authority
        );

        @Nonnull
        MutationAttempt retireExactSource(
                @Nonnull TimedSummonWorldAuthority.Store authority
        );

        /**
         * Saves the exact chunk and confirms its readback before completing.
         */
        @Nonnull
        CompletionStage<ChunkPersistence> persistChunkAndReadBack(
                long chunkIndex
        );

        /** Re-enters the owning world thread after asynchronous persistence. */
        @Nonnull
        CompletionStage<LiveOperationResult> resumeOnWorldThread(
                @Nonnull Supplier<CompletionStage<LiveOperationResult>>
                        continuation
        );
    }

    private static void requireChunk(
            EvidenceStatus status,
            Long chunkIndex,
            String label
    ) {
        if (status == null
                || (status == EvidenceStatus.EXACT)
                != (chunkIndex != null)) {
            throw new IllegalArgumentException(
                    "Timed " + label + " evidence is inconsistent"
            );
        }
    }
}
