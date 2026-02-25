package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionBootstrapService;
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
 * Ensures progression components exist for tamed companions when NPC entities are loaded into the world store.
 *
 * <p>This self-heals missing shared happiness/breeding/traits state after reloads.
 */
public final class CompanionProgressionBootstrapOnLoadSystem extends RefSystem<EntityStore> {
    private final ComponentType<EntityStore, NPCEntity> npcType;
    private final ComponentType<EntityStore, TameworkTamedComponent> tamedType;

    public CompanionProgressionBootstrapOnLoadSystem(ComponentType<EntityStore, NPCEntity> npcType,
                                                     ComponentType<EntityStore, TameworkTamedComponent> tamedType) {
        this.npcType = npcType;
        this.tamedType = tamedType;
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> reference,
                              @Nonnull AddReason reason,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (npcType == null || tamedType == null) {
            return;
        }
        if (store.getComponent(reference, npcType) == null) {
            return;
        }
        TameworkTamedComponent tamed = store.getComponent(reference, tamedType);
        if (tamed == null || !tamed.isTamed()) {
            return;
        }
        commandBuffer.run(bufferStore -> {
            if (bufferStore == null || reference == null || !reference.isValid()) {
                return;
            }
            if (bufferStore.getComponent(reference, npcType) == null) {
                return;
            }
            TameworkTamedComponent latestTamed = bufferStore.getComponent(reference, tamedType);
            if (latestTamed == null || !latestTamed.isTamed()) {
                return;
            }
            CompanionProgressionBootstrapService.ensureProgressionComponents(reference, bufferStore);
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
        if (npcType == null || tamedType == null) {
            return Query.any();
        }
        return Query.and(npcType, tamedType);
    }
}
