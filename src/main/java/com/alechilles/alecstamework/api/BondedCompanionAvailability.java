package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable availability state for the separate bonded-companion authority. */
public record BondedCompanionAvailability(
        boolean available,
        @Nullable String reason
) {
    public BondedCompanionAvailability {
        reason = normalize(reason);
        if (!available && reason == null) {
            throw new IllegalArgumentException(
                    "Unavailable bonded-companion APIs require a reason."
            );
        }
    }

    @Nonnull
    public static BondedCompanionAvailability availableNow() {
        return new BondedCompanionAvailability(true, null);
    }

    @Nonnull
    public static BondedCompanionAvailability unavailable(
            @Nonnull String reason
    ) {
        return new BondedCompanionAvailability(
                false,
                Objects.requireNonNull(reason, "reason")
        );
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
