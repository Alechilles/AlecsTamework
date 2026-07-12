package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Rolls fertility once and resolves every child role and exact inherited owner once. */
final class BreedingBirthPlanService {
    private final BreedingFertilityOffspringService fertilityService;
    private final BreedingOffspringSpawnService spawnService;
    private final BreedingPopulationTypeService populationTypeService;

    BreedingBirthPlanService(BreedingFertilityOffspringService fertilityService,
                             BreedingOffspringSpawnService spawnService) {
        this.fertilityService = fertilityService;
        this.spawnService = spawnService;
        this.populationTypeService = new BreedingPopulationTypeService();
    }

    @Nullable
    BreedingBirthPlan plan(@Nullable Ref<EntityStore> parentARef,
                           @Nullable Ref<EntityStore> parentBRef,
                           @Nonnull Store<EntityStore> store,
                           @Nullable String parentARoleId,
                           @Nullable String parentBRoleId,
                           int parentARoleIndex,
                           int parentBRoleIndex,
                           @Nullable TwBreedingConfig config,
                           @Nonnull BreedingOffspringProgressionService.OwnerSnapshot parentAOwner,
                           @Nonnull BreedingOffspringProgressionService.OwnerSnapshot parentBOwner) {
        BreedingFertilityOffspringService.FertilityRoll fertility =
                fertilityService.rollOffspring(parentARef, parentBRef, store);
        if (fertility.offspringCount() <= 0) {
            return new BreedingBirthPlan(fertility, List.of());
        }
        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin == null) {
            return null;
        }
        List<BreedingBirthPlan.PlannedChild> children = new ArrayList<>(fertility.offspringCount());
        for (int index = 0; index < fertility.offspringCount(); index++) {
            BreedingResolvedSpawnRole spawnRole = spawnService.resolveSpawnRole(
                    parentARoleId,
                    parentBRoleId,
                    config,
                    parentARoleIndex,
                    parentBRoleIndex,
                    npcPlugin,
                    Math.random(),
                    Math.random()
            );
            if (spawnRole == null) {
                continue;
            }
            BreedingOffspringProgressionService.OwnerSnapshot owner =
                    BreedingPlannedOwnerResolver.resolve(
                            config,
                            spawnRole.roleId(),
                            parentAOwner,
                            parentBOwner
                    );
            String populationType = populationTypeService.resolveTypeKey(spawnRole.roleId(), config);
            if (populationType == null || populationType.isBlank()) {
                populationType = spawnRole.roleId();
            }
            children.add(new BreedingBirthPlan.PlannedChild(
                    "child-" + index,
                    spawnRole,
                    owner,
                    populationType
            ));
        }
        return new BreedingBirthPlan(fertility, children);
    }
}
