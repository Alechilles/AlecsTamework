package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nullable;

/**
 * Resolves breeding config for an NPC.
 *
 * <p>Role-based resolution is preferred so priority/order rules are consistently applied when
 * multiple configs target the same role. Explicit component config IDs are only used as a
 * fallback when no role mapping is available.
 */
public final class BreedingConfigResolver {
    private BreedingConfigResolver() {
    }

    @Nullable
    public static TwBreedingConfig resolveConfig(@Nullable Ref<EntityStore> npcRef,
                                                 @Nullable Store<EntityStore> store,
                                                 @Nullable TameworkBreedingComponent component) {
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        if (roleId != null && !roleId.isBlank()) {
            TwBreedingConfig byRole = TwBreedingConfig.resolveForRole(roleId);
            if (byRole != null) {
                return byRole;
            }
        }

        if (component != null && component.getConfigId() != null && !component.getConfigId().isBlank()) {
            TwBreedingConfig config = TwBreedingConfig.resolveById(component.getConfigId());
            if (config != null) {
                return config;
            }
        }
        return null;
    }
}
