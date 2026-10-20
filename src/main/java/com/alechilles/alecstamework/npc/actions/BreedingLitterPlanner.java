package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Freezes one litter before the delayed birth. */
final class BreedingLitterPlanner {
    private final BreedingFertilityOffspringService fertility =
            new BreedingFertilityOffspringService();
    private final BreedingOffspringSpawnService roles =
            new BreedingOffspringSpawnService(
                    new BreedingOffspringRoleResolver()
            );

    @Nullable
    Plan plan(
            @Nonnull Ref<EntityStore> parentARef,
            @Nonnull Ref<EntityStore> parentBRef,
            @Nonnull Store<EntityStore> store,
            @Nonnull BreedingPairContext context,
            @Nullable TwBreedingConfig config,
            @Nonnull String worldName
    ) {
        Vector3d spawn = context.spawnAnchor();
        NPCPlugin npcPlugin = NPCPlugin.get();
        Tamework plugin = Tamework.getInstance();
        if (spawn == null || npcPlugin == null || plugin == null) {
            return null;
        }
        BreedingFertilityOffspringService.FertilityRoll roll =
                fertility.rollOffspring(parentARef, parentBRef, store);
        if (roll.offspringCount() <= 0) {
            return Plan.empty(roll);
        }
        UUID litterId = UUID.randomUUID();
        List<UUID> childIds = BreedingLitterOperation.plannedChildIds(
                litterId, roll.offspringCount()
        );
        ArrayList<BreedingLitterOperation.ChildPlan> children =
                new ArrayList<>(roll.offspringCount());
        ArrayList<BreedingResolvedSpawnRole> resolvedRoles = new ArrayList<>(roll.offspringCount());
        for (int ordinal = 0; ordinal < roll.offspringCount(); ordinal++) {
            BreedingResolvedSpawnRole role = roles.resolveSpawnRole(
                    context.parentARoleId(),
                    context.parentBRoleId(),
                    config,
                    context.parentARoleIndex(),
                    context.parentBRoleIndex(),
                    npcPlugin,
                    Math.random(),
                    Math.random()
            );
            if (role == null) {
                return null;
            }
            TwBreedingConfig.RoleFamily family = role.lifecycleFamily();
            resolvedRoles.add(role);
            children.add(new BreedingLitterOperation.ChildPlan(
                    childIds.get(ordinal),
                    role.roleId(),
                    role.adultRoleId(),
                    role.gender() == null
                            ? null : role.gender().name(),
                    family == null ? null : family.getId(),
                    family == null ? null : family.getSelectedLineId()
            ));
        }
        // A litter that mixes managed profiles is still refused. Its caps are checked at birth.
        Map<String, ManagedActivityConfigRegistry.RoleResolution> managedRoles =
                plugin.getManagedActivityConfigRegistry().snapshot().rolesById();
        String managedProfile = resolveManagedProfile(children, roleId -> {
            ManagedActivityConfigRegistry.RoleResolution resolution = managedRoles.get(roleId);
            return resolution == null ? null : resolution.profile().profileId();
        });
        if (managedProfile == null) {
            return null;
        }
        return new Plan(roll, List.copyOf(children), List.copyOf(resolvedRoles));
    }

    /** Returns an empty profile for ordinary births, or null for incompatible profiles. */
    @Nullable
    static String resolveManagedProfile(
            @Nonnull List<BreedingLitterOperation.ChildPlan> children,
            @Nonnull Function<String, String> profileForRole
    ) {
        String profileId = null;
        for (BreedingLitterOperation.ChildPlan child : children) {
            String childProfile = profileForRole.apply(child.roleId());
            childProfile = childProfile == null ? "" : childProfile.trim();
            // An ordinary litter needs no managed profile. Mixed litters must
            // still fail admission instead of bypassing a managed child's cap.
            if (profileId != null && !profileId.equals(childProfile)) {
                return null;
            }
            profileId = childProfile;
        }
        return profileId;
    }

    record Plan(
            @Nonnull BreedingFertilityOffspringService.FertilityRoll fertility,
            @Nonnull List<BreedingLitterOperation.ChildPlan> children,
            @Nonnull List<BreedingResolvedSpawnRole> resolvedRoles
    ) {
        static Plan empty(
                BreedingFertilityOffspringService.FertilityRoll fertility
        ) {
            return new Plan(fertility, List.of(), List.of());
        }

        boolean empty() {
            return children.isEmpty();
        }
    }
}
