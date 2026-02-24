package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.server.npc.NPCPlugin;
import javax.annotation.Nullable;

/**
 * Resolves offspring role selection, preferring role-specific baby variants when available.
 */
final class BreedingOffspringRoleResolver {
    @Nullable
    OffspringRoleSelection selectOffspringRole(@Nullable String parentRoleId, @Nullable NPCPlugin npcPlugin) {
        if (parentRoleId == null || parentRoleId.isBlank() || npcPlugin == null) {
            return null;
        }
        String[] candidates = new String[] {
                parentRoleId + "_Baby",
                parentRoleId + "Baby",
                "Baby_" + parentRoleId,
                parentRoleId.startsWith("Mob_")
                        ? "Mob_Baby_" + parentRoleId.substring("Mob_".length())
                        : null
        };
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            if (npcPlugin.getIndex(candidate) >= 0) {
                return new OffspringRoleSelection(candidate, true);
            }
        }
        if (npcPlugin.getIndex(parentRoleId) >= 0) {
            return new OffspringRoleSelection(parentRoleId, false);
        }
        return null;
    }

    record OffspringRoleSelection(String roleId, boolean hasBabyVariant) {
    }
}
