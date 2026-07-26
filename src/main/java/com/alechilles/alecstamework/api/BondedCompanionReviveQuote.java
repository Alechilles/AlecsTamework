package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable server-authored price and cooldown summary for bonded revival. */
public record BondedCompanionReviveQuote(
        @Nonnull String profileId,
        boolean enabled,
        @Nullable String itemId,
        int quantity,
        boolean affordable,
        long cooldownRemainingSeconds,
        long policyRevision
) {
    public BondedCompanionReviveQuote {
        profileId = requireText(profileId, "profileId");
        itemId = itemId == null || itemId.isBlank() ? null : itemId.trim();
        if (quantity < 0 || cooldownRemainingSeconds < 0L
                || policyRevision < 0L) {
            throw new IllegalArgumentException(
                    "Revive quote values cannot be negative."
            );
        }
        if ((itemId == null) != (quantity == 0)) {
            throw new IllegalArgumentException(
                    "Revive price item and quantity must be present together."
            );
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }
}
