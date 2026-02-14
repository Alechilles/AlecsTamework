package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.server.npc.role.Role;

/** Resolves interaction configs using overrides, role params, and role IDs. */
final class InteractionConfigResolver {
    private final String configIdOverride;
    private final InteractionParamAccess paramAccess;
    private final String configParamName;

    InteractionConfigResolver(String configIdOverride,
                              InteractionParamAccess paramAccess,
                              String configParamName) {
        this.configIdOverride = configIdOverride;
        this.paramAccess = paramAccess;
        this.configParamName = configParamName;
    }

    // Resolves the interaction config for a role, honoring overrides and role params.
    TwInteractionConfig resolveConfig(Role role, InteractionContextSnapshot ctx) {
        DefaultAssetMap<String, TwInteractionConfig> assetMap = TwInteractionConfig.getAssetMap();
        if (assetMap == null) {
            return null;
        }
        String configId = configIdOverride;
        if (configId == null || configId.isBlank()) {
            String roleOverride = paramAccess.getRoleStringParam(role, ctx, configParamName);
            if (roleOverride != null && !roleOverride.isBlank()) {
                configId = roleOverride;
            }
        }
        if (configId != null && !configId.isBlank()) {
            return assetMap.getAssetMap().get(configId);
        }
        String roleId = role != null ? role.getRoleName() : null;
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        return TwInteractionConfig.resolveForRole(roleId);
    }
}
