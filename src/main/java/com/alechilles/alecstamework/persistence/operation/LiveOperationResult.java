package com.alechilles.alecstamework.persistence.operation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Exact outcome of one idempotent live boundary executed outside the database transaction. */
public record LiveOperationResult(@Nonnull Status status,
                                  @Nonnull String code,
                                  @Nullable Throwable cause) {
    public LiveOperationResult {
        if (status == null || code == null || code.isBlank()) {
            throw new IllegalArgumentException("Live operation status and code are required");
        }
        code = code.trim();
        if (status == Status.CONFIRMED && cause != null) {
            throw new IllegalArgumentException("Confirmed live operation cannot carry failure");
        }
    }

    /** Returns positive evidence that the live effect is complete. */
    @Nonnull
    public static LiveOperationResult confirmed(@Nonnull String code) {
        return new LiveOperationResult(Status.CONFIRMED, code, null);
    }

    /** Returns exact evidence that retrying the same idempotent boundary is safe. */
    @Nonnull
    public static LiveOperationResult retryable(
            @Nonnull String code,
            @Nullable Throwable cause
    ) {
        return new LiveOperationResult(Status.RETRYABLE, code, cause);
    }

    /** Returns exact evidence that normal completion is impossible and compensation is required. */
    @Nonnull
    public static LiveOperationResult compensate(
            @Nonnull String code,
            @Nullable Throwable cause
    ) {
        return new LiveOperationResult(Status.COMPENSATE, code, cause);
    }

    /** Returns ambiguous evidence that must fail closed until recovery can prove an outcome. */
    @Nonnull
    public static LiveOperationResult unknown(
            @Nonnull String code,
            @Nullable Throwable cause
    ) {
        return new LiveOperationResult(Status.UNKNOWN, code, cause);
    }

    /** Returns this result as an already-completed asynchronous live boundary outcome. */
    @Nonnull
    public CompletionStage<LiveOperationResult> completed() {
        return CompletableFuture.completedFuture(this);
    }

    public enum Status {
        CONFIRMED,
        COMPENSATE,
        RETRYABLE,
        UNKNOWN
    }
}
