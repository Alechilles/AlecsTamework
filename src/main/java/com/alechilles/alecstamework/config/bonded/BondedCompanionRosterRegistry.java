package com.alechilles.alecstamework.config.bonded;

import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.assets.TwBondedCompanionRosterConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
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
    private final AtomicReference<CoherentSnapshot> current =
            new AtomicReference<>(CoherentSnapshot.empty());

    @Nonnull
    public Snapshot snapshot() {
        return current.get().rosters();
    }

    @Nonnull
    public Optional<RosterDefinition> resolve(@Nullable String rosterId) {
        return rosterId == null
                ? Optional.empty()
                : Optional.ofNullable(
                        current.get().rosters().byRosterId().get(rosterId.trim())
                );
    }

    /** Captures roster and dependent-command lookups from one publication. */
    @Nonnull
    public CoherentSnapshot coherentSnapshot() {
        return current.get();
    }

    @Nonnull
    public synchronized ReloadResult replace(
            @Nonnull Collection<TwBondedCompanionRosterConfig> configs,
            long revision
    ) {
        Objects.requireNonNull(configs, "configs");
        try {
            PreparedReplacement replacement =
                    prepareReplacement(configs, revision);
            validateDependentCommands(
                    replacement.candidate(),
                    replacement.base().commands()
            );
            publishPrepared(replacement);
            return new ReloadResult(true, replacement.candidate(), null);
        } catch (RuntimeException invalid) {
            return new ReloadResult(
                    false,
                    current.get().rosters(),
                    invalid.getMessage()
            );
        }
    }

    synchronized PreparedReplacement prepareReplacement(
            Collection<TwBondedCompanionRosterConfig> configs,
            long revision
    ) {
        return new PreparedReplacement(current.get(), compile(configs, revision));
    }

    synchronized boolean publishPrepared(PreparedReplacement replacement) {
        Objects.requireNonNull(replacement, "replacement");
        return publishCoherent(replacement, replacement.base().commands());
    }

    synchronized boolean publishCoherent(
            PreparedReplacement replacement,
            CommandItemRegistry.Snapshot commands
    ) {
        Objects.requireNonNull(replacement, "replacement");
        Objects.requireNonNull(commands, "commands");
        return current.compareAndSet(
                replacement.base(),
                new CoherentSnapshot(replacement.candidate(), commands)
        );
    }

    /** Atomically replaces only the command half while retaining active rosters. */
    public synchronized boolean publishCommands(
            CommandItemRegistry.Snapshot base,
            CommandItemRegistry.Snapshot candidate
    ) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(candidate, "candidate");
        CoherentSnapshot active = current.get();
        if (active.commands() != base) {
            return false;
        }
        validateDependentCommands(active.rosters(), candidate);
        return current.compareAndSet(
                active,
                new CoherentSnapshot(active.rosters(), candidate)
        );
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
        LinkedHashSet<String> allowedRoles = new LinkedHashSet<>();
        for (String allowedRole : config.getAllowedRoles()) {
            allowedRoles.add(allowedRole.trim());
        }
        return new RosterDefinition(
                config.getId(),
                config.getPriority(),
                config.getRosterId(),
                config.getFamilyId(),
                allowedRoles,
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

    private static void validateDependentCommands(
            Snapshot rosters,
            CommandItemRegistry.Snapshot commands
    ) {
        for (TwCommandItemConfig config : commands.byItemId().values()) {
            if (config != null && config.usesBondedCompanionRoster()
                    && !rosters.byRosterId().containsKey(
                            config.getBondedRosterId()
                    )) {
                throw new IllegalArgumentException(
                        "Unknown bonded roster: " + config.getBondedRosterId()
                );
            }
        }
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

    /** Immutable roster and command lookup boundary from one atomic generation. */
    public record CoherentSnapshot(
            @Nonnull Snapshot rosters,
            @Nonnull CommandItemRegistry.Snapshot commands
    ) {
        public CoherentSnapshot {
            rosters = Objects.requireNonNull(rosters, "rosters");
            commands = Objects.requireNonNull(commands, "commands");
        }

        private static CoherentSnapshot empty() {
            return new CoherentSnapshot(
                    Snapshot.empty(),
                    CommandItemRegistry.Snapshot.empty()
            );
        }
    }

    record PreparedReplacement(CoherentSnapshot base, Snapshot candidate) {
        PreparedReplacement {
            base = Objects.requireNonNull(base, "base");
            candidate = Objects.requireNonNull(candidate, "candidate");
        }
    }
}
