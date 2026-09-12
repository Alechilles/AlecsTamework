package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.movement.CompanionFollowFlockService;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.flock.FlockMembership;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Indexes membership events, including save loads; callbacks retain only entity IDs. */
public final class CompanionFollowFlockMembershipSystems {
    private CompanionFollowFlockMembershipSystems() { }

    private static void observe(Ref<EntityStore> ref, Store<EntityStore> store) {
        var id = store.getComponent(ref, UUIDComponent.getComponentType());
        var tame = store.getComponent(ref, TameworkTamedComponent.getComponentType());
        if (id != null && tame != null && tame.isTamed()
                && store.getComponent(ref, TameworkOwnerComponent.getComponentType()) != null) {
            CompanionFollowFlockService.get().observeMembership(id.getUuid(), store);
        }
    }

    private static void forget(Ref<EntityStore> ref, Store<EntityStore> store) {
        var id = store.getComponent(ref, UUIDComponent.getComponentType());
        if (id != null) CompanionFollowFlockService.get().forgetMembership(id.getUuid(), store);
    }

    public static final class EntityRef extends RefSystem<EntityStore> {
        private final Query<EntityStore> query = Query.and(NPCEntity.getComponentType(),
                UUIDComponent.getComponentType(), FlockMembership.getComponentType(),
                TameworkTamedComponent.getComponentType(), TameworkOwnerComponent.getComponentType());
        @Override public Query<EntityStore> getQuery() { return query; }
        @Override public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {
            observe(ref, store);
        }
        @Override public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {
            forget(ref, store);
        }
    }

    public static final class MembershipChange extends RefChangeSystem<EntityStore, FlockMembership> {
        private final Query<EntityStore> query = Query.and(NPCEntity.getComponentType(), UUIDComponent.getComponentType());
        @Override public Query<EntityStore> getQuery() { return query; }
        @Override public ComponentType<EntityStore, FlockMembership> componentType() {
            return FlockMembership.getComponentType();
        }
        @Override public void onComponentAdded(@Nonnull Ref<EntityStore> ref, @Nonnull FlockMembership membership,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {
            observe(ref, store);
        }
        @Override public void onComponentSet(@Nonnull Ref<EntityStore> ref, @Nullable FlockMembership previous,
                @Nonnull FlockMembership membership, @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> buffer) {
            observe(ref, store);
        }
        @Override public void onComponentRemoved(@Nonnull Ref<EntityStore> ref, @Nonnull FlockMembership membership,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {
            forget(ref, store);
        }
    }
}
