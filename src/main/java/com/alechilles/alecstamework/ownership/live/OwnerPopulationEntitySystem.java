package com.alechilles.alecstamework.ownership.live;

import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Adds and removes loaded NPCs from the non-persistent owner population index.
 */
public final class OwnerPopulationEntitySystem extends RefSystem<EntityStore> {
    private final OwnerPopulationLiveIndex index;
    private final ComponentType<EntityStore, NPCEntity> npcType;
    private final ComponentType<EntityStore, TameworkOwnerComponent> ownerType;
    private final Query<EntityStore> query;

    public OwnerPopulationEntitySystem(
            @Nonnull OwnerPopulationLiveIndex index,
            @Nonnull ComponentType<EntityStore, NPCEntity> npcType,
            @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> ownerType
    ) {
        this.index = Objects.requireNonNull(index, "index");
        this.npcType = Objects.requireNonNull(npcType, "npcType");
        this.ownerType = Objects.requireNonNull(ownerType, "ownerType");
        this.query = Query.and(npcType, ownerType);
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> reference,
                              @Nonnull AddReason reason,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        observe(reference, store);
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> reference,
                               @Nonnull RemoveReason reason,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        NPCEntity npc = store.getComponent(reference, npcType);
        index.remove(npc == null ? null : npc.getUuid());
    }

    private void observe(Ref<EntityStore> reference, Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(reference, npcType);
        TameworkOwnerComponent owner = store.getComponent(reference, ownerType);
        index.observe(
                npc == null ? null : npc.getUuid(),
                owner == null ? null : owner.getOwnerId(),
                worldName(store),
                CompanionRoleIdResolver.resolveRoleId(reference, store)
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
