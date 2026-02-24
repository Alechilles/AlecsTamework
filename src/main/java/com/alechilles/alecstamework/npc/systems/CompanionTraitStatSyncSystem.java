package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionStatModifierService;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import javax.annotation.Nonnull;

/**
 * Reapplies trait-driven stat modifiers for NPCs when entities are added to the world store.
 *
 * <p>This keeps legacy companions (created before newer trait stat wiring) in sync after world load.
 */
public final class CompanionTraitStatSyncSystem extends RefSystem<EntityStore> {
    private final ComponentType<EntityStore, NPCEntity> npcType;
    private final ComponentType<EntityStore, EntityStatMap> statType;
    private final ComponentType<EntityStore, TameworkTraitsComponent> traitsType;

    public CompanionTraitStatSyncSystem(ComponentType<EntityStore, NPCEntity> npcType,
                                        ComponentType<EntityStore, EntityStatMap> statType,
                                        ComponentType<EntityStore, TameworkTraitsComponent> traitsType) {
        this.npcType = npcType;
        this.statType = statType;
        this.traitsType = traitsType;
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> reference,
                              @Nonnull AddReason reason,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (npcType == null || statType == null || traitsType == null) {
            return;
        }
        if (store.getComponent(reference, npcType) == null) {
            return;
        }
        if (store.getComponent(reference, statType) == null) {
            return;
        }
        if (store.getComponent(reference, traitsType) == null) {
            return;
        }
        CompanionStatModifierService.applyTraitModifiers(reference, store);
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
        if (npcType == null || statType == null || traitsType == null) {
            return Query.any();
        }
        return Query.and(npcType, statType, traitsType);
    }
}
