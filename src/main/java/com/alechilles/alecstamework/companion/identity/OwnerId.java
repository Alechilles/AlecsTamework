package com.alechilles.alecstamework.companion.identity;

import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Stable player identity used by companion ownership boundaries.
 *
 * @param value player UUID
 */
public record OwnerId(@Nonnull UUID value) {
    public OwnerId {
        if (value == null) {
            throw new IllegalArgumentException("Owner ID is required");
        }
    }

    /** Parses the canonical database/API representation. */
    @Nonnull
    public static OwnerId parse(@Nonnull String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Owner ID is required");
        }
        return new OwnerId(UUID.fromString(value.trim()));
    }

    /** Returns the lowercase canonical UUID representation. */
    @Override
    @Nonnull
    public String toString() {
        return value.toString();
    }
}
