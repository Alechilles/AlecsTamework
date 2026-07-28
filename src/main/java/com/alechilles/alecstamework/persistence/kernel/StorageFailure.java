package com.alechilles.alecstamework.persistence.kernel;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Driver-neutral failure details returned across replacement persistence boundaries.
 *
 * @param kind stable failure category
 * @param code stable machine-readable reason code
 * @param operation logical storage operation that failed
 * @param retryable whether retry may succeed without repair or operator intervention
 * @param cause original failure for diagnostics; never required for control flow
 */
public record StorageFailure(@Nonnull StorageFailureKind kind,
                             @Nonnull String code,
                             @Nonnull String operation,
                             boolean retryable,
                             @Nullable Throwable cause) {
    public StorageFailure {
        if (kind == null) {
            throw new IllegalArgumentException("Storage failure kind is required");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Storage failure code is required");
        }
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("Storage failure operation is required");
        }
    }
}
