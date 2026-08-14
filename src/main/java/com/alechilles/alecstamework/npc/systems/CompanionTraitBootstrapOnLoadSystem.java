package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionBootstrapService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import javax.annotation.Nonnull;

/**
 * Ensures trait components and trait-driven effects are initialized for NPCs as soon as they load into the world.
 *
 * <p>This allows visual/combat-affecting traits (for example Size and Toughness) to exist before taming.
 */
public final class CompanionTraitBootstrapOnLoadSystem extends RefSystem<EntityStore> {
    private final ComponentType<EntityStore, NPCEntity> npcType;

    public CompanionTraitBootstrapOnLoadSystem(ComponentType<EntityStore, NPCEntity> npcType) {
        this.npcType = npcType;
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> reference,
                              @Nonnull AddReason reason,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (npcType == null) {
            return;
        }
        if (store.getComponent(reference, npcType) == null) {
            return;
        }
        String roleId = CompanionRoleIdResolver.resolveRoleId(reference, store);
        if (roleId == null || roleId.isBlank()) {
            return;
        }
        TwTraitConfig config = TwTraitConfig.resolveForRole(roleId);
        if (config == null || !config.isEnabled()) {
            return;
        }
        CompanionTraitBootstrapPlan plan = bootstrapPlan(reference, store, config);
        if (plan == CompanionTraitBootstrapPlan.NONE) {
            return;
        }
        commandBuffer.run(bufferStore -> {
            if (bufferStore == null || reference == null || !reference.isValid()) {
                return;
            }
            if (bufferStore.getComponent(reference, npcType) == null) {
                return;
            }
            String currentRoleId = CompanionRoleIdResolver.resolveRoleId(reference, bufferStore);
            if (currentRoleId == null || currentRoleId.isBlank()) {
                return;
            }
            TwTraitConfig currentConfig = TwTraitConfig.resolveForRole(currentRoleId);
            if (currentConfig == null || !currentConfig.isEnabled()) {
                return;
            }
            CompanionTraitBootstrapPlan currentPlan = bootstrapPlan(reference, bufferStore, currentConfig);
            if (currentPlan == CompanionTraitBootstrapPlan.LIFE_STAGE_ONLY) {
                CompanionProgressionBootstrapService.ensureLifeStageOnLoad(
                        reference,
                        bufferStore,
                        currentRoleId
                );
            } else if (currentPlan == CompanionTraitBootstrapPlan.FULL_REPAIR) {
                CompanionProgressionBootstrapService.ensureTraitComponents(
                        reference,
                        bufferStore,
                        currentRoleId
                );
            }
        });
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> reference,
                               @Nonnull RemoveReason reason,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        // No-op.
    }

    @Override
    public Query<EntityStore> getQuery() {
        if (npcType == null) {
            return Query.any();
        }
        return Query.and(npcType);
    }

    private CompanionTraitBootstrapPlan bootstrapPlan(@Nonnull Ref<EntityStore> reference,
                                                      @Nonnull Store<EntityStore> store,
                                                      @Nonnull TwTraitConfig config) {
        var traitsType = TameworkTraitsComponent.getComponentType();
        if (traitsType == null) {
            return CompanionTraitBootstrapPlan.NONE;
        }
        TameworkTraitsComponent traits = store.getComponent(reference, traitsType);
        var lifeStageType = TameworkLifeStageComponent.getComponentType();
        boolean lifeStagePresent = lifeStageType == null || store.getComponent(reference, lifeStageType) != null;
        return CompanionTraitBootstrapPlan.classify(traits, config.getId(), lifeStagePresent);
    }
}
