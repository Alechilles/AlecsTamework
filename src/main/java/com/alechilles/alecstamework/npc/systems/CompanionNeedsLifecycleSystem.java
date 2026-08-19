package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.progression.CompanionNeedsRuntimeRegistry;
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
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Observes tamed NPC lifecycle changes and updates the store-scoped needs registry. */
public final class CompanionNeedsLifecycleSystem extends RefSystem<EntityStore> {
    private final CompanionNeedsRuntimeRegistry registry;
    private final ComponentType<EntityStore, NPCEntity> npcType;
    private final ComponentType<EntityStore, TameworkTamedComponent> tamedType;
    private final Query<EntityStore> query;

    public CompanionNeedsLifecycleSystem(
            @Nonnull CompanionNeedsRuntimeRegistry registry,
            @Nonnull ComponentType<EntityStore, NPCEntity> npcType,
            @Nonnull ComponentType<EntityStore, TameworkTamedComponent> tamedType) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.npcType = Objects.requireNonNull(npcType, "npcType");
        this.tamedType = Objects.requireNonNull(tamedType, "tamedType");
        this.query = Query.and(npcType, tamedType);
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> reference,
                              @Nonnull AddReason reason,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        NPCEntity npc = store.getComponent(reference, npcType);
        TameworkTamedComponent tamed = store.getComponent(reference, tamedType);
        if (npc == null || tamed == null || !tamed.isTamed()) {
            return;
        }
        UUID npcId = npc.getUuid();
        if (npcId != null) {
            registry.register(store, npcId, System.currentTimeMillis());
        }
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> reference,
                               @Nonnull RemoveReason reason,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        NPCEntity npc = store.getComponent(reference, npcType);
        UUID npcId = npc == null ? null : npc.getUuid();
        if (npcId != null) {
            registry.remove(store, npcId);
        }
    }

    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }
}
