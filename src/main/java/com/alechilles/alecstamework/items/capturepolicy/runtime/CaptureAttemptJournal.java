package com.alechilles.alecstamework.items.capturepolicy.runtime;

import com.alechilles.alecstamework.persistence.sqlite.CaptureAttemptRecord;
import com.alechilles.alecstamework.persistence.sqlite.CaptureAttemptRepository;
import java.util.List;
import java.util.UUID;
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
    default CompletableFuture<CaptureAttemptRepository.MutationResult> markSourceConsumed(
            @Nonnull String attemptId, long consumedAtMs) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("capture_source_spend_unavailable"));
    }

    @Nonnull
    default CompletableFuture<CaptureAttemptRepository.MutationResult> markSourceReceipted(
            @Nonnull String attemptId, long receiptedAtMs) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("capture_source_receipt_unavailable"));
    }

    @Nonnull
    default CompletableFuture<Boolean> requireSourceRefund(
            @Nonnull String attemptId, @Nonnull String reason, long nowMs) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("capture_source_refund_unavailable"));
    }

    @Nonnull
    default CompletableFuture<Boolean> cancelUnreceiptedSuccess(
            @Nonnull String attemptId, @Nonnull String reason, long nowMs) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("capture_unspent_cancel_unavailable"));
    }

    @Nonnull
    CompletableFuture<CaptureAttemptRepository.MutationResult> advance(
            @Nonnull String attemptId,
            @Nonnull CaptureAttemptRecord.State expected,
            @Nonnull CaptureAttemptRecord.State next,
            @Nullable String reasonCode,
            @Nullable String lastError,
            long nowMs);

    /** Recovery-only convergence from an interrupted successful roll to its durable terminal fact. */
    @Nonnull
    default CompletableFuture<CaptureAttemptRepository.MutationResult> reconcileTerminal(
            @Nonnull String attemptId,
            @Nonnull CaptureAttemptRecord.State expected,
            @Nonnull CaptureAttemptRecord.State terminal,
            @Nonnull String reasonCode,
            long nowMs) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("capture_terminal_reconciliation_unavailable"));
    }

    @Nonnull
    CompletableFuture<Boolean> markEventEmitted(@Nonnull String attemptId, long emittedAtMs);

    @Nonnull
    CompletableFuture<CaptureAttemptRepository.FailureCooldown> findFailureCooldown(
            @Nonnull UUID actorUuid, @Nonnull String spawnerConfigId);

    @Nullable
    CaptureAttemptRecord find(@Nonnull String attemptId) throws Exception;

    @Nonnull
    List<CaptureAttemptRecord> loadRecoverable() throws Exception;
}
