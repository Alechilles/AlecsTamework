package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.live.TameworkCompanionComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Stamps newly owned and tamed NPCs and keeps the owner in step (spec 8.1). Adding a component to
 * a live entity fires only {@link RefChangeSystem} callbacks, and an entity added with both
 * components already present fires only {@link RefSystem#onEntityAdded}, so both paths are covered.
 */
public final class CompanionOwnershipSystems {
    private CompanionOwnershipSystems() {
    }

    /** An owned, tamed NPC added to a store without a stamp (for example spawned already owned). */
    public static final class OnAdd extends RefSystem<EntityStore> {
        private final CompanionBodyLifecycle lifecycle;
        private final Query<EntityStore> query;

        public OnAdd(@Nonnull CompanionBodyLifecycle lifecycle, @Nonnull ComponentType<EntityStore, NPCEntity> npc,
                     @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> owner,
                     @Nonnull ComponentType<EntityStore, TameworkTamedComponent> tamed,
                     @Nonnull ComponentType<EntityStore, TameworkCompanionComponent> stamp) {
            this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
            this.query = Query.and(npc, owner, tamed, Query.not(stamp));
        }

        @Override
        public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
                                  @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {
            admit(lifecycle, ref, store, buffer);
        }

        /**
         * Stamps an owned, tamed, unstamped NPC; also used by the startup pass. World thread only.
         *
         * @return true when the NPC was owned and tamed, so a tame was attempted
         */
        static boolean admit(@Nonnull CompanionBodyLifecycle lifecycle, @Nonnull Ref<EntityStore> ref,
                             @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {
            if (!isTamed(store, ref)) {
                return false;
            }
            lifecycle.tame(ref, store, buffer);
            return true;
        }

        @Override
        public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
                                   @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {
        }

        @Override
        @Nonnull
        public Query<EntityStore> getQuery() {
            return query;
        }
    }

    /** Owner put on a live NPC: tame when it is tamed and unstamped; follow the owner when stamped. */
    public static final class OwnerChanged extends RefChangeSystem<EntityStore, TameworkOwnerComponent> {
        private final CompanionBodyLifecycle lifecycle;
        private final ComponentType<EntityStore, TameworkOwnerComponent> ownerType;
        private final ComponentType<EntityStore, TameworkCompanionComponent> stampType;
        private final Query<EntityStore> query;

        public OwnerChanged(@Nonnull CompanionBodyLifecycle lifecycle, @Nonnull ComponentType<EntityStore, NPCEntity> npc,
                            @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> owner,
                            @Nonnull ComponentType<EntityStore, TameworkCompanionComponent> stamp) {
            this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
            this.ownerType = Objects.requireNonNull(owner, "owner");
            this.stampType = Objects.requireNonNull(stamp, "stamp");
            this.query = Query.and(npc, owner);
        }

        @Override
        @Nonnull
        public ComponentType<EntityStore, TameworkOwnerComponent> componentType() {
            return ownerType;
        }

        @Override
        public void onComponentAdded(@Nonnull Ref<EntityStore> ref, @Nonnull TameworkOwnerComponent component,
                                     @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {
            changed(ref, component, store, buffer);
        }

        @Override
        public void onComponentSet(@Nonnull Ref<EntityStore> ref, @Nullable TameworkOwnerComponent oldComponent,
                                   @Nonnull TameworkOwnerComponent newComponent, @Nonnull Store<EntityStore> store,
                                   @Nonnull CommandBuffer<EntityStore> buffer) {
            changed(ref, newComponent, store, buffer);
        }

        @Override
        public void onComponentRemoved(@Nonnull Ref<EntityStore> ref, @Nonnull TameworkOwnerComponent component,
                                       @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {
            // Release flows change the record before they clear the owner.
        }

        @Override
        @Nonnull
        public Query<EntityStore> getQuery() {
            return query;
        }

        private void changed(Ref<EntityStore> ref, TameworkOwnerComponent owner, Store<EntityStore> store,
                             CommandBuffer<EntityStore> buffer) {
            if (store.getComponent(ref, stampType) != null) {
                lifecycle.ownerChanged(ref, store, owner.getOwnerId(), owner.getOwnerName());
            } else if (isTamed(store, ref)) {
                lifecycle.tame(ref, store, buffer);
            }
        }
    }

    /** Tamed set on a live owned NPC. */
    public static final class TamedChanged extends RefChangeSystem<EntityStore, TameworkTamedComponent> {
        private final CompanionBodyLifecycle lifecycle;
        private final ComponentType<EntityStore, TameworkTamedComponent> tamedType;
        private final Query<EntityStore> query;

        public TamedChanged(@Nonnull CompanionBodyLifecycle lifecycle, @Nonnull ComponentType<EntityStore, NPCEntity> npc,
                            @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> owner,
                            @Nonnull ComponentType<EntityStore, TameworkTamedComponent> tamed) {
            this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
            this.tamedType = Objects.requireNonNull(tamed, "tamed");
            this.query = Query.and(npc, owner, tamed);
        }

        @Override
        @Nonnull
        public ComponentType<EntityStore, TameworkTamedComponent> componentType() {
            return tamedType;
        }

        @Override
        public void onComponentAdded(@Nonnull Ref<EntityStore> ref, @Nonnull TameworkTamedComponent component,
                                     @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {
            if (isTamed(store, ref)) {
                lifecycle.tame(ref, store, buffer);
            }
        }

        @Override
        public void onComponentSet(@Nonnull Ref<EntityStore> ref, @Nullable TameworkTamedComponent oldComponent,
                                   @Nonnull TameworkTamedComponent newComponent, @Nonnull Store<EntityStore> store,
                                   @Nonnull CommandBuffer<EntityStore> buffer) {
            if (isTamed(store, ref)) {
                lifecycle.tame(ref, store, buffer);
            }
        }

        @Override
        public void onComponentRemoved(@Nonnull Ref<EntityStore> ref, @Nonnull TameworkTamedComponent component,
                                       @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {
        }

        @Override
        @Nonnull
        public Query<EntityStore> getQuery() {
            return query;
        }
    }

    static boolean isTamed(Store<EntityStore> store, Ref<EntityStore> ref) {
        TameworkTamedComponent tamed = store.getComponent(ref, TameworkTamedComponent.getComponentType());
        TameworkOwnerComponent owner = store.getComponent(ref, TameworkOwnerComponent.getComponentType());
        return tamed != null && tamed.isTamed() && owner != null && owner.getOwnerId() != null;
    }
}
