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
        return selectOffspringRole(parentRoleId, breedingConfig, npcPlugin, Math.random(), Math.random());
    }

    @Nullable
    OffspringRoleSelection selectOffspringRole(@Nullable String parentRoleId,
                                               @Nullable TwBreedingConfig breedingConfig,
                                               @Nullable NPCPlugin npcPlugin,
                                               double adultRoleRoll) {
        return selectOffspringRole(parentRoleId, breedingConfig, npcPlugin, adultRoleRoll, Math.random());
    }

    @Nullable
    OffspringRoleSelection selectOffspringRole(@Nullable String parentRoleId,
                                               @Nullable TwBreedingConfig breedingConfig,
                                               @Nullable NPCPlugin npcPlugin,
                                               double adultRoleRoll,
                                               double genderRoll) {
        if (parentRoleId == null || parentRoleId.isBlank() || npcPlugin == null) {
            return null;
        }

        if (breedingConfig != null) {
            TwBreedingConfig.OffspringLifecycleSettings lifecycle =
                    breedingConfig.resolveOffspringLifecycle(parentRoleId);
            if (lifecycle != null && lifecycle.isEnabled()) {
                TwBreedingConfig.RoleFamily family = breedingConfig.resolveLifecycleFamilyForRole(parentRoleId);
                if (family != null) {
                    TwBreedingConfig.Gender gender = resolveOffspringGender(parentRoleId, breedingConfig, genderRoll);
                    String adultRoleId = adultRoleSelectionService.selectAdultRole(
                            family,
                            npcPlugin,
                            adultRoleRoll,
                            gender
                    );
                    if (adultRoleId == null || adultRoleId.isBlank()) {
                        return null;
                    }
                    TwBreedingConfig.Gender selectedGender = resolveSelectedGender(family, adultRoleId, gender);
                    String babyRoleId = family.getBabyRoleId();
                    if (babyRoleId != null && !babyRoleId.isBlank() && npcPlugin.getIndex(babyRoleId) >= 0) {
                        return new OffspringRoleSelection(babyRoleId, adultRoleId, selectedGender, family);
                    }
                    return new OffspringRoleSelection(adultRoleId, adultRoleId, selectedGender, family);
                }
            }
        }
        if (npcPlugin.getIndex(parentRoleId) >= 0) {
            return new OffspringRoleSelection(parentRoleId, parentRoleId, null, null);
        }
        return null;
    }

    @Nullable
    private static TwBreedingConfig.Gender resolveOffspringGender(@Nullable String parentRoleId,
                                                                  @Nullable TwBreedingConfig breedingConfig,
                                                                  double genderRoll) {
        TwBreedingConfig.GenderSettings settings = breedingConfig != null
                ? breedingConfig.resolveGender(parentRoleId)
                : null;
        if (settings == null || !settings.isEnabled()) {
            return null;
        }
        return settings.selectGender(genderRoll);
    }

    @Nullable
    static TwBreedingConfig.Gender resolveSelectedGender(@Nullable TwBreedingConfig.RoleFamily family,
                                                         @Nullable String adultRoleId,
                                                         @Nullable TwBreedingConfig.Gender sampledGender) {
        TwBreedingConfig.Gender adultRoleGender = family != null
                ? family.resolveGenderForAdultRole(adultRoleId)
                : null;
        return adultRoleGender != null ? adultRoleGender : sampledGender;
    }

    record OffspringRoleSelection(String roleId,
                                  String adultRoleId,
                                  @Nullable TwBreedingConfig.Gender gender,
                                  @Nullable TwBreedingConfig.RoleFamily lifecycleFamily) {
    }
}
