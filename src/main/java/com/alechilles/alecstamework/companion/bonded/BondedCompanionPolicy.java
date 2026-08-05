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
        @Nullable String summonAuraEffectId,
        @Nullable String expiryWarningEffectId,
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
        summonAuraEffectId = summonAuraEffectId == null
                || summonAuraEffectId.isBlank()
                ? null : summonAuraEffectId.trim();
        expiryWarningEffectId = expiryWarningEffectId == null
                || expiryWarningEffectId.isBlank()
                ? null : expiryWarningEffectId.trim();
        if (revision < 0L || maximumOwned < 0 || maximumActive < 0
                || sessionDurationSeconds < 0L
                || summonCooldownSeconds < 0L) {
            throw new IllegalArgumentException("negative bonded policy value");
        }
    }

    public BondedCompanionPolicy(
            long revision, String rosterId, String familyId,
            Set<String> allowedRoles, int maximumOwned, int maximumActive,
            long sessionDurationSeconds, long summonCooldownSeconds,
            @Nullable String summonAuraEffectId,
            @Nullable RevivePrice revivePrice, FeatureFlags features
    ) {
        this(revision, rosterId, familyId, allowedRoles, maximumOwned,
                maximumActive, sessionDurationSeconds, summonCooldownSeconds,
                summonAuraEffectId, null, revivePrice, features);
    }

    public BondedCompanionPolicy(
            long revision, String rosterId, String familyId,
            Set<String> allowedRoles, int maximumOwned, int maximumActive,
            long sessionDurationSeconds, long summonCooldownSeconds,
            @Nullable RevivePrice revivePrice, FeatureFlags features
    ) {
        this(revision, rosterId, familyId, allowedRoles, maximumOwned,
                maximumActive, sessionDurationSeconds, summonCooldownSeconds,
                null, null, revivePrice, features);
    }

    @Nullable
    public RevivePrice revivePriceFor(@Nonnull String roleId) {
        return revivePrice == null ? null : revivePrice.forRole(roleId);
    }

    /** Ordered AND recipe required for one paid bonded revival. */
    public record RevivePrice(@Nonnull List<BondedCompanionReviveCost> costs,
                              @Nonnull java.util.Map<String, RevivePrice> byRole) {
        public RevivePrice {
            costs = List.copyOf(Objects.requireNonNull(costs, "costs"));
            byRole = java.util.Map.copyOf(Objects.requireNonNull(byRole, "byRole"));
            if (costs.isEmpty() && byRole.isEmpty()) {
                throw new IllegalArgumentException("revive price requires a fallback or role-specific cost");
            }
            HashSet<String> itemIds = new HashSet<>();
            for (BondedCompanionReviveCost cost : costs) {
                if (!itemIds.add(cost.itemId())) {
                    throw new IllegalArgumentException(
                            "duplicate revive item: " + cost.itemId());
                }
            }
        }

        public RevivePrice(@Nonnull List<BondedCompanionReviveCost> costs) {
            this(costs, java.util.Map.of());
        }

        @Nullable
        public RevivePrice forRole(@Nonnull String roleId) {
            RevivePrice rolePrice = byRole.get(Objects.requireNonNull(roleId, "roleId"));
            return rolePrice != null ? rolePrice : costs.isEmpty() ? null : new RevivePrice(costs);
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
