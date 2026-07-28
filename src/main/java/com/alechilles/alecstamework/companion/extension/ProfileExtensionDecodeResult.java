package com.alechilles.alecstamework.companion.extension;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Explicit extension JSON decode result; failure can never be interpreted as absence. */
public sealed interface ProfileExtensionDecodeResult
        permits ProfileExtensionDecodeResult.Decoded, ProfileExtensionDecodeResult.Failed {
    /** Successfully validated exact JSON payload. */
    record Decoded(@Nonnull String jsonPayload) implements ProfileExtensionDecodeResult {
        public Decoded {
            if (jsonPayload == null) {
                throw new IllegalArgumentException("Decoded extension JSON is required");
            }
        }
    }

    /** Stable decode failure with optional diagnostic cause. */
    record Failed(@Nonnull Failure failure,
                  @Nonnull String code,
                  @Nullable Throwable cause) implements ProfileExtensionDecodeResult {
        public Failed {
            if (failure == null || code == null || code.isBlank()) {
                throw new IllegalArgumentException("Extension decode failure and code are required");
            }
            code = code.trim();
        }
    }

    /** Failure classes callers may handle without inspecting exception text. */
    enum Failure {
        HASH_MISMATCH,
        UNSUPPORTED_VERSION,
        INVALID_JSON
    }
}
