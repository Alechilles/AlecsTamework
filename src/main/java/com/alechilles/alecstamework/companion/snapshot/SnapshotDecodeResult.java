package com.alechilles.alecstamework.companion.snapshot;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Explicit result of decoding durable snapshot evidence. */
public sealed interface SnapshotDecodeResult<T>
        permits SnapshotDecodeResult.Decoded, SnapshotDecodeResult.Failed {
    /** Successfully decoded immutable value. */
    record Decoded<T>(@Nonnull T value) implements SnapshotDecodeResult<T> {
        public Decoded {
            if (value == null) {
                throw new IllegalArgumentException("Decoded snapshot value is required");
            }
        }
    }

    /** Decode failure that must never be interpreted as snapshot absence. */
    record Failed<T>(@Nonnull Failure failure,
                     @Nonnull String code,
                     @Nullable Throwable cause) implements SnapshotDecodeResult<T> {
        public Failed {
            if (failure == null || code == null || code.isBlank()) {
                throw new IllegalArgumentException("Snapshot decode failure and code are required");
            }
            code = code.trim();
        }
    }

    /** Stable decode failure classes. */
    enum Failure {
        HASH_MISMATCH,
        UNSUPPORTED_CODEC,
        TYPE_MISMATCH,
        DECODE_FAILED
    }
}
