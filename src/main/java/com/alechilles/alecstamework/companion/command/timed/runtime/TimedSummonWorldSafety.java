package com.alechilles.alecstamework.companion.command.timed.runtime;

import com.alechilles.alecstamework.companion.command.timed.runtime.TimedSummonWorldAttempt.AttemptGateway;
import com.alechilles.alecstamework.companion.command.timed.runtime.TimedSummonWorldAttempt.ChunkPersistence;
import com.alechilles.alecstamework.companion.command.timed.runtime.TimedSummonWorldAttempt.MutationAttempt;
import com.alechilles.alecstamework.companion.command.timed.runtime.TimedSummonWorldAttempt.ProjectionProbe;
import com.alechilles.alecstamework.companion.command.timed.runtime.TimedSummonWorldAttempt.ReceiptProbe;
import com.alechilles.alecstamework.companion.command.timed.runtime.TimedSummonWorldAttempt.SourceProbe;
import com.alechilles.alecstamework.companion.command.timed.runtime.TimedSummonWorldAttempt.StoreProbe;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Null, exception, and asynchronous-failure normalization for timed attempts. */
final class TimedSummonWorldSafety {
    ProjectionProbe probeStart(
            AttemptGateway attempts,
            TimedSummonWorldAuthority.Start authority
    ) {
        try {
            ProjectionProbe probe = attempts.probeStart(authority);
            return probe == null
                    ? ProjectionProbe.retryable(null)
                    : probe;
        } catch (RuntimeException | LinkageError failure) {
            return ProjectionProbe.retryable(failure);
        }
    }

    StoreProbe probeStore(
            AttemptGateway attempts,
            TimedSummonWorldAuthority.Store authority
    ) {
        try {
            StoreProbe probe = attempts.probeStore(authority);
            return probe == null
                    ? retryableStore(null)
                    : probe;
        } catch (RuntimeException | LinkageError failure) {
            return retryableStore(failure);
        }
    }

    MutationAttempt spawn(
            AttemptGateway attempts,
            TimedSummonWorldAuthority.Start authority
    ) {
        try {
            MutationAttempt result = attempts.spawnExact(authority);
            return result == null
                    ? MutationAttempt.retryable(null)
                    : result;
        } catch (RuntimeException | LinkageError failure) {
            return MutationAttempt.retryable(failure);
        }
    }

    MutationAttempt releaseStartHold(
            AttemptGateway attempts,
            TimedSummonWorldAuthority.Start authority
    ) {
        try {
            MutationAttempt result = attempts.releaseStartHold(authority);
            return result == null
                    ? MutationAttempt.retryable(null)
                    : result;
        } catch (RuntimeException | LinkageError failure) {
            return MutationAttempt.retryable(failure);
        }
    }

    MutationAttempt installReceipt(
            AttemptGateway attempts,
            TimedSummonWorldAuthority.Store authority
    ) {
        try {
            MutationAttempt result =
                    attempts.installRetirementReceipt(authority);
            return result == null
                    ? MutationAttempt.retryable(null)
                    : result;
        } catch (RuntimeException | LinkageError failure) {
            return MutationAttempt.retryable(failure);
        }
    }

    MutationAttempt retire(
            AttemptGateway attempts,
            TimedSummonWorldAuthority.Store authority
    ) {
        try {
            MutationAttempt result =
                    attempts.retireExactSource(authority);
            return result == null
                    ? MutationAttempt.retryable(null)
                    : result;
        } catch (RuntimeException | LinkageError failure) {
            return MutationAttempt.retryable(failure);
        }
    }

    CompletionStage<ChunkPersistence> persist(
            AttemptGateway attempts,
            long chunkIndex
    ) {
        CompletionStage<ChunkPersistence> persisted;
        try {
            persisted = attempts.persistChunkAndReadBack(chunkIndex);
        } catch (RuntimeException | LinkageError failure) {
            return completed(ChunkPersistence.retryable(failure));
        }
        if (persisted == null) {
            return completed(ChunkPersistence.retryable(null));
        }
        CompletableFuture<ChunkPersistence> normalized =
                new CompletableFuture<>();
        persisted.whenComplete((result, failure) -> normalized.complete(
                failure != null
                        ? ChunkPersistence.retryable(failure)
                        : result == null
                        ? ChunkPersistence.retryable(null)
                        : result
        ));
        return normalized;
    }

    CompletionStage<LiveOperationResult> resume(
            AttemptGateway attempts,
            Supplier<CompletionStage<LiveOperationResult>> continuation,
            LiveOperationResult failureResult
    ) {
        CompletionStage<LiveOperationResult> resumed;
        try {
            resumed = attempts.resumeOnWorldThread(continuation);
        } catch (RuntimeException | LinkageError failure) {
            return completed(LiveOperationResult.retryable(
                    failureResult.code(), failure
            ));
        }
        if (resumed == null) {
            return completed(failureResult);
        }
        CompletableFuture<LiveOperationResult> normalized =
                new CompletableFuture<>();
        resumed.whenComplete((result, failure) -> normalized.complete(
                failure != null
                        ? LiveOperationResult.retryable(
                                failureResult.code(), failure
                        )
                        : result == null ? failureResult : result
        ));
        return normalized;
    }

    private StoreProbe retryableStore(Throwable cause) {
        return StoreProbe.of(
                ReceiptProbe.retryable(cause),
                SourceProbe.retryable(cause)
        );
    }

    private <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }
}
