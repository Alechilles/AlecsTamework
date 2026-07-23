package com.alechilles.alecstamework.companion.revival;

import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Typed receipt resolution for the composite charge-and-spawn boundary. */
public record PaidRevivalLiveResult(
        @Nonnull Status status,
        @Nonnull String code,
        @Nullable Throwable cause
) {
    public PaidRevivalLiveResult {
        if (status == null || code == null || code.isBlank()) {
            throw new IllegalArgumentException(
                    "Paid revival live status and code are required"
            );
        }
        code = code.trim();
        if (status == Status.CONFIRMED && cause != null) {
            throw new IllegalArgumentException(
                    "Confirmed paid revival cannot carry failure"
            );
        }
    }

    @Nonnull
    public static PaidRevivalLiveResult confirmed(@Nonnull String code) {
        return new PaidRevivalLiveResult(Status.CONFIRMED, code, null);
    }

    @Nonnull
    public static PaidRevivalLiveResult noCharge(@Nonnull String code) {
        return new PaidRevivalLiveResult(Status.NO_CHARGE, code, null);
    }

    @Nonnull
    public static PaidRevivalLiveResult refundRequired(
            @Nonnull String code
    ) {
        return new PaidRevivalLiveResult(
                Status.REFUND_REQUIRED, code, null
        );
    }

    @Nonnull
    public static PaidRevivalLiveResult retryable(
            @Nonnull String code,
            @Nullable Throwable cause
    ) {
        return new PaidRevivalLiveResult(Status.RETRYABLE, code, cause);
    }

    @Nonnull
    public static PaidRevivalLiveResult unknown(
            @Nonnull String code,
            @Nullable Throwable cause
    ) {
        return new PaidRevivalLiveResult(Status.UNKNOWN, code, cause);
    }

    /** Maps the typed economic disposition into the one shared phase protocol. */
    @Nonnull
    public LiveOperationResult sharedResult() {
        return switch (status) {
            case CONFIRMED -> LiveOperationResult.confirmed(code);
            case NO_CHARGE, REFUND_REQUIRED ->
                    LiveOperationResult.compensate(code, cause);
            case RETRYABLE -> LiveOperationResult.retryable(code, cause);
            case UNKNOWN -> LiveOperationResult.unknown(code, cause);
        };
    }

    @Nonnull
    public CompletionStage<PaidRevivalLiveResult> completed() {
        return CompletableFuture.completedFuture(this);
    }

    public enum Status {
        CONFIRMED,
        NO_CHARGE,
        REFUND_REQUIRED,
        RETRYABLE,
        UNKNOWN
    }
}
