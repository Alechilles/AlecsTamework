package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.List;
import javax.annotation.Nonnull;

/** Immutable server-authored price and cooldown summary for bonded revival. */
public record BondedCompanionReviveQuote(
        @Nonnull String profileId,
        boolean enabled,
        @Nonnull List<CostLine> costs,
        long cooldownRemainingSeconds,
        long policyRevision
) {
    public BondedCompanionReviveQuote {
        profileId = requireText(profileId, "profileId");
        costs = List.copyOf(Objects.requireNonNull(costs, "costs"));
        if (cooldownRemainingSeconds < 0L
                || policyRevision < 0L) {
            throw new IllegalArgumentException(
                    "Revive quote values cannot be negative."
            );
        }
    }

    /** One ordered revive cost with server-observed owned quantity. */
    public record CostLine(
            @Nonnull String itemId,
            int requiredQuantity,
            int ownedQuantity
    ) {
        public CostLine {
            itemId = requireText(itemId, "itemId");
            if (requiredQuantity <= 0 || ownedQuantity < 0) {
                throw new IllegalArgumentException("invalid revive cost line");
            }
        }

        public boolean affordable() {
            return ownedQuantity >= requiredQuantity;
        }
    }

    /** True only when every declared cost line is affordable. */
    public boolean affordable() {
        return !costs.isEmpty() && costs.stream().allMatch(CostLine::affordable);
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }
}
