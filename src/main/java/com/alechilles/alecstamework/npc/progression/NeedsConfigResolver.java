package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nullable;

/**
 * Resolves needs config for an NPC from explicit config ID or role mapping.
 */
public final class NeedsConfigResolver {
    private NeedsConfigResolver() {
    }

    @Nullable
    public static TwNeedsConfig resolveConfig(@Nullable Ref<EntityStore> npcRef,
                                              @Nullable Store<EntityStore> store,
                                              @Nullable TameworkNeedsComponent component) {
        if (component != null && component.getConfigId() != null && !component.getConfigId().isBlank()) {
            TwNeedsConfig config = TwNeedsConfig.resolveById(component.getConfigId());
            if (config != null) {
                return config;
            }
        }
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        return TwNeedsConfig.resolveForRole(roleId);
    }
}
