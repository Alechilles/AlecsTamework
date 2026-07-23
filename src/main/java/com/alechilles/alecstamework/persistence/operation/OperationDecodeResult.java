package com.alechilles.alecstamework.persistence.operation;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Explicit result of decoding a durable operation payload for recovery. */
public sealed interface OperationDecodeResult<T>
        permits OperationDecodeResult.Decoded, OperationDecodeResult.Failed {
    /** Successfully decoded immutable operation payload. */
    record Decoded<T>(@Nonnull T value) implements OperationDecodeResult<T> {
        public Decoded {
            if (value == null) {
                throw new IllegalArgumentException("Decoded operation payload is required");
            }
        }
    }

    /** Recovery-blocking decode failure that must be quarantined rather than skipped. */
    record Failed<T>(@Nonnull Failure failure,
                     @Nonnull String code,
                     @Nullable Throwable cause) implements OperationDecodeResult<T> {
        public Failed {
            if (failure == null || code == null || code.isBlank()) {
                throw new IllegalArgumentException("Operation decode failure and code are required");
            }
            code = code.trim();
        }
    }

    /** Stable operation payload failure classes. */
    enum Failure {
        UNKNOWN_DEFINITION,
        UNSUPPORTED_VERSION,
        TYPE_MISMATCH,
        DECODE_FAILED
    }
}
