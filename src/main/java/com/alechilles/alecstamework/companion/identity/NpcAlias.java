package com.alechilles.alecstamework.companion.identity;

import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Runtime NPC UUID that may be leased, current, or historical for a stable profile.
 *
 * @param value runtime entity UUID
 */
public record NpcAlias(@Nonnull UUID value) {
    public NpcAlias {
        if (value == null) {
            throw new IllegalArgumentException("NPC alias is required");
        }
    }

    /** Parses the canonical database/API representation. */
    @Nonnull
    public static NpcAlias parse(@Nonnull String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("NPC alias is required");
        }
        return new NpcAlias(UUID.fromString(value.trim()));
    }

    /** Returns the lowercase canonical UUID representation. */
    @Override
    @Nonnull
    public String toString() {
        return value.toString();
    }
}
