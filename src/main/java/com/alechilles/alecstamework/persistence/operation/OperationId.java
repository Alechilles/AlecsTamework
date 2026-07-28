package com.alechilles.alecstamework.persistence.operation;

import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Globally unique identity for one durable persistence operation.
 *
 * @param value operation UUID
 */
public record OperationId(@Nonnull UUID value) {
    public OperationId {
        if (value == null) {
            throw new IllegalArgumentException("Operation ID is required");
        }
    }

    /** Creates a new operation identity. */
    public static OperationId create() {
        return new OperationId(UUID.randomUUID());
    }

    /** Parses the canonical database/API representation. */
    public static OperationId parse(@Nonnull String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Operation ID is required");
        }
        return new OperationId(UUID.fromString(value.trim()));
    }

    /** Returns the lowercase canonical UUID representation. */
    @Override
    @Nonnull
    public String toString() {
        return value.toString();
    }
}
