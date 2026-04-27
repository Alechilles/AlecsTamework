package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.hypixel.hytale.server.npc.NPCPlugin;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nullable;

/**
 * Selects the adult role an offspring will grow into from a breeding lifecycle family.
 */
final class BreedingAdultRoleSelectionService {
    @Nullable
    String selectAdultRole(@Nullable TwBreedingConfig.RoleFamily family, @Nullable NPCPlugin npcPlugin) {
        return selectAdultRole(family, npcPlugin, ThreadLocalRandom.current().nextDouble());
    }

    @Nullable
    String selectAdultRole(@Nullable TwBreedingConfig.RoleFamily family,
                           @Nullable NPCPlugin npcPlugin,
                           double roll) {
        if (family == null) {
            return null;
        }
        if (!family.hasWeightedAdultRoles()) {
            return isAvailableRole(family.getLegacyAdultRoleId(), npcPlugin)
                    ? family.getLegacyAdultRoleId()
                    : null;
        }

        double totalWeight = 0.0;
        for (TwBreedingConfig.AdultRoleChoice choice : family.getAdultRoles()) {
            if (choice == null || !isAvailableRole(choice.getRoleId(), npcPlugin)) {
                continue;
            }
            totalWeight += choice.getWeight();
        }
        if (!Double.isFinite(totalWeight) || totalWeight <= 0.0) {
            return null;
        }

        double target = clampRoll(roll) * totalWeight;
        double cursor = 0.0;
        String fallback = null;
        for (TwBreedingConfig.AdultRoleChoice choice : family.getAdultRoles()) {
            if (choice == null || !isAvailableRole(choice.getRoleId(), npcPlugin)) {
                continue;
            }
            double weight = choice.getWeight();
            if (weight <= 0.0) {
                continue;
            }
            fallback = choice.getRoleId();
            cursor += weight;
            if (target < cursor) {
                return choice.getRoleId();
            }
        }
        return fallback;
    }

    private static boolean isAvailableRole(@Nullable String roleId, @Nullable NPCPlugin npcPlugin) {
        if (roleId == null || roleId.isBlank()) {
            return false;
        }
        return npcPlugin == null || npcPlugin.getIndex(roleId) >= 0;
    }

    private static double clampRoll(double roll) {
        if (!Double.isFinite(roll) || roll <= 0.0) {
            return 0.0;
        }
        if (roll >= 1.0) {
            return Math.nextDown(1.0);
        }
        return roll;
    }
}
