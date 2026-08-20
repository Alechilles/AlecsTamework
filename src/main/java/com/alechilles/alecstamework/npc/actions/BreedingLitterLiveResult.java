package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Exact child receipts and disposition from one litter world boundary. */
public record BreedingLitterLiveResult(
        @Nonnull Status status,
        @Nonnull String code,
        @Nonnull Map<Integer, UUID> receipts,
        @Nullable Throwable cause
) {
    public BreedingLitterLiveResult {
        status = Objects.requireNonNull(status, "status");
        code = requireText(code);
        receipts = receipts == null ? Map.of() : Map.copyOf(receipts);
        if (status == Status.CONFIRMED && cause != null) {
            throw new IllegalArgumentException(
                    "Confirmed litter result cannot carry failure"
            );
        }
        if (status != Status.CONFIRMED && !receipts.isEmpty()) {
            throw new IllegalArgumentException(
                    "Only confirmed litter results may carry receipts"
            );
        }
    }

    @Nonnull
    public static BreedingLitterLiveResult confirmed(
            @Nonnull String code,
            @Nonnull Map<Integer, UUID> receipts
    ) {
        return new BreedingLitterLiveResult(
                Status.CONFIRMED, code, receipts, null
        );
    }

    @Nonnull
    public static BreedingLitterLiveResult retryable(
            @Nonnull String code,
            @Nullable Throwable cause
    ) {
        return new BreedingLitterLiveResult(
                Status.RETRYABLE, code, Map.of(), cause
        );
    }

    @Nonnull
    public static BreedingLitterLiveResult unknown(
            @Nonnull String code,
            @Nullable Throwable cause
    ) {
        return new BreedingLitterLiveResult(
                Status.UNKNOWN, code, Map.of(), cause
        );
    }

    @Nonnull
    public LiveOperationResult sharedResult() {
        return switch (status) {
            case CONFIRMED -> LiveOperationResult.confirmed(code);
            case RETRYABLE -> LiveOperationResult.retryable(code, cause);
            case UNKNOWN -> LiveOperationResult.unknown(code, cause);
        };
    }

    @Nonnull
    public CompletionStage<BreedingLitterLiveResult> completed() {
        return CompletableFuture.completedFuture(this);
    }

    private static String requireText(String value) {
        String normalized = Objects.requireNonNull(value, "code").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Litter result code is required");
        }
        return normalized;
    }

    public enum Status {
        CONFIRMED,
        RETRYABLE,
        UNKNOWN
    }
}
