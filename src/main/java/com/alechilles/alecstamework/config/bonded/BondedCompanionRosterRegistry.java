package com.alechilles.alecstamework.config.bonded;

import com.alechilles.alecstamework.config.assets.TwBondedCompanionRosterConfig;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Atomically retains the last valid immutable bonded-roster policy snapshot. */
public final class BondedCompanionRosterRegistry {
    private final AtomicReference<Snapshot> current =
            new AtomicReference<>(Snapshot.empty());

    @Nonnull
    public Snapshot snapshot() {
        return current.get();
    }

    @Nonnull
    public Optional<RosterDefinition> resolve(@Nullable String rosterId) {
        return rosterId == null
                ? Optional.empty()
                : Optional.ofNullable(
                        current.get().byRosterId().get(rosterId.trim())
                );
    }

    @Nonnull
    public ReloadResult replace(
            @Nonnull Collection<TwBondedCompanionRosterConfig> configs,
            long revision
    ) {
        Objects.requireNonNull(configs, "configs");
        try {
            Snapshot replacement = compile(configs, revision);
            current.set(replacement);
            return new ReloadResult(true, replacement, null);
        } catch (RuntimeException invalid) {
            return new ReloadResult(false, current.get(), invalid.getMessage());
        }
    }

    private static Snapshot compile(
            Collection<TwBondedCompanionRosterConfig> configs,
            long revision
    ) {
        if (revision < 0L) {
            throw new IllegalArgumentException(
                    "Bonded-roster revision cannot be negative."
            );
        }
        LinkedHashMap<String, RosterDefinition> definitions =
                new LinkedHashMap<>();
        LinkedHashSet<String> assetIds = new LinkedHashSet<>();
        for (TwBondedCompanionRosterConfig config : configs) {
            if (config == null) {
                continue;
            }
            config.validateOrThrow();
            if (!assetIds.add(config.getId())) {
                throw new IllegalArgumentException(
                        "Duplicate bonded-roster asset ID: " + config.getId()
                );
            }
            RosterDefinition definition = definition(config);
            if (definitions.putIfAbsent(
                    definition.rosterId(),
                    definition
            ) != null) {
                throw new IllegalArgumentException(
                        "Duplicate bonded RosterId: "
                                + definition.rosterId()
                );
            }
        }
        return new Snapshot(revision, definitions);
    }

    private static RosterDefinition definition(
            TwBondedCompanionRosterConfig config
    ) {
        TwBondedCompanionRosterConfig.RevivePriceDefinition configuredPrice =
                config.getRevivePrice();
        RevivePrice revivePrice = configuredPrice == null
                ? null
                : new RevivePrice(
                        configuredPrice.getItemId(),
                        configuredPrice.getQuantity()
                );
        TwBondedCompanionRosterConfig.FeatureToggles configuredFeatures =
                config.getFeatures();
        return new RosterDefinition(
                config.getId(),
                config.getPriority(),
                config.getRosterId(),
                config.getFamilyId(),
                Set.of(config.getAllowedRoles()),
                config.getMaximumOwned(),
                config.getMaximumActive(),
                config.getSessionDurationSeconds(),
                config.getSummonCooldownSeconds(),
                revivePrice,
                new FeatureFlags(
                        configuredFeatures.isCapture(),
                        configuredFeatures.isProvision(),
                        configuredFeatures.isSummon(),
                        configuredFeatures.isDismiss(),
                        configuredFeatures.isRevive()
                )
        );
    }

    /** Immutable resolver snapshot for one accepted asset revision. */
    public record Snapshot(
            long revision,
            @Nonnull Map<String, RosterDefinition> byRosterId
    ) {
        public Snapshot {
            if (revision < 0L) {
                throw new IllegalArgumentException(
                        "Bonded-roster revision cannot be negative."
                );
            }
            byRosterId = Map.copyOf(Objects.requireNonNull(
                    byRosterId,
                    "byRosterId"
            ));
        }

        static Snapshot empty() {
            return new Snapshot(0L, Map.of());
        }
    }

    /** Immutable compiled definition consumed by bonded runtime code. */
    public record RosterDefinition(
            @Nonnull String configId,
            int priority,
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
        public RosterDefinition {
            configId = Objects.requireNonNull(configId, "configId");
            rosterId = Objects.requireNonNull(rosterId, "rosterId");
            familyId = Objects.requireNonNull(familyId, "familyId");
            allowedRoles = Set.copyOf(Objects.requireNonNull(
                    allowedRoles,
                    "allowedRoles"
            ));
            features = Objects.requireNonNull(features, "features");
        }
    }

    /** Immutable optional revive price in one roster definition. */
    public record RevivePrice(@Nonnull String itemId, int quantity) {
        public RevivePrice {
            itemId = Objects.requireNonNull(itemId, "itemId");
            if (quantity <= 0) {
                throw new IllegalArgumentException(
                        "Revive price quantity must be positive."
                );
            }
        }
    }

    /** Immutable feature policy compiled from one roster asset. */
    public record FeatureFlags(
            boolean capture,
            boolean provision,
            boolean summon,
            boolean dismiss,
            boolean revive
    ) {
    }

    /** Result of one atomic bonded-roster replacement attempt. */
    public record ReloadResult(
            boolean applied,
            @Nonnull Snapshot active,
            @Nullable String error
    ) {
        public ReloadResult {
            active = Objects.requireNonNull(active, "active");
            if (!applied && (error == null || error.isBlank())) {
                error = "bonded-roster-index-invalid";
            }
        }
    }
}
