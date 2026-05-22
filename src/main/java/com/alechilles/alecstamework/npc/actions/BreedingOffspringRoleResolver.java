package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.hypixel.hytale.server.npc.NPCPlugin;
import java.util.LinkedHashMap;
import java.util.Map;
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
        return selectOffspringRole(parentRoleId, null, breedingConfig, npcPlugin);
    }

    @Nullable
    OffspringRoleSelection selectOffspringRole(@Nullable String parentARoleId,
                                               @Nullable String parentBRoleId,
                                               @Nullable TwBreedingConfig breedingConfig,
                                               @Nullable NPCPlugin npcPlugin) {
        return selectOffspringRole(
                parentARoleId,
                parentBRoleId,
                breedingConfig,
                npcPlugin,
                Math.random(),
                Math.random(),
                Math.random(),
                Math.random()
        );
    }

    @Nullable
    OffspringRoleSelection selectOffspringRole(@Nullable String parentRoleId,
                                               @Nullable TwBreedingConfig breedingConfig,
                                               @Nullable NPCPlugin npcPlugin,
                                               double adultRoleRoll) {
        return selectOffspringRole(parentRoleId, null, breedingConfig, npcPlugin, adultRoleRoll);
    }

    @Nullable
    OffspringRoleSelection selectOffspringRole(@Nullable String parentARoleId,
                                               @Nullable String parentBRoleId,
                                               @Nullable TwBreedingConfig breedingConfig,
                                               @Nullable NPCPlugin npcPlugin,
                                               double adultRoleRoll) {
        return selectOffspringRole(
                parentARoleId,
                parentBRoleId,
                breedingConfig,
                npcPlugin,
                Math.random(),
                Math.random(),
                adultRoleRoll,
                Math.random()
        );
    }

    @Nullable
    OffspringRoleSelection selectOffspringRole(@Nullable String parentRoleId,
                                               @Nullable TwBreedingConfig breedingConfig,
                                               @Nullable NPCPlugin npcPlugin,
                                               double adultRoleRoll,
                                               double genderRoll) {
        return selectOffspringRole(
                parentRoleId,
                null,
                breedingConfig,
                npcPlugin,
                Math.random(),
                Math.random(),
                adultRoleRoll,
                genderRoll
        );
    }

    @Nullable
    OffspringRoleSelection selectOffspringRole(@Nullable String parentARoleId,
                                               @Nullable String parentBRoleId,
                                               @Nullable TwBreedingConfig breedingConfig,
                                               @Nullable NPCPlugin npcPlugin,
                                               double lineRoll,
                                               double mutationRoll,
                                               double adultRoleRoll,
                                               double genderRoll) {
        String primaryParentRoleId = firstNonBlank(parentARoleId, parentBRoleId);
        if (primaryParentRoleId == null) {
            return null;
        }

        if (breedingConfig != null) {
            TwBreedingConfig.OffspringLifecycleSettings lifecycle = breedingConfig.resolveOffspringLifecycle(
                    primaryParentRoleId
            );
            if (lifecycle != null && lifecycle.isEnabled()) {
                TwBreedingConfig.RoleFamily family = resolveLifecycleFamily(
                        parentARoleId,
                        parentBRoleId,
                        breedingConfig,
                        lifecycle,
                        lineRoll,
                        mutationRoll
                );
                if (family != null) {
                    TwBreedingConfig.Gender gender = resolveOffspringGender(
                            primaryParentRoleId,
                            breedingConfig,
                            genderRoll
                    );
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
                    if (isAvailableRole(babyRoleId, npcPlugin)) {
                        return new OffspringRoleSelection(babyRoleId, adultRoleId, selectedGender, family);
                    }
                    return new OffspringRoleSelection(adultRoleId, adultRoleId, selectedGender, family);
                }
            }
        }
        if (isAvailableRole(primaryParentRoleId, npcPlugin)) {
            return new OffspringRoleSelection(primaryParentRoleId, primaryParentRoleId, null, null);
        }
        return null;
    }

    @Nullable
    private TwBreedingConfig.RoleFamily resolveLifecycleFamily(@Nullable String parentARoleId,
                                                               @Nullable String parentBRoleId,
                                                               @Nullable TwBreedingConfig breedingConfig,
                                                               @Nullable TwBreedingConfig.OffspringLifecycleSettings lifecycle,
                                                               double lineRoll,
                                                               double mutationRoll) {
        if (breedingConfig == null || lifecycle == null) {
            return null;
        }
        TwBreedingConfig.RoleFamily parentAFamily = breedingConfig.resolveLifecycleFamilyForRole(parentARoleId);
        TwBreedingConfig.RoleFamily parentBFamily = breedingConfig.resolveLifecycleFamilyForRole(parentBRoleId);
        TwBreedingConfig.RoleFamily family = parentAFamily != null ? parentAFamily : parentBFamily;
        if (family == null) {
            return null;
        }
        TwBreedingConfig.RoleInheritanceSettings roleInheritance = lifecycle.getRoleInheritance();
        if (roleInheritance.getMode() == TwBreedingConfig.RoleInheritanceMode.PARENT_LINE) {
            TwBreedingConfig.RoleFamily selected = selectParentLineFamily(
                    family,
                    parentAFamily,
                    parentBFamily,
                    parentARoleId,
                    parentBRoleId,
                    roleInheritance,
                    lineRoll,
                    mutationRoll
            );
            if (selected != null) {
                return selected;
            }
        }
        if (family.hasLines() && !hasDirectSelectableAdultRole(family)) {
            TwBreedingConfig.RoleFamily selected = selectAnyLineFamily(family, lineRoll, null, null);
            if (selected != null) {
                return selected;
            }
        }
        return family;
    }

    private static boolean hasDirectSelectableAdultRole(@Nullable TwBreedingConfig.RoleFamily family) {
        if (family == null) {
            return false;
        }
        if (family.getLegacyAdultRoleId() != null && !family.getLegacyAdultRoleId().isBlank()) {
            return true;
        }
        for (TwBreedingConfig.AdultRoleChoice choice : family.getAdultRoles()) {
            if (choice != null
                    && choice.getRoleId() != null
                    && !choice.getRoleId().isBlank()
                    && choice.hasPositiveWeight()) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private TwBreedingConfig.RoleFamily selectParentLineFamily(@Nullable TwBreedingConfig.RoleFamily family,
                                                               @Nullable TwBreedingConfig.RoleFamily parentAFamily,
                                                               @Nullable TwBreedingConfig.RoleFamily parentBFamily,
                                                               @Nullable String parentARoleId,
                                                               @Nullable String parentBRoleId,
                                                               @Nullable TwBreedingConfig.RoleInheritanceSettings settings,
                                                               double lineRoll,
                                                               double mutationRoll) {
        if (family == null || settings == null || !family.hasLines()) {
            return null;
        }
        TwBreedingConfig.RoleLine parentALine = parentAFamily != null && family.sameLifecycleFamily(parentAFamily)
                ? parentAFamily.resolveLineForRole(parentARoleId)
                : null;
        TwBreedingConfig.RoleLine parentBLine = parentBFamily != null && family.sameLifecycleFamily(parentBFamily)
                ? parentBFamily.resolveLineForRole(parentBRoleId)
                : null;
        if (parentALine == null && parentBLine == null) {
            return selectAnyLineFamily(family, lineRoll, null, null);
        }
        if (settings.getMutationChance() > 0.0 && clampRoll(mutationRoll) < settings.getMutationChance()) {
            TwBreedingConfig.RoleFamily mutation = selectAnyLineFamily(family, lineRoll, parentALine, parentBLine);
            if (mutation != null) {
                return mutation;
            }
        }
        TwBreedingConfig.RoleLine selectedLine = selectWeightedParentLine(
                family,
                parentALine,
                parentBLine,
                settings,
                lineRoll
        );
        return selectedLine == null ? null : family.copyForLine(selectedLine);
    }

    @Nullable
    private static TwBreedingConfig.RoleLine selectWeightedParentLine(@Nullable TwBreedingConfig.RoleFamily family,
                                                                      @Nullable TwBreedingConfig.RoleLine parentALine,
                                                                      @Nullable TwBreedingConfig.RoleLine parentBLine,
                                                                      @Nullable TwBreedingConfig.RoleInheritanceSettings settings,
                                                                      double roll) {
        if (family == null || settings == null) {
            return null;
        }
        LinkedHashMap<TwBreedingConfig.RoleLine, Double> weights = new LinkedHashMap<>();
        addLineWeight(weights, parentALine, settings.getParentWeight());
        addLineWeight(weights, parentBLine, settings.getParentWeight());
        double randomWeight = settings.getRandomWeight();
        if (randomWeight > 0.0) {
            for (TwBreedingConfig.RoleLine line : family.getLines()) {
                addLineWeight(weights, line, randomWeight);
            }
        }
        return selectWeightedLine(weights, roll);
    }

    @Nullable
    private static TwBreedingConfig.RoleFamily selectAnyLineFamily(@Nullable TwBreedingConfig.RoleFamily family,
                                                                   double roll,
                                                                   @Nullable TwBreedingConfig.RoleLine excludeA,
                                                                   @Nullable TwBreedingConfig.RoleLine excludeB) {
        TwBreedingConfig.RoleLine selected = selectAnyLine(family, roll, excludeA, excludeB);
        if (selected == null && (excludeA != null || excludeB != null)) {
            selected = selectAnyLine(family, roll, null, null);
        }
        return selected == null || family == null ? null : family.copyForLine(selected);
    }

    @Nullable
    private static TwBreedingConfig.RoleLine selectAnyLine(@Nullable TwBreedingConfig.RoleFamily family,
                                                           double roll,
                                                           @Nullable TwBreedingConfig.RoleLine excludeA,
                                                           @Nullable TwBreedingConfig.RoleLine excludeB) {
        if (family == null) {
            return null;
        }
        LinkedHashMap<TwBreedingConfig.RoleLine, Double> weights = new LinkedHashMap<>();
        for (TwBreedingConfig.RoleLine line : family.getLines()) {
            if (line == null || line == excludeA || line == excludeB) {
                continue;
            }
            addLineWeight(weights, line, 1.0);
        }
        return selectWeightedLine(weights, roll);
    }

    private static void addLineWeight(Map<TwBreedingConfig.RoleLine, Double> weights,
                                      @Nullable TwBreedingConfig.RoleLine line,
                                      double weight) {
        if (line == null || !line.hasSelectableAdultRole() || !Double.isFinite(weight) || weight <= 0.0) {
            return;
        }
        weights.merge(line, weight, Double::sum);
    }

    @Nullable
    private static TwBreedingConfig.RoleLine selectWeightedLine(
            @Nullable LinkedHashMap<TwBreedingConfig.RoleLine, Double> weights,
            double roll) {
        if (weights == null || weights.isEmpty()) {
            return null;
        }
        double totalWeight = 0.0;
        for (double weight : weights.values()) {
            if (Double.isFinite(weight) && weight > 0.0) {
                totalWeight += weight;
            }
        }
        if (!Double.isFinite(totalWeight) || totalWeight <= 0.0) {
            return null;
        }
        double target = clampRoll(roll) * totalWeight;
        double cursor = 0.0;
        TwBreedingConfig.RoleLine fallback = null;
        for (Map.Entry<TwBreedingConfig.RoleLine, Double> entry : weights.entrySet()) {
            double weight = entry.getValue() == null ? 0.0 : entry.getValue();
            if (!Double.isFinite(weight) || weight <= 0.0) {
                continue;
            }
            fallback = entry.getKey();
            cursor += weight;
            if (target < cursor) {
                return entry.getKey();
            }
        }
        return fallback;
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

    private static boolean isAvailableRole(@Nullable String roleId, @Nullable NPCPlugin npcPlugin) {
        if (roleId == null || roleId.isBlank()) {
            return false;
        }
        return npcPlugin == null || npcPlugin.getIndex(roleId) >= 0;
    }

    @Nullable
    private static String firstNonBlank(@Nullable String first, @Nullable String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
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

    record OffspringRoleSelection(String roleId,
                                  String adultRoleId,
                                  @Nullable TwBreedingConfig.Gender gender,
                                  @Nullable TwBreedingConfig.RoleFamily lifecycleFamily) {
    }
}
