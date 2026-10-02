package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.live.TameworkCompanionComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import java.util.Set;
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

    /**
     * The unstamped NPCs the add system and the startup pass look at: owned and tamed ones, and,
     * when {@code projection} is given (a world imported from 3.x or 4.x), also bodies that carry
     * only a tamed component or the retired projection identity. Those have no owner (unowned,
     * coop and released animals) and can still be the body, or a stale duplicate, of an imported
     * record.
     */
    @Nonnull
    static Query<EntityStore> unstamped(
            @Nonnull ComponentType<EntityStore, NPCEntity> npc,
            @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> owner,
            @Nonnull ComponentType<EntityStore, TameworkTamedComponent> tamed,
            @Nonnull ComponentType<EntityStore, TameworkCompanionComponent> stamp,
            @Nullable ComponentType<EntityStore, TameworkProjectionIdentityComponent> projection) {
        return projection == null ? Query.and(npc, owner, tamed, Query.not(stamp))
                : Query.and(npc, Query.not(stamp), Query.or(tamed, projection));
    }

    /**
     * An NPC added to a store without a stamp: owned and tamed (for example spawned already
     * owned), or, on an imported world, a body saved by 3.x or 4.x.
     *
     * <p>Ordering contract (plan 7 R14, R15): this system reads the retired
     * {@code TameworkProjectionIdentity} component from the store to match an old body to its
     * imported record. A system that strips retired components must run after this one
     * ({@code Order.AFTER} this class) and strip through the command buffer of the add callback,
     * never from the holder before the entity enters the store.</p>
     */
    public static final class OnAdd extends RefSystem<EntityStore> {
        private final CompanionBodyLifecycle lifecycle;
        private final Query<EntityStore> query;
        // The body's NPC UUID is its identity for the legacy alias lookup.
        private final Set<Dependency<EntityStore>> dependencies =
                Set.of(new SystemDependency<>(Order.AFTER, EntityStore.UUIDSystem.class));

        /** {@code projection} is non-null only on an imported world (see {@link #unstamped}). */
        public OnAdd(@Nonnull CompanionBodyLifecycle lifecycle, @Nonnull ComponentType<EntityStore, NPCEntity> npc,
                     @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> owner,
                     @Nonnull ComponentType<EntityStore, TameworkTamedComponent> tamed,
                     @Nonnull ComponentType<EntityStore, TameworkCompanionComponent> stamp,
                     @Nullable ComponentType<EntityStore, TameworkProjectionIdentityComponent> projection) {
            this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
            this.query = unstamped(npc, owner, tamed, stamp, projection);
        }

        @Override
        public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
                                  @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {
            admit(lifecycle, ref, store, buffer);
        }

        /**
         * Stamps an owned, tamed, unstamped NPC; also used by the startup pass. World thread only.
         * The tame path matches a 3.x/4.x body to its imported record before it mints a profile;
         * a body with no owner can only be matched, never tamed.
         *
         * @return true when the NPC was owned and tamed, so a tame was attempted
         */
        static boolean admit(@Nonnull CompanionBodyLifecycle lifecycle, @Nonnull Ref<EntityStore> ref,
                             @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {
            if (!isTamed(store, ref)) {
                lifecycle.admitLegacyBody(ref, store, buffer, false);
                return false;
            }
            // Already owned when it arrived: register it, never strip the owner over a cap.
            lifecycle.tame(ref, store, buffer, false);
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

        @Override
        @Nonnull
        public Set<Dependency<EntityStore>> getDependencies() {
            return dependencies;
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
                lifecycle.tame(ref, store, buffer, true);
            }
        }
    }

    /** Command links put on, changed on or removed from a stamped NPC: the record's tool links follow. */
    public static final class LinksChanged extends RefChangeSystem<EntityStore, TameworkCommandLinksComponent> {
        private final CompanionBodyLifecycle lifecycle;
        private final ComponentType<EntityStore, TameworkCommandLinksComponent> linksType;
        private final Query<EntityStore> query;

        public LinksChanged(@Nonnull CompanionBodyLifecycle lifecycle, @Nonnull ComponentType<EntityStore, NPCEntity> npc,
                            @Nonnull ComponentType<EntityStore, TameworkCommandLinksComponent> links,
                            @Nonnull ComponentType<EntityStore, TameworkCompanionComponent> stamp) {
            this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
            this.linksType = Objects.requireNonNull(links, "links");
            this.query = Query.and(npc, links, stamp);
        }

        @Override
        @Nonnull
        public ComponentType<EntityStore, TameworkCommandLinksComponent> componentType() {
            return linksType;
        }

        @Override
        public void onComponentAdded(@Nonnull Ref<EntityStore> ref, @Nonnull TameworkCommandLinksComponent component,
                                     @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {
            lifecycle.linksChanged(ref, store, component);
        }

        @Override
        public void onComponentSet(@Nonnull Ref<EntityStore> ref, @Nullable TameworkCommandLinksComponent oldComponent,
                                   @Nonnull TameworkCommandLinksComponent newComponent, @Nonnull Store<EntityStore> store,
                                   @Nonnull CommandBuffer<EntityStore> buffer) {
            lifecycle.linksChanged(ref, store, newComponent);
        }

        @Override
        public void onComponentRemoved(@Nonnull Ref<EntityStore> ref, @Nonnull TameworkCommandLinksComponent component,
                                       @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {
            lifecycle.linksChanged(ref, store, null);
        }

        @Override
        @Nonnull
        public Query<EntityStore> getQuery() {
            return query;
        }
    }

    /** Name put on or changed on a stamped NPC (a rename): the record's display name follows. */
    public static final class NameChanged extends RefChangeSystem<EntityStore, TameworkNpcNameComponent> {
        private final CompanionBodyLifecycle lifecycle;
        private final ComponentType<EntityStore, TameworkNpcNameComponent> nameType;
        private final Query<EntityStore> query;

        public NameChanged(@Nonnull CompanionBodyLifecycle lifecycle, @Nonnull ComponentType<EntityStore, NPCEntity> npc,
                           @Nonnull ComponentType<EntityStore, TameworkNpcNameComponent> name,
                           @Nonnull ComponentType<EntityStore, TameworkCompanionComponent> stamp) {
            this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
            this.nameType = Objects.requireNonNull(name, "name");
            this.query = Query.and(npc, name, stamp);
        }

        @Override
        @Nonnull
        public ComponentType<EntityStore, TameworkNpcNameComponent> componentType() {
            return nameType;
        }

        @Override
        public void onComponentAdded(@Nonnull Ref<EntityStore> ref, @Nonnull TameworkNpcNameComponent component,
                                     @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {
            lifecycle.nameChanged(ref, store, component.getName());
        }

        @Override
        public void onComponentSet(@Nonnull Ref<EntityStore> ref, @Nullable TameworkNpcNameComponent oldComponent,
                                   @Nonnull TameworkNpcNameComponent newComponent, @Nonnull Store<EntityStore> store,
                                   @Nonnull CommandBuffer<EntityStore> buffer) {
            lifecycle.nameChanged(ref, store, newComponent.getName());
        }

        @Override
        public void onComponentRemoved(@Nonnull Ref<EntityStore> ref, @Nonnull TameworkNpcNameComponent component,
                                       @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {
            // A body that lost its name component keeps the record's name, as on a body sighting.
        }

        @Override
        @Nonnull
        public Query<EntityStore> getQuery() {
            return query;
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
                lifecycle.tame(ref, store, buffer, true);
            }
        }

        @Override
        public void onComponentSet(@Nonnull Ref<EntityStore> ref, @Nullable TameworkTamedComponent oldComponent,
                                   @Nonnull TameworkTamedComponent newComponent, @Nonnull Store<EntityStore> store,
                                   @Nonnull CommandBuffer<EntityStore> buffer) {
            if (isTamed(store, ref)) {
                lifecycle.tame(ref, store, buffer, true);
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
