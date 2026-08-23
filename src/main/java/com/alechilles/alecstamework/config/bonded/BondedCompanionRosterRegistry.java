package com.alechilles.alecstamework.config.bonded;

import com.alechilles.alecstamework.api.BondedCompanionReviveCost;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.assets.TwBondedCompanionRosterConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwItemCostComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
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
        return current.get().rosters().resolveUnique(rosterId);
    }

    /** Resolves one exact policy family within a player-facing roster. */
    @Nonnull
    public Optional<RosterDefinition> resolve(
            @Nullable String rosterId,
            @Nullable String familyId
    ) {
        return current.get().rosters().resolve(rosterId, familyId);
    }

    /** Resolves a role only when exactly one family in the roster allows it. */
    @Nonnull
    public FamilyResolution resolveForRole(
            @Nullable String rosterId,
            @Nullable String roleId
    ) {
        return current.get().rosters().resolveForRole(rosterId, roleId);
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
        TreeMap<String, TreeMap<String, RosterDefinition>> definitions =
                new TreeMap<>();
        LinkedHashSet<String> assetIds = new LinkedHashSet<>();
        ArrayList<TwBondedCompanionRosterConfig> ordered = new ArrayList<>();
        for (TwBondedCompanionRosterConfig config : configs) {
            if (config == null) {
                continue;
            }
            config.validateOrThrow();
            ordered.add(config);
        }
        ordered.sort(Comparator
                .comparing(TwBondedCompanionRosterConfig::getId)
                .thenComparing(TwBondedCompanionRosterConfig::getRosterId)
                .thenComparing(TwBondedCompanionRosterConfig::getFamilyId));
        for (TwBondedCompanionRosterConfig config : ordered) {
            if (!assetIds.add(config.getId())) {
                throw new IllegalArgumentException(
                        "Duplicate bonded-roster asset ID: " + config.getId()
                );
            }
            RosterDefinition definition = definition(config);
            Map<String, RosterDefinition> families = definitions.computeIfAbsent(
                    definition.rosterId(),
                    ignored -> new TreeMap<>()
            );
            if (families.putIfAbsent(definition.familyId(), definition) != null) {
                throw new IllegalArgumentException(
                        "Duplicate bonded roster family: "
                                + definition.rosterId() + "/"
                                + definition.familyId()
                );
            }
        }
        return snapshot(revision, definitions);
    }

    private static Snapshot snapshot(
            long revision,
            Map<String, ? extends Map<String, RosterDefinition>> definitions
    ) {
        LinkedHashMap<String, RosterDefinition> representatives =
                new LinkedHashMap<>();
        LinkedHashMap<String, Map<String, RosterDefinition>> families =
                new LinkedHashMap<>();
        for (Map.Entry<String, ? extends Map<String, RosterDefinition>> entry
                : definitions.entrySet()) {
            LinkedHashMap<String, RosterDefinition> ordered =
                    new LinkedHashMap<>(entry.getValue());
            families.put(entry.getKey(), ordered);
            if (ordered.size() == 1) {
                representatives.put(
                        entry.getKey(), ordered.values().iterator().next()
                );
            }
        }
        return new Snapshot(revision, representatives, families);
    }

    private static RosterDefinition definition(
            TwBondedCompanionRosterConfig config
    ) {
        TwBondedCompanionRosterConfig.RevivePriceDefinition configuredPrice =
                config.getRevivePrice();
        LinkedHashMap<String, RevivePrice> rolePrices = new LinkedHashMap<>();
        for (TwBondedCompanionRosterConfig.RoleRevivePriceDefinition entry
                : config.getRevivePriceByRole()) {
            rolePrices.put(entry.getRoleId(), revivePrice(entry.getCosts()));
        }
        RevivePrice revivePrice = configuredPrice == null && rolePrices.isEmpty()
                ? null : new RevivePrice(
                        configuredPrice == null ? List.of() : revivePrice(configuredPrice.getCosts()).costs(),
                        rolePrices);
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
                config.getReviveCooldownSeconds(),
                config.getSummonAuraEffectId(),
                config.getExpiryWarningEffectId(),
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

    private static RevivePrice revivePrice(TwItemCostComponent[] configured) {
        ArrayList<BondedCompanionReviveCost> costs = new ArrayList<>(
                configured.length);
        for (TwItemCostComponent cost : configured) {
            costs.add(new BondedCompanionReviveCost(
                    cost.getItemId(), cost.getQuantity()));
        }
        return new RevivePrice(costs);
    }

    private static void validateDependentCommands(
            Snapshot rosters,
            CommandItemRegistry.Snapshot commands
    ) {
        for (TwCommandItemConfig config : commands.byItemId().values()) {
            if (config != null && config.usesBondedCompanionRoster()
                    && !rosters.containsRoster(config.getBondedRosterId())) {
                throw new IllegalArgumentException(
                        "Unknown bonded roster: " + config.getBondedRosterId()
                );
            }
        }
    }

    /** Immutable resolver snapshot for one accepted asset revision. */
    public record Snapshot(
            long revision,
            /** Legacy fail-closed view containing only single-family rosters. */
            @Nonnull Map<String, RosterDefinition> byRosterId,
            @Nonnull Map<String, Map<String, RosterDefinition>> familiesByRosterId
    ) {
        public Snapshot {
            if (revision < 0L) {
                throw new IllegalArgumentException(
                        "Bonded-roster revision cannot be negative."
                );
            }
            var canonical = BondedCompanionRosterSnapshotCanonicalizer
                    .canonicalize(byRosterId, familiesByRosterId);
            byRosterId = canonical.representatives();
            familiesByRosterId = canonical.families();
        }

        /** Preserves the source contract for snapshots constructed by tests. */
        public Snapshot(
                long revision,
                @Nonnull Map<String, RosterDefinition> byRosterId
        ) {
            this(
                    revision,
                    byRosterId,
                    BondedCompanionRosterSnapshotCanonicalizer
                            .singletonFamilies(byRosterId)
            );
        }

        /** Returns all independently configured families for one roster. */
        @Nonnull
        public List<RosterDefinition> families(@Nullable String rosterId) {
            if (rosterId == null) {
                return List.of();
            }
            Map<String, RosterDefinition> families =
                    familiesByRosterId.get(rosterId.trim());
            return families == null ? List.of() : List.copyOf(families.values());
        }

        public boolean containsRoster(@Nullable String rosterId) {
            return rosterId != null
                    && familiesByRosterId.containsKey(rosterId.trim());
        }

        /** Returns every roster ID, including multi-family rosters. */
        @Nonnull public Set<String> rosterIds() {
            return familiesByRosterId.keySet();
        }

        /** Returns the number of logical rosters, not configured families. */
        public int rosterCount() { return familiesByRosterId.size(); }

        @Nonnull
        public Optional<RosterDefinition> resolveUnique(@Nullable String rosterId) {
            List<RosterDefinition> families = families(rosterId);
            return families.size() == 1
                    ? Optional.of(families.getFirst())
                    : Optional.empty();
        }

        @Nonnull
        public Optional<RosterDefinition> resolve(
                @Nullable String rosterId,
                @Nullable String familyId
        ) {
            if (rosterId == null || familyId == null) {
                return Optional.empty();
            }
            Map<String, RosterDefinition> families =
                    familiesByRosterId.get(rosterId.trim());
            return families == null
                    ? Optional.empty()
                    : Optional.ofNullable(families.get(familyId.trim()));
        }

        @Nonnull
        public FamilyResolution resolveForRole(
                @Nullable String rosterId,
                @Nullable String roleId
        ) {
            if (roleId == null || roleId.isBlank()) {
                return FamilyResolution.notFound();
            }
            RosterDefinition match = null;
            for (RosterDefinition family : families(rosterId)) {
                if (!family.allowedRoles().contains(roleId.trim())) {
                    continue;
                }
                if (match != null) {
                    return FamilyResolution.ambiguous();
                }
                match = family;
            }
            return match == null
                    ? FamilyResolution.notFound()
                    : FamilyResolution.found(match);
        }

        static Snapshot empty() {
            return new Snapshot(0L, Map.of(), Map.of());
        }
    }

    /** Outcome of selecting a family from a role when no family was supplied. */
    public record FamilyResolution(
            @Nonnull FamilyResolutionStatus status,
            @Nullable RosterDefinition definition
    ) {
        public FamilyResolution {
            status = Objects.requireNonNull(status, "status");
            if ((status == FamilyResolutionStatus.FOUND) != (definition != null)) {
                throw new IllegalArgumentException(
                        "Only a found family resolution carries a definition."
                );
            }
        }

        static FamilyResolution found(RosterDefinition definition) {
            return new FamilyResolution(FamilyResolutionStatus.FOUND, definition);
        }

        static FamilyResolution notFound() {
            return new FamilyResolution(FamilyResolutionStatus.NOT_FOUND, null);
        }

        static FamilyResolution ambiguous() {
            return new FamilyResolution(FamilyResolutionStatus.AMBIGUOUS, null);
        }
    }

    /** Stable role-to-family selection result. */
    public enum FamilyResolutionStatus {
        FOUND,
        NOT_FOUND,
        AMBIGUOUS
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
            long reviveCooldownSeconds,
            @Nullable String summonAuraEffectId,
            @Nullable String expiryWarningEffectId,
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
            if (reviveCooldownSeconds < 0L) {
                throw new IllegalArgumentException("revive cooldown cannot be negative");
            }
            summonAuraEffectId = summonAuraEffectId == null
                    || summonAuraEffectId.isBlank()
                    ? null : summonAuraEffectId.trim();
            expiryWarningEffectId = expiryWarningEffectId == null
                    || expiryWarningEffectId.isBlank()
                    ? null : expiryWarningEffectId.trim();
        }

        public RosterDefinition(
                String configId, int priority, String rosterId, String familyId,
                Set<String> allowedRoles, int maximumOwned, int maximumActive,
                long sessionDurationSeconds, long summonCooldownSeconds,
                @Nullable RevivePrice revivePrice, FeatureFlags features
        ) {
            this(configId, priority, rosterId, familyId, allowedRoles,
                    maximumOwned, maximumActive, sessionDurationSeconds,
                    summonCooldownSeconds, 0L, null, null, revivePrice, features);
        }
    }

    /** Immutable optional ordered revive recipe in one roster definition. */
    public record RevivePrice(@Nonnull List<BondedCompanionReviveCost> costs,
                              @Nonnull Map<String, RevivePrice> byRole) {
        public RevivePrice {
            costs = List.copyOf(Objects.requireNonNull(costs, "costs"));
            byRole = Map.copyOf(Objects.requireNonNull(byRole, "byRole"));
            if (costs.isEmpty() && byRole.isEmpty()) {
                throw new IllegalArgumentException(
                        "Revive price requires a fallback or role-specific cost."
                );
            }
        }

        public RevivePrice(@Nonnull List<BondedCompanionReviveCost> costs) {
            this(costs, Map.of());
        }

        @Nullable
        public RevivePrice forRole(@Nonnull String roleId) {
            RevivePrice rolePrice = byRole.get(Objects.requireNonNull(roleId, "roleId"));
            return rolePrice != null ? rolePrice : costs.isEmpty() ? null : new RevivePrice(costs);
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
