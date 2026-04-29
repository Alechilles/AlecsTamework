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
        return selectAdultRole(family, npcPlugin, ThreadLocalRandom.current().nextDouble(), null);
    }

    @Nullable
    String selectAdultRole(@Nullable TwBreedingConfig.RoleFamily family,
                           @Nullable NPCPlugin npcPlugin,
                           double roll) {
        return selectAdultRole(family, npcPlugin, roll, null);
    }

    @Nullable
    String selectAdultRole(@Nullable TwBreedingConfig.RoleFamily family,
                           @Nullable NPCPlugin npcPlugin,
                           double roll,
                           @Nullable TwBreedingConfig.Gender gender) {
        if (family == null) {
            return null;
        }
        if (!family.hasWeightedAdultRoles()) {
            return isAvailableRole(family.getLegacyAdultRoleId(), npcPlugin)
                    ? family.getLegacyAdultRoleId()
                    : null;
        }

        double totalWeight = totalWeight(family, npcPlugin, gender);
        TwBreedingConfig.Gender effectiveGender = gender;
        if (!Double.isFinite(totalWeight) || totalWeight <= 0.0) {
            effectiveGender = null;
            totalWeight = totalWeight(family, npcPlugin, null);
        }
        if (!Double.isFinite(totalWeight) || totalWeight <= 0.0) {
            return null;
        }

        double target = clampRoll(roll) * totalWeight;
        double cursor = 0.0;
        String fallback = null;
        for (TwBreedingConfig.AdultRoleChoice choice : family.getAdultRoles()) {
            if (choice == null || !isAvailableRole(choice.getRoleId(), npcPlugin) || !choice.matchesGender(effectiveGender)) {
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

    private static double totalWeight(@Nullable TwBreedingConfig.RoleFamily family,
                                      @Nullable NPCPlugin npcPlugin,
                                      @Nullable TwBreedingConfig.Gender gender) {
        if (family == null) {
            return 0.0;
        }
        double totalWeight = 0.0;
        for (TwBreedingConfig.AdultRoleChoice choice : family.getAdultRoles()) {
            if (choice == null || !isAvailableRole(choice.getRoleId(), npcPlugin) || !choice.matchesGender(gender)) {
                continue;
            }
            double weight = choice.getWeight();
            if (weight <= 0.0) {
                continue;
            }
            totalWeight += weight;
        }
        return totalWeight;
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
