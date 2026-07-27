package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.api.BondedCompanionReviveCost;
import java.util.HashSet;
import java.util.List;
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

    /** Ordered AND recipe required for one paid bonded revival. */
    public record RevivePrice(@Nonnull List<BondedCompanionReviveCost> costs) {
        public RevivePrice {
            costs = List.copyOf(Objects.requireNonNull(costs, "costs"));
            if (costs.isEmpty()) {
                throw new IllegalArgumentException("costs must not be empty");
            }
            HashSet<String> itemIds = new HashSet<>();
            for (BondedCompanionReviveCost cost : costs) {
                if (!itemIds.add(cost.itemId())) {
                    throw new IllegalArgumentException(
                            "duplicate revive item: " + cost.itemId());
                }
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
