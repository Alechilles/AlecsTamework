package com.alechilles.alecstamework.config;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Registry for command item configs keyed by item id.
 */
public final class CommandItemRegistry {
    private final BondedCompanionRosterRegistry bondedRosters;
    private volatile Snapshot state = Snapshot.empty();

    public CommandItemRegistry() {
        this(null);
    }

    public CommandItemRegistry(BondedCompanionRosterRegistry bondedRosters) {
        this.bondedRosters = bondedRosters;
    }

    public void register(String itemId, TwCommandItemConfig config) {
        register(config == null ? null : config.getId(), itemId, config);
    }

    public synchronized void register(
            String configId,
            String itemId,
            TwCommandItemConfig config
    ) {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(config, "config");
        Snapshot active = activeSnapshot();
        HashMap<String, TwCommandItemConfig> byItem =
                new HashMap<>(active.byItemId());
        HashMap<String, TwCommandItemConfig> byId =
                new HashMap<>(active.byConfigId());
        validateOwnerFamily(itemId, config, byItem);
        validateBondedRoster(config, null);
        byItem.put(itemId, config);
        if (configId != null && !configId.isBlank()) {
            byId.put(configId.trim(), config);
        }
        publishOrThrow(active, new Snapshot(
                byItem,
                byId,
                Math.addExact(active.revision(), 1L)
        ));
    }

    public TwCommandItemConfig get(String itemId) {
        if (itemId == null) {
            return null;
        }
        Snapshot active = activeSnapshot();
        TwCommandItemConfig config = active.byItemId().get(itemId);
        if (config != null) {
            return config;
        }
        String normalized = ItemFeatureRegistry.normalizeStateItemId(itemId);
        if (normalized != null && !normalized.equals(itemId)) {
            return active.byItemId().get(normalized);
        }
        return null;
    }

    public Map<String, TwCommandItemConfig> snapshot() {
        return activeSnapshot().byItemId();
    }

    public TwCommandItemConfig getByConfigId(String configId) {
        return configId == null
                ? null
                : activeSnapshot().byConfigId().get(configId.trim());
    }

    public long revision() {
        return activeSnapshot().revision();
    }

    /**
     * Validates immutable config/family/item/role access evidence.
     *
     * <p>Physical inventory possession remains a caller-owned world-thread fence.</p>
     */
    public String validateOwnerFamilyAccess(
            String familyId,
            String configId,
            String accessItemId,
            String profileRoleId
    ) {
        if (configId == null || configId.isBlank()) {
            return "command-config-required";
        }
        TwCommandItemConfig config = getByConfigId(configId);
        if (config == null || !config.isEnabled()) {
            return "command-config-unavailable";
        }
        if (!config.usesOwnerCommandFamilyRoster()) {
            return "command-config-not-owner-family";
        }
        if (!Objects.equals(config.getCommandFamilyId(), familyId)) {
            return "command-family-mismatch";
        }
        if (accessItemId != null && get(accessItemId) != config) {
            return "command-access-item-mismatch";
        }
        TwCommandItemConfig.AllowedRoles allowed = config.getAllowedRoles();
        if (allowed == null || allowed.getMode()
                == TwCommandItemConfig.RoleFilterMode.AllowAll) {
            return null;
        }
        String[] roles = allowed.getMode()
                == TwCommandItemConfig.RoleFilterMode.Allowlist
                ? allowed.getAllowlist()
                : allowed.getDenylist();
        boolean listed = profileRoleId != null && Arrays.stream(roles)
                .filter(Objects::nonNull)
                .map(String::trim)
                .anyMatch(profileRoleId::equals);
        if (allowed.getMode()
                == TwCommandItemConfig.RoleFilterMode.Allowlist) {
            return listed ? null : "profile-role-not-allowed";
        }
        return listed ? "profile-role-denied" : null;
    }

    public synchronized void clear() {
        Snapshot active = activeSnapshot();
        publishOrThrow(active, new Snapshot(
                Map.of(),
                Map.of(),
                Math.addExact(active.revision(), 1L)
        ));
    }

    /** Compiles a complete command generation without changing active lookups. */
    public synchronized PreparedReplacement prepareReplacement(
            Collection<TwCommandItemConfig> configs,
            BondedCompanionRosterRegistry.Snapshot bondedSnapshot
    ) {
        Objects.requireNonNull(configs, "configs");
        Objects.requireNonNull(bondedSnapshot, "bondedSnapshot");
        Snapshot active = activeSnapshot();
        HashMap<String, TwCommandItemConfig> byItem = new HashMap<>();
        HashMap<String, TwCommandItemConfig> byId = new HashMap<>();
        int loaded = 0;
        for (TwCommandItemConfig config : configs) {
            if (config == null || !config.isEnabled()) {
                continue;
            }
            String[] itemIds = config.getItemIds();
            if (itemIds == null) {
                continue;
            }
            for (String itemId : itemIds) {
                if (itemId == null || itemId.isBlank()) {
                    continue;
                }
                validateOwnerFamily(itemId, config, byItem);
                validateBondedRoster(config, bondedSnapshot);
                byItem.put(itemId, config);
                String configId = config.getId();
                if (configId != null && !configId.isBlank()) {
                    byId.put(configId.trim(), config);
                }
                loaded++;
            }
        }
        return new PreparedReplacement(
                active,
                new Snapshot(
                        byItem,
                        byId,
                        Math.addExact(active.revision(), 1L)
                ),
                loaded
        );
    }

