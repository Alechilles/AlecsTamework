package com.alechilles.alecstamework.items.capturepolicy.runtime;

import com.alechilles.alecstamework.persistence.sqlite.CaptureAttemptRecord;
import com.alechilles.alecstamework.persistence.sqlite.CaptureAttemptRepository;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Durable, asynchronous port used by the capture-attempt coordinator. */
public interface CaptureAttemptJournal {
    @Nonnull
    CompletableFuture<CaptureAttemptRepository.PrepareResult> prepare(
            @Nonnull CaptureAttemptRecord attempt);

    @Nonnull
    CompletableFuture<CaptureAttemptRepository.MutationResult> resolve(
            @Nonnull CaptureAttemptRepository.ResolutionMutation mutation);

    @Nonnull
    CompletableFuture<CaptureAttemptRepository.MutationResult> advance(
            @Nonnull String attemptId,
            @Nonnull CaptureAttemptRecord.State expected,
            @Nonnull CaptureAttemptRecord.State next,
            @Nullable String reasonCode,
            @Nullable String lastError,
            long nowMs);

    @Nonnull
    CompletableFuture<Boolean> markEventEmitted(@Nonnull String attemptId, long emittedAtMs);

    @Nullable
    CaptureAttemptRecord find(@Nonnull String attemptId) throws Exception;

    @Nonnull
    List<CaptureAttemptRecord> loadRecoverable() throws Exception;
}
