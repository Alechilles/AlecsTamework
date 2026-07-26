package com.alechilles.alecstamework.companion.bonded;

import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable policy resolved from one accepted bonded-roster asset generation. */
public record BondedCompanionPolicy(
        long revision,
        @Nonnull String rosterId,
        @Nonnull String familyId,
        @Nonnull Set<String> allowedRoles,
        int maximumOwned,
        int maximumActive,
        long sessionDurationSeconds,
        long summonCooldownSeconds,
        @Nullable RevivePrice revivePrice,
        @Nonnull FeatureFlags features
) {
    public BondedCompanionPolicy {
        rosterId = text(rosterId, "rosterId");
        familyId = text(familyId, "familyId");
        allowedRoles = Set.copyOf(Objects.requireNonNull(
                allowedRoles, "allowedRoles"
        ));
        features = Objects.requireNonNull(features, "features");
        if (revision < 0L || maximumOwned < 0 || maximumActive < 0
                || sessionDurationSeconds < 0L
                || summonCooldownSeconds < 0L) {
            throw new IllegalArgumentException("negative bonded policy value");
        }
    }

    /** Exact item price required for a paid revival. */
    public record RevivePrice(@Nonnull String itemId, int quantity) {
        public RevivePrice {
            itemId = text(itemId, "itemId");
            if (quantity <= 0) {
                throw new IllegalArgumentException("quantity must be positive");
            }
        }
    }

    /** Per-action policy switches copied from the resolved roster asset. */
    public record FeatureFlags(
            boolean capture,
            boolean provision,
            boolean summon,
            boolean dismiss,
            boolean revive
    ) {
    }

    private static String text(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