    /** Publishes a previously validated command generation in one map swap. */
    public synchronized boolean publishPrepared(
            PreparedReplacement replacement
    ) {
        Objects.requireNonNull(replacement, "replacement");
        return publish(replacement.base, replacement.candidate);
    }

    private void validateOwnerFamily(
            String itemId,
            TwCommandItemConfig config,
            Map<String, TwCommandItemConfig> configsByItemId
    ) {
        if (!config.usesOwnerCommandFamilyRoster()) {
            return;
        }
        if (config.getCommandFamilyId() == null) {
            throw new IllegalArgumentException(
                    "OwnerCommandFamily command configs require CommandFamilyId"
            );
        }
        if (!config.isRequireOwner()) {
            throw new IllegalArgumentException(
                    "OwnerCommandFamily command configs require RequireOwner=true"
            );
        }
        TwCommandItemConfig previous = configsByItemId.get(itemId);
        if (previous != null
                && previous.usesOwnerCommandFamilyRoster()
                && !Objects.equals(
                previous.getCommandFamilyId(),
                config.getCommandFamilyId()
        )) {
            throw new IllegalArgumentException(
                    "One command item cannot access conflicting families: "
                            + itemId
            );
        }
    }

    private void validateBondedRoster(
            TwCommandItemConfig config,
            BondedCompanionRosterRegistry.Snapshot candidate
    ) {
        if (!config.usesBondedCompanionRoster()) {
            return;
        }
        if (config.getBondedRosterId() == null) {
            throw new IllegalArgumentException(
                    "BondedCompanions command configs require BondedRosterId"
            );
        }
        if (config.getCommandFamilyId() != null) {
            throw new IllegalArgumentException(
                    "BondedCompanions command configs cannot declare CommandFamilyId"
            );
        }
        if (config.hasProjectRosterToItemMetadataSetting()) {
            throw new IllegalArgumentException(
                    "BondedCompanions command configs cannot declare "
                            + "ProjectRosterToItemMetadata"
            );
        }
        boolean exists = candidate == null
                ? bondedRosters != null
                        && bondedRosters.resolve(
                                config.getBondedRosterId()
                        ).isPresent()
                : candidate.byRosterId().containsKey(
                        config.getBondedRosterId()
                );
        if (!exists) {
            throw new IllegalArgumentException(
                    "Unknown bonded roster: " + config.getBondedRosterId()
            );
        }
    }

    /** Opaque, immutable candidate returned only after full validation. */
    public static final class PreparedReplacement {
        private final Snapshot base;
        private final Snapshot candidate;
        private final int loadedCount;

        private PreparedReplacement(
                Snapshot base,
                Snapshot candidate,
                int loadedCount
        ) {
            this.base = Objects.requireNonNull(base, "base");
            this.candidate = Objects.requireNonNull(candidate, "candidate");
            this.loadedCount = loadedCount;
        }

        public int loadedCount() {
            return loadedCount;
        }

        public Snapshot candidate() {
            return candidate;
        }

        public Snapshot base() {
            return base;
        }
    }

    /** Immutable command half of a coherent bonded config generation. */
    public record Snapshot(
            @javax.annotation.Nonnull Map<String, TwCommandItemConfig> byItemId,
            @javax.annotation.Nonnull Map<String, TwCommandItemConfig> byConfigId,
            long revision
    ) {
        public Snapshot {
            byItemId = Map.copyOf(byItemId);
            byConfigId = Map.copyOf(byConfigId);
            if (revision < 0L) {
                throw new IllegalArgumentException(
                        "Command registry revision cannot be negative."
                );
            }
        }

        public static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), 0L);
        }
    }

    private Snapshot activeSnapshot() {
        return bondedRosters == null
                ? state
                : bondedRosters.coherentSnapshot().commands();
    }

    private boolean publish(Snapshot base, Snapshot candidate) {
        if (bondedRosters != null) {
            return bondedRosters.publishCommands(base, candidate);
        }
        if (state != base) {
            return false;
        }
        state = candidate;
        return true;
    }

    private void publishOrThrow(Snapshot base, Snapshot candidate) {
        if (!publish(base, candidate)) {
            throw new IllegalStateException(
                    "command-config-generation-publication-raced"
            );
        }
    }
}
