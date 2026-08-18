package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.progression.CompanionNeedsRuntimeRegistry;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Keeps needs membership synchronized when an NPC's tamed component changes. */
public final class CompanionNeedsTamedChangeSystem
        extends RefChangeSystem<EntityStore, TameworkTamedComponent> {
    private final CompanionNeedsRuntimeRegistry registry;
    private final ComponentType<EntityStore, NPCEntity> npcType;
    private final ComponentType<EntityStore, TameworkTamedComponent> tamedType;
    private final Query<EntityStore> query;

    public CompanionNeedsTamedChangeSystem(
            @Nonnull CompanionNeedsRuntimeRegistry registry,
            @Nonnull ComponentType<EntityStore, NPCEntity> npcType,
            @Nonnull ComponentType<EntityStore, TameworkTamedComponent> tamedType) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.npcType = Objects.requireNonNull(npcType, "npcType");
        this.tamedType = Objects.requireNonNull(tamedType, "tamedType");
        this.query = Query.and(npcType);
    }

    @Override
    @Nonnull
    public ComponentType<EntityStore, TameworkTamedComponent> componentType() {
        return tamedType;
    }

    @Override
    public void onComponentAdded(@Nonnull Ref<EntityStore> ref,
                                 @Nonnull TameworkTamedComponent component,
                                 @Nonnull Store<EntityStore> store,
                                 @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        observe(ref, component, store);
    }

    @Override
    public void onComponentSet(@Nonnull Ref<EntityStore> ref,
                               @Nullable TameworkTamedComponent previous,
                               @Nonnull TameworkTamedComponent component,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        observe(ref, component, store);
    }

    @Override
    public void onComponentRemoved(@Nonnull Ref<EntityStore> ref,
                                   @Nonnull TameworkTamedComponent component,
                                   @Nonnull Store<EntityStore> store,
                                   @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        remove(ref, store);
    }

    private void observe(@Nonnull Ref<EntityStore> ref,
                         @Nullable TameworkTamedComponent component,
                         @Nonnull Store<EntityStore> store) {
        UUID npcId = npcId(ref, store);
        if (npcId == null) {
            return;
        }
        if (component != null && component.isTamed()) {
            registry.register(store, npcId, System.currentTimeMillis());
        } else {
            registry.remove(store, npcId);
        }
    }

    private void remove(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        UUID npcId = npcId(ref, store);
        if (npcId != null) {
            registry.remove(store, npcId);
        }
    }

    @Nullable
    private UUID npcId(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(ref, npcType);
        return npc == null ? null : npc.getUuid();
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return query;
    }
}
