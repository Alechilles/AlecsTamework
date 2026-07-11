package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.ownership.BreedingBirthPlanSnapshot;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Converts runtime breeding plans to and from their durable journal representation. */
final class BreedingBirthPlanSnapshotMapper {
    @Nonnull
    BreedingBirthPlanSnapshot snapshot(@Nonnull BreedingBirthPlan plan) {
        List<BreedingBirthPlanSnapshot.PlannedChild> children = new ArrayList<>(
                plan.children().size()
        );
        for (BreedingBirthPlan.PlannedChild child : plan.children()) {
            TwBreedingConfig.RoleFamily family = child.spawnRole().lifecycleFamily();
            children.add(new BreedingBirthPlanSnapshot.PlannedChild(
                    child.childKey(),
                    child.spawnRole().roleId(),
                    child.spawnRole().roleIndex(),
                    child.spawnRole().adultRoleId(),
                    child.spawnRole().gender() == null
                            ? null : child.spawnRole().gender().name(),
                    family != null,
                    family == null ? null : family.getId(),
                    family == null ? null : family.getSelectedLineId(),
                    child.owner().ownerId(),
                    child.owner().ownerName(),
                    child.populationType()
            ));
        }
        BreedingFertilityOffspringService.FertilityRoll fertility = plan.fertility();
        return new BreedingBirthPlanSnapshot(
                fertility.parentAMultiplier(),
                fertility.parentBMultiplier(),
                fertility.expectedOffspring(),
                fertility.offspringCount(),
                children
        );
    }

    @Nullable
    BreedingBirthPlan restore(@Nonnull BreedingBirthPlanSnapshot snapshot,
                              @Nullable TwBreedingConfig config) {
        try {
            List<BreedingBirthPlan.PlannedChild> children = new ArrayList<>(
                    snapshot.children().size()
            );
            for (BreedingBirthPlanSnapshot.PlannedChild child : snapshot.children()) {
                TwBreedingConfig.Gender gender = TwBreedingConfig.Gender.fromConfigValue(
                        child.gender()
                );
                if (child.gender() != null && gender == null) {
                    return null;
                }
                TwBreedingConfig.RoleFamily family = restoreFamily(child, config);
                if (child.lifecycleFamilyPresent() && family == null) {
                    return null;
                }
                BreedingResolvedSpawnRole spawnRole = new BreedingResolvedSpawnRole(
                        child.roleId(),
                        child.roleIndex(),
                        child.adultRoleId(),
                        gender,
                        family
                );
                children.add(new BreedingBirthPlan.PlannedChild(
                        child.childKey(),
                        spawnRole,
                        new BreedingOffspringProgressionService.OwnerSnapshot(
                                child.ownerId(), child.ownerName()
                        ),
                        child.populationType()
                ));
            }
            return new BreedingBirthPlan(
                    new BreedingFertilityOffspringService.FertilityRoll(
                            snapshot.parentAMultiplier(),
                            snapshot.parentBMultiplier(),
                            snapshot.expectedOffspring(),
                            snapshot.offspringCount()
                    ),
                    children
            );
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Nullable
    private static TwBreedingConfig.RoleFamily restoreFamily(
            BreedingBirthPlanSnapshot.PlannedChild child,
            @Nullable TwBreedingConfig config
    ) {
        if (!child.lifecycleFamilyPresent()) {
            return null;
        }
        if (config == null) {
            return null;
        }
        TwBreedingConfig.RoleFamily family = config.resolveLifecycleFamilyForRole(child.roleId());
        if (family == null) {
            family = config.resolveLifecycleFamilyForRole(child.adultRoleId());
        }
        if (family == null || !matchesOptionalId(child.lifecycleFamilyId(), family.getId())) {
            return null;
        }
        if (child.lifecycleLineId() == null) {
            TwBreedingConfig.RoleFamily selected = family.resolveLineFamilyForRole(child.roleId());
            return selected == null ? family : selected;
        }
        if (child.lifecycleLineId().equalsIgnoreCase(family.getSelectedLineId())) {
            return family;
        }
        for (TwBreedingConfig.RoleLine line : family.getLines()) {
            if (line != null && child.lifecycleLineId().equalsIgnoreCase(line.getId())) {
                return family.copyForLine(line);
            }
        }
        return null;
    }

    private static boolean matchesOptionalId(@Nullable String expected, @Nullable String actual) {
        return expected == null || (actual != null && expected.equalsIgnoreCase(actual));
    }
}
