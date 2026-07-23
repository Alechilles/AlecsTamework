package com.alechilles.alecstamework.companion.identity;

import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Stable logical identity of one companion across runtime NPC UUID changes.
 *
 * @param value canonical UUID
 */
public record ProfileId(@Nonnull UUID value) {
    public ProfileId {
        if (value == null) {
            throw new IllegalArgumentException("Profile ID is required");
        }
    }

    /** Parses the canonical database/API representation. */
    @Nonnull
    public static ProfileId parse(@Nonnull String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Profile ID is required");
        }
        return new ProfileId(UUID.fromString(value.trim()));
    }

    /** Returns the lowercase canonical UUID representation. */
    @Override
    @Nonnull
    public String toString() {
        return value.toString();
    }
}
