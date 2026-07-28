package com.alechilles.alecstamework.persistence.operation;

import javax.annotation.Nonnull;

/**
 * Stable caller-derived key that deduplicates one operation kind.
 *
 * @param value opaque durable key
 */
public record IdempotencyKey(@Nonnull String value) {
    public static final int MAX_LENGTH = 200;

    public IdempotencyKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Idempotency key is required");
        }
        value = value.trim();
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Idempotency key exceeds " + MAX_LENGTH + " characters");
        }
    }

    /** Returns the durable representation. */
    @Override
    @Nonnull
    public String toString() {
        return value;
    }
}
