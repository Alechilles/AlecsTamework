package com.alechilles.alecstamework.items.capturepolicy.runtime;

import com.alechilles.alecstamework.persistence.sqlite.CaptureAttemptRecord;
import com.alechilles.alecstamework.persistence.sqlite.CaptureAttemptRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Production capture-attempt journal backed by schema-v8 SQLite repositories. */
public final class SqliteCaptureAttemptJournal implements CaptureAttemptJournal {
    private final CaptureAttemptRepository repository;

    public SqliteCaptureAttemptJournal(@Nonnull CaptureAttemptRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public CompletableFuture<CaptureAttemptRepository.PrepareResult> prepare(CaptureAttemptRecord attempt) {
        return committed(repository.prepareAsync(attempt));
    }

    @Override
    public CompletableFuture<CaptureAttemptRepository.MutationResult> resolve(
            CaptureAttemptRepository.ResolutionMutation mutation) {
        return committed(repository.resolveAsync(mutation));
    }

    @Override
    public CompletableFuture<CaptureAttemptRepository.MutationResult> advance(
            String attemptId,
            CaptureAttemptRecord.State expected,
            CaptureAttemptRecord.State next,
            @Nullable String reasonCode,
            @Nullable String lastError,
            long nowMs) {
        return committed(repository.advanceAsync(
                attemptId, expected, next, reasonCode, lastError, nowMs));
    }

    @Override
    public CompletableFuture<Boolean> markEventEmitted(String attemptId, long emittedAtMs) {
        return committed(repository.markEventEmittedAsync(attemptId, emittedAtMs));
    }

    @Override
    public CaptureAttemptRecord find(String attemptId) throws Exception {
        return repository.find(attemptId);
    }

    @Override
    public List<CaptureAttemptRecord> loadRecoverable() throws Exception {
        return repository.loadRecoverable();
    }

    private static <T> CompletableFuture<T> committed(
            @Nullable PersistenceWriteQueue.WriteSubmission<T> submission) {
        if (submission == null || !submission.accepted() || submission.completion() == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("capture_attempt_write_rejected"));
        }
        return submission.completion().thenCompose(outcome -> {
            if (outcome != null && outcome.isCommitted() && outcome.value() != null) {
                return CompletableFuture.completedFuture(outcome.value());
            }
            String reason = outcome == null || outcome.failureReason() == null
                    ? "capture_attempt_write_failed"
                    : outcome.failureReason();
            Throwable failure = outcome == null ? null : outcome.failure();
            return CompletableFuture.failedFuture(
                    failure == null ? new IllegalStateException(reason) : new IllegalStateException(reason, failure));
        });
    }
}
