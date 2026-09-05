package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.PopulationAdmissionForcePolicy;
import com.alechilles.alecstamework.api.PopulationAdmissionIdentity;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.api.PopulationAdmissionOperation;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV2;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV3;
import com.alechilles.alecstamework.api.PopulationAdmissionToken;
import com.alechilles.alecstamework.api.PopulationCompanionLifecycle;
import com.alechilles.alecstamework.companion.population.domain.ManagedBatchAdmissionRequest;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import com.alechilles.alecstamework.math.TameworkRotationUtil;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
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

/** Freezes one litter before managed admission and delayed world work. */
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
        String targetRole = children.getFirst().roleId();
        // Retained mappings still require managed admission when a config reload
        // makes them stale. The admission service checks profile readiness.
        Map<String, ManagedActivityConfigRegistry.RoleResolution> managedRoles =
                plugin.getManagedActivityConfigRegistry().snapshot().rolesById();
        String managedProfile = resolveManagedProfile(children, roleId -> {
            ManagedActivityConfigRegistry.RoleResolution resolution = managedRoles.get(roleId);
            return resolution == null ? null : resolution.profile().profileId();
        });
        if (managedProfile == null) {
            return null;
        }
        if (managedProfile.isEmpty()) {
            return new Plan(litterId, roll, List.copyOf(children), null,
                    rotation(parentARef, parentBRef, store), List.copyOf(resolvedRoles));
        }
        UUID inheritedOwner = BreedingInheritedOwnerResolver.resolve(
                config,
                targetRole,
                context.parentAOwner(),
                context.parentBOwner()
        ).ownerId();
        PopulationAdmissionLocation destination =
                new PopulationAdmissionLocation(
                        worldName,
                        ChunkUtil.chunkCoordinate((int) Math.floor(spawn.x)),
                        ChunkUtil.chunkCoordinate((int) Math.floor(spawn.z))
                );
        PopulationAdmissionRequest admission =
                new PopulationAdmissionRequest(
                        new PopulationAdmissionIdentity(
                                null,
                                childIds.getFirst().toString(),
                                "breeding-litter:" + litterId
                        ),
                        null,
                        PopulationAdmissionRequest.NEW_PROFILE_REVISION,
                        null,
                        inheritedOwner,
                        null,
                        destination,
                        PopulationAdmissionOperation.BREEDING,
                        1,
                        PopulationAdmissionForcePolicy.ENFORCE,
                        PopulationCompanionLifecycle.ACTIVE
                );
        PopulationAdmissionRequestV3 v3 = new PopulationAdmissionRequestV3(
                new PopulationAdmissionRequestV2(
                        admission, targetRole, worldName
                ),
                managedProfile
        );
        return new Plan(
                litterId,
                roll,
                List.copyOf(children),
                ManagedBatchAdmissionRequest.create(
                        litterId, v3, roll.offspringCount()
                ),
                rotation(parentARef, parentBRef, store),
                List.copyOf(resolvedRoles)
        );
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

    @Nonnull
    BreedingLitterOperation operation(
            @Nonnull Plan plan,
            @Nonnull BreedingPairContext context,
            @Nonnull String worldName,
            @Nonnull PopulationAdmissionToken token,
            long requestedAtMs
    ) {
        Vector3d spawn = context.spawnAnchor();
        if (spawn == null || plan.empty()) {
            throw new IllegalArgumentException(
                    "A positive frozen litter plan is required"
            );
        }
        BreedingLitterOperation.Parent first = parent(
                context.parentAUuid(),
                context.parentARoleId(),
                context.parentARoleIndex(),
                context.parentAOwner(),
                context.parentATamed()
        );
        BreedingLitterOperation.Parent second = parent(
                context.parentBUuid(),
                context.parentBRoleId(),
                context.parentBRoleIndex(),
                context.parentBOwner(),
                context.parentBTamed()
        );
        BreedingLitterOperation.Parent parentA = compare(
                first.uuid(), second.uuid()
        ) < 0 ? first : second;
        BreedingLitterOperation.Parent parentB = parentA == first
                ? second : first;
        boolean firstIsSortedParentA = parentA == first;
        return new BreedingLitterOperation(
                plan.litterId(),
                parentA,
                parentB,
                worldName,
                spawn.x,
                spawn.y,
                spawn.z,
                plan.rotation().yaw(),
                plan.rotation().pitch(),
                plan.rotation().roll(),
                context.breedingConfigId(),
                firstIsSortedParentA
                        ? plan.fertility().parentAMultiplier()
                        : plan.fertility().parentBMultiplier(),
                firstIsSortedParentA
                        ? plan.fertility().parentBMultiplier()
                        : plan.fertility().parentAMultiplier(),
                plan.fertility().expectedOffspring(),
                plan.children().size(),
                plan.children(),
                token,
                requestedAtMs
        );
    }

    private static BreedingLitterOperation.Parent parent(
            UUID uuid,
            String roleId,
            int roleIndex,
            BreedingOffspringProgressionService.OwnerSnapshot owner,
            boolean tamed
    ) {
        return new BreedingLitterOperation.Parent(
                uuid,
                roleId,
                roleIndex,
                owner.ownerId(),
                owner.ownerName(),
                tamed
        );
    }

    private static Rotation3f rotation(
            Ref<EntityStore> parentA,
            Ref<EntityStore> parentB,
            Store<EntityStore> store
    ) {
        TransformComponent a = store.getComponent(
                parentA, TransformComponent.getComponentType()
        );
        TransformComponent b = store.getComponent(
                parentB, TransformComponent.getComponentType()
        );
        if (a != null && b != null) {
            Vector3d delta = new Vector3d(b.getPosition())
                    .sub(a.getPosition());
            if (delta.lengthSquared() > 0.00001) {
                return TameworkRotationUtil.lookAt(delta);
            }
        }
        TransformComponent fallback = a != null ? a : b;
        return fallback == null
                ? new Rotation3f()
                : new Rotation3f(fallback.getRotation());
    }

    private static int compare(UUID left, UUID right) {
        return left.toString().compareTo(right.toString());
    }

    record Plan(
            @Nullable UUID litterId,
            @Nonnull BreedingFertilityOffspringService.FertilityRoll fertility,
            @Nonnull List<BreedingLitterOperation.ChildPlan> children,
            @Nullable ManagedBatchAdmissionRequest admission,
            @Nonnull Rotation3f rotation,
            @Nonnull List<BreedingResolvedSpawnRole> resolvedRoles
    ) {
        static Plan empty(
                BreedingFertilityOffspringService.FertilityRoll fertility
        ) {
            return new Plan(
                    null, fertility, List.of(), null, new Rotation3f(), List.of()
            );
        }

        boolean empty() {
            return children.isEmpty();
        }
    }
}
