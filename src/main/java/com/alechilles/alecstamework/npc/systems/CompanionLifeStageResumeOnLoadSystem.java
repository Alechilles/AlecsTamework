package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
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
 * Restarts life-stage growth progression for NPCs that already have an active lifecycle component after load/unload.
 *
 * <p>This is intentionally independent from taming/trait bootstrap systems so bred offspring keep progressing even
 * when they are not covered by those other bootstrap gates.
 */
public final class CompanionLifeStageResumeOnLoadSystem extends RefSystem<EntityStore> {
    private final ComponentType<EntityStore, NPCEntity> npcType;
    private final ComponentType<EntityStore, TameworkLifeStageComponent> lifeStageType;

    public CompanionLifeStageResumeOnLoadSystem(ComponentType<EntityStore, NPCEntity> npcType,
                                                ComponentType<EntityStore, TameworkLifeStageComponent> lifeStageType) {
        this.npcType = npcType;
        this.lifeStageType = lifeStageType;
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> reference,
                              @Nonnull AddReason reason,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (npcType == null || lifeStageType == null) {
            return;
        }
        if (store.getComponent(reference, npcType) == null) {
            return;
        }
        TameworkLifeStageComponent stage = store.getComponent(reference, lifeStageType);
        if (stage == null || !stage.isGrowthScalingEnabled()) {
            return;
        }
        commandBuffer.run(bufferStore -> {
            if (bufferStore == null || reference == null || !reference.isValid()) {
                return;
            }
            if (bufferStore.getComponent(reference, npcType) == null) {
                return;
            }
            TameworkLifeStageComponent latestStage = bufferStore.getComponent(reference, lifeStageType);
            if (latestStage == null || !latestStage.isGrowthScalingEnabled()) {
                return;
            }
            NPCEntity npc = bufferStore.getComponent(reference, npcType);
            CompanionLifeStageService.refreshLifeStage(reference, npc, bufferStore);
            CompanionLifeStageService.ensureGrowthTickScheduled(reference, npc, bufferStore);
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
        if (npcType == null || lifeStageType == null) {
            return Query.any();
        }
        return Query.and(npcType, lifeStageType);
    }
}
