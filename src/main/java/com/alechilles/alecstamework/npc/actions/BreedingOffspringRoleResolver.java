package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.hypixel.hytale.server.npc.NPCPlugin;
import javax.annotation.Nullable;

/**
 * Resolves offspring role selection using explicit breeding lifecycle family mappings.
 */
final class BreedingOffspringRoleResolver {
    @Nullable
    OffspringRoleSelection selectOffspringRole(@Nullable String parentRoleId,
                                               @Nullable TwBreedingConfig breedingConfig,
                                               @Nullable NPCPlugin npcPlugin) {
        if (parentRoleId == null || parentRoleId.isBlank() || npcPlugin == null) {
            return null;
        }

        if (breedingConfig != null) {
            TwBreedingConfig.OffspringLifecycleSettings lifecycle =
                    breedingConfig.resolveOffspringLifecycle(parentRoleId);
            if (lifecycle != null && lifecycle.isEnabled()) {
                TwBreedingConfig.RoleFamily family = breedingConfig.resolveLifecycleFamilyForRole(parentRoleId);
                if (family != null) {
                    String babyRoleId = family.getBabyRoleId();
                    if (babyRoleId != null && !babyRoleId.isBlank() && npcPlugin.getIndex(babyRoleId) >= 0) {
                        return new OffspringRoleSelection(babyRoleId, family);
                    }
                    String adultRoleId = family.getAdultRoleId();
                    if (adultRoleId != null && !adultRoleId.isBlank() && npcPlugin.getIndex(adultRoleId) >= 0) {
                        return new OffspringRoleSelection(adultRoleId, family);
                    }
                }
            }
        }
        if (npcPlugin.getIndex(parentRoleId) >= 0) {
            return new OffspringRoleSelection(parentRoleId, null);
        }
        return null;
    }

    record OffspringRoleSelection(String roleId, @Nullable TwBreedingConfig.RoleFamily lifecycleFamily) {
    }
}
