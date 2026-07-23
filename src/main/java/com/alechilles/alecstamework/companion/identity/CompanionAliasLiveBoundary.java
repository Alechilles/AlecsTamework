package com.alechilles.alecstamework.companion.identity;

import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Idempotent live-boundary resolver for a pre-leased alias.
 *
 * <p>Implementations run outside SQLite transactions. On recovery they must inspect live evidence
 * before deciding whether mutation is needed, and must return {@code UNKNOWN} when neither
 * presence nor absence can be proven.</p>
 */
@FunctionalInterface
public interface CompanionAliasLiveBoundary {
    @Nonnull
    Result applyOrResolve(
            @Nonnull CompanionAliasRotation rotation,
            @Nonnull OperationEnvelope operation
    ) throws Exception;

    /** Exact live-boundary outcome with a stable diagnostic code. */
    record Result(
            @Nonnull Status status,
            @Nonnull String code,
            @Nullable Throwable cause
    ) {
        public Result {
            if (status == null || code == null || code.isBlank()) {
                throw new IllegalArgumentException(
                        "Alias live-boundary status and code are required"
                );
            }
            code = code.trim();
            if (status == Status.CONFIRMED && cause != null) {
                throw new IllegalArgumentException(
                        "Confirmed alias live boundary cannot carry failure"
                );
            }
        }

        @Nonnull
        public static Result confirmed() {
            return new Result(Status.CONFIRMED, "alias_live_confirmed", null);
        }

        @Nonnull
        public static Result retryable(@Nonnull String code, @Nullable Throwable cause) {
            return new Result(Status.RETRYABLE, code, cause);
        }

        @Nonnull
        public static Result unknown(@Nonnull String code, @Nullable Throwable cause) {
            return new Result(Status.UNKNOWN, code, cause);
        }
    }

    enum Status {
        CONFIRMED,
        RETRYABLE,
        UNKNOWN
    }
}
