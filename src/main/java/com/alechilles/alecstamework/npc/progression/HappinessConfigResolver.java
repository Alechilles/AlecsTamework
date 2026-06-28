package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nullable;

/**
 * Resolves shared happiness config for an NPC.
 *
 * <p>Role-based resolution is preferred so priority selection stays consistent across reloads and
 * config edits. Component config IDs are treated as fallback only.
 */
public final class HappinessConfigResolver {
    private HappinessConfigResolver() {
    }

    @Nullable
    public static TwHappinessConfig resolveConfig(@Nullable Ref<EntityStore> npcRef,
                                                  @Nullable Store<EntityStore> store,
                                                  @Nullable TameworkHappinessComponent component) {
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        if (roleId != null && !roleId.isBlank()) {
            TwHappinessConfig byRole = TwHappinessConfig.resolveForRole(roleId);
            if (byRole != null) {
                return byRole;
            }
        }

        if (component != null && component.getConfigId() != null && !component.getConfigId().isBlank()) {
            TwHappinessConfig config = TwHappinessConfig.resolveById(component.getConfigId());
            if (config != null) {
                return config;
            }
        }
        return null;
    }

    public static boolean isRuntimeEnabled(@Nullable TwHappinessConfig config) {
        return isRuntimeEnabled(config, TameworkRuntimeSettings.currentOrNull());
    }

    static boolean isRuntimeEnabled(@Nullable TwHappinessConfig config, @Nullable TameworkRuntimeSettings settings) {
        return config != null && config.isEnabled() && (settings == null || settings.happinessEnabled());
    }
}
