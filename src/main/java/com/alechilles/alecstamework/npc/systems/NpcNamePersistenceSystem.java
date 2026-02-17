package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
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
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import javax.annotation.Nonnull;

/**
 * Re-applies persisted Tamework NPC names when entities are added to the store.
 */
public final class NpcNamePersistenceSystem extends RefSystem<EntityStore> {
    private final ComponentType<EntityStore, TameworkNpcNameComponent> nameType;
    private final ComponentType<EntityStore, NPCEntity> npcType;

    public NpcNamePersistenceSystem(ComponentType<EntityStore, TameworkNpcNameComponent> nameType,
                                    ComponentType<EntityStore, NPCEntity> npcType) {
        this.nameType = nameType;
        this.npcType = npcType;
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> reference,
                              @Nonnull AddReason reason,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (nameType == null || npcType == null) {
            return;
        }
        if (store.getComponent(reference, npcType) == null) {
            return;
        }
        TameworkNpcNameComponent nameComponent = store.getComponent(reference, nameType);
        if (nameComponent == null) {
            return;
        }
        String name = nameComponent.getName();
        if (name == null || name.isBlank()) {
            return;
        }
        EntitySupport.setDisplayName(reference, name, true, commandBuffer);
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
        if (nameType == null || npcType == null) {
            return Query.any();
        }
        return Query.and(nameType, npcType);
    }
}
