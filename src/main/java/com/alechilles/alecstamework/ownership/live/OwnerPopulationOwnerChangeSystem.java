package com.alechilles.alecstamework.ownership.live;

import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Keeps the live owner population index synchronized when an NPC owner component changes.
 */
public final class OwnerPopulationOwnerChangeSystem
        extends RefChangeSystem<EntityStore, TameworkOwnerComponent> {
    private final OwnerPopulationLiveIndex index;
    private final ComponentType<EntityStore, NPCEntity> npcType;
    private final ComponentType<EntityStore, TameworkOwnerComponent> ownerType;
    private final Query<EntityStore> query;

    public OwnerPopulationOwnerChangeSystem(
            @Nonnull OwnerPopulationLiveIndex index,
            @Nonnull ComponentType<EntityStore, NPCEntity> npcType,
            @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> ownerType
    ) {
        this.index = Objects.requireNonNull(index, "index");
        this.npcType = Objects.requireNonNull(npcType, "npcType");
        this.ownerType = Objects.requireNonNull(ownerType, "ownerType");
        this.query = Query.and(npcType);
    }

    @Override
    public ComponentType<EntityStore, TameworkOwnerComponent> componentType() {
        return ownerType;
    }

    @Override
    public void onComponentAdded(@Nonnull Ref<EntityStore> ref,
                                 @Nonnull TameworkOwnerComponent component,
                                 @Nonnull Store<EntityStore> store,
                                 @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        observe(ref, component, store);
    }

    @Override
    public void onComponentSet(@Nonnull Ref<EntityStore> ref,
                               @Nullable TameworkOwnerComponent previous,
                               @Nonnull TameworkOwnerComponent component,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        observe(ref, component, store);
    }

    @Override
    public void onComponentRemoved(@Nonnull Ref<EntityStore> ref,
                                   @Nonnull TameworkOwnerComponent component,
                                   @Nonnull Store<EntityStore> store,
                                   @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        NPCEntity npc = store.getComponent(ref, npcType);
        index.remove(npc == null ? null : npc.getUuid());
    }

    private void observe(Ref<EntityStore> ref,
                         TameworkOwnerComponent owner,
                         Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(ref, npcType);
        index.observe(
                npc == null ? null : npc.getUuid(),
                owner == null ? null : owner.getOwnerId(),
                worldName(store),
                CompanionRoleIdResolver.resolveRoleId(ref, store)
        );
    }

    @Nullable
    private static String worldName(Store<EntityStore> store) {
        EntityStore entityStore = store.getExternalData();
        World world = entityStore == null ? null : entityStore.getWorld();
        return world == null ? null : world.getName();
    }

    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }
}
