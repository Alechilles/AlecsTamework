package com.alechilles.alecstamework.config;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Registry for command item configs keyed by item id.
 */
public final class CommandItemRegistry {
    private final Map<String, TwCommandItemConfig> configsByItemId = new HashMap<>();
    private final Map<String, TwCommandItemConfig> configsById = new HashMap<>();
    private long revision;

    public void register(String itemId, TwCommandItemConfig config) {
        register(config == null ? null : config.getId(), itemId, config);
    }

    public void register(
            String configId,
            String itemId,
            TwCommandItemConfig config
    ) {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(config, "config");
        validateOwnerFamily(itemId, config);
        configsByItemId.put(itemId, config);
        if (configId != null && !configId.isBlank()) {
            configsById.put(configId.trim(), config);
        }
        revision = Math.addExact(revision, 1);
    }

    public TwCommandItemConfig get(String itemId) {
        if (itemId == null) {
            return null;
        }
        TwCommandItemConfig config = configsByItemId.get(itemId);
        if (config != null) {
            return config;
        }
        String normalized = ItemFeatureRegistry.normalizeStateItemId(itemId);
        if (normalized != null && !normalized.equals(itemId)) {
            return configsByItemId.get(normalized);
        }
        return null;
    }

    public Map<String, TwCommandItemConfig> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(configsByItemId));
    }

    public TwCommandItemConfig getByConfigId(String configId) {
        return configId == null
                ? null
                : configsById.get(configId.trim());
    }

    public long revision() {
        return revision;
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

    public void clear() {
        configsByItemId.clear();
        configsById.clear();
        revision = Math.addExact(revision, 1);
    }

    private void validateOwnerFamily(
            String itemId,
            TwCommandItemConfig config
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
}
