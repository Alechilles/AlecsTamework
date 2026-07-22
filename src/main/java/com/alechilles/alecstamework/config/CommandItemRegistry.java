package com.alechilles.alecstamework.config;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Arrays;

/**
 * Registry for command item configs keyed by item id.
 */
public final class CommandItemRegistry {
    private final Map<String, TwCommandItemConfig> configsByItemId = new HashMap<>();
    private final Map<String, TwCommandItemConfig> configsById = new HashMap<>();

    public void register(String itemId, TwCommandItemConfig config) {
        register(config.getId(), itemId, config);
    }

    public void register(String configId, String itemId, TwCommandItemConfig config) {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(config, "config");
        if (config.usesOwnerCommandFamilyRoster()) {
            if (config.getCommandFamilyId() == null) {
                throw new IllegalArgumentException(
                        "OwnerCommandFamily command configs require CommandFamilyId.");
            }
            if (!config.isRequireOwner()) {
                throw new IllegalArgumentException(
                        "OwnerCommandFamily command configs require RequireOwner=true.");
            }
            TwCommandItemConfig previous = configsByItemId.get(itemId);
            if (previous != null && previous.usesOwnerCommandFamilyRoster()
                    && !Objects.equals(previous.getCommandFamilyId(), config.getCommandFamilyId())) {
                throw new IllegalArgumentException(
                        "One command item cannot access conflicting command families: " + itemId);
            }
        }
        configsByItemId.put(itemId, config);
        if (configId != null && !configId.isBlank()) configsById.put(configId.trim(), config);
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
        return configId == null ? null : configsById.get(configId.trim());
    }

    /** Validates config/family/item/role policy only; physical inventory possession is a caller fence. */
    public String validateOwnerFamilyAccess(String familyId, String configId,
                                            String accessItemId, String profileRoleId) {
        if (configId == null || configId.isBlank()) return "command-config-required";
        TwCommandItemConfig config = getByConfigId(configId);
        if (config == null || !config.isEnabled()) return "command-config-unavailable";
        if (!config.usesOwnerCommandFamilyRoster()) return "command-config-not-owner-family";
        if (config.getCommandFamilyId() == null
                || !config.getCommandFamilyId().equals(familyId)) return "command-family-mismatch";
        if (accessItemId != null && get(accessItemId) != config) return "command-access-item-mismatch";
        TwCommandItemConfig.AllowedRoles allowed = config.getAllowedRoles();
        if (allowed == null || allowed.getMode() == TwCommandItemConfig.RoleFilterMode.AllowAll) return null;
        boolean listed = Arrays.stream(allowed.getMode() == TwCommandItemConfig.RoleFilterMode.Allowlist
                        ? allowed.getAllowlist() : allowed.getDenylist())
                .filter(Objects::nonNull).map(String::trim)
                .anyMatch(profileRoleId::equals);
        if (allowed.getMode() == TwCommandItemConfig.RoleFilterMode.Allowlist && !listed) {
            return "profile-role-not-allowed";
        }
        if (allowed.getMode() == TwCommandItemConfig.RoleFilterMode.Denylist && listed) {
            return "profile-role-denied";
        }
        return null;
    }

    public void clear() {
        configsByItemId.clear();
        configsById.clear();
    }
}
