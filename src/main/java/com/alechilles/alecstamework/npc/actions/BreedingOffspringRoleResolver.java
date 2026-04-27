package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.hypixel.hytale.server.npc.NPCPlugin;
import javax.annotation.Nullable;

/**
 * Resolves offspring role selection using explicit breeding lifecycle family mappings.
 */
final class BreedingOffspringRoleResolver {
    private final BreedingAdultRoleSelectionService adultRoleSelectionService = new BreedingAdultRoleSelectionService();

    @Nullable
    OffspringRoleSelection selectOffspringRole(@Nullable String parentRoleId,
                                               @Nullable TwBreedingConfig breedingConfig,
                                               @Nullable NPCPlugin npcPlugin) {
        return selectOffspringRole(parentRoleId, breedingConfig, npcPlugin, Math.random());
    }

    @Nullable
    OffspringRoleSelection selectOffspringRole(@Nullable String parentRoleId,
                                               @Nullable TwBreedingConfig breedingConfig,
                                               @Nullable NPCPlugin npcPlugin,
                                               double adultRoleRoll) {
        if (parentRoleId == null || parentRoleId.isBlank() || npcPlugin == null) {
            return null;
        }

        if (breedingConfig != null) {
            TwBreedingConfig.OffspringLifecycleSettings lifecycle =
                    breedingConfig.resolveOffspringLifecycle(parentRoleId);
            if (lifecycle != null && lifecycle.isEnabled()) {
                TwBreedingConfig.RoleFamily family = breedingConfig.resolveLifecycleFamilyForRole(parentRoleId);
                if (family != null) {
                    String adultRoleId = adultRoleSelectionService.selectAdultRole(family, npcPlugin, adultRoleRoll);
                    if (adultRoleId == null || adultRoleId.isBlank()) {
                        return null;
                    }
                    String babyRoleId = family.getBabyRoleId();
                    if (babyRoleId != null && !babyRoleId.isBlank() && npcPlugin.getIndex(babyRoleId) >= 0) {
                        return new OffspringRoleSelection(babyRoleId, adultRoleId, family);
                    }
                    return new OffspringRoleSelection(adultRoleId, adultRoleId, family);
                }
            }
        }
        if (npcPlugin.getIndex(parentRoleId) >= 0) {
            return new OffspringRoleSelection(parentRoleId, parentRoleId, null);
        }
        return null;
    }

    record OffspringRoleSelection(String roleId,
                                  String adultRoleId,
                                  @Nullable TwBreedingConfig.RoleFamily lifecycleFamily) {
    }
}
