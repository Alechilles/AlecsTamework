package com.alechilles.alecstamework.companion.live;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
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
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import javax.annotation.Nonnull;

/**
 * Applies the generation fence to companion bodies as they enter a world, and reports
 * removals of the registered body (spec 6.8). Registered by the phase 3 composition only.
 */
public final class CompanionBodySystem extends RefSystem<EntityStore> {
    private final ComponentType<EntityStore, TameworkCompanionComponent> stampType;
    private final ComponentType<EntityStore, TameworkOwnerComponent> ownerType;
    private final ComponentType<EntityStore, TameworkTamedComponent> tamedType;
    private final CompanionIndex index;
    private final Predicate<UUID> unreadable;
    private final LoadedBodies<Ref<EntityStore>> loaded;
    private final CompanionBodyCallbacks callbacks;
    private final Set<Dependency<EntityStore>> dependencies =
            Set.of(new SystemDependency<>(Order.AFTER, EntityStore.UUIDSystem.class));

    public CompanionBodySystem(@Nonnull ComponentType<EntityStore, TameworkCompanionComponent> stampType,
                               @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> ownerType,
                               @Nonnull ComponentType<EntityStore, TameworkTamedComponent> tamedType,
                               @Nonnull CompanionIndex index,
                               @Nonnull Predicate<UUID> unreadable,
                               @Nonnull LoadedBodies<Ref<EntityStore>> loaded,
                               @Nonnull CompanionBodyCallbacks callbacks) {
        this.stampType = Objects.requireNonNull(stampType, "stampType");
        this.ownerType = Objects.requireNonNull(ownerType, "ownerType");
        this.tamedType = Objects.requireNonNull(tamedType, "tamedType");
        this.index = Objects.requireNonNull(index, "index");
        this.unreadable = Objects.requireNonNull(unreadable, "unreadable");
        this.loaded = Objects.requireNonNull(loaded, "loaded");
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
                              @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {
        TameworkCompanionComponent stamp = store.getComponent(ref, stampType);
        if (stamp == null || stamp.getProfileId() == null) {
            return;
        }
        UUID profileId = stamp.getProfileId();
        FenceAction action = index.atomically(() -> {
            Ref<EntityStore> other = loaded.get(profileId);
            boolean anotherLoaded = other != null && other.isValid() && !other.equals(ref);
            FenceAction decided = CompanionFence.decide(index.get(profileId), unreadable.test(profileId),
                    stamp.getGeneration(), isOwnedAndTamed(store, ref), anotherLoaded);
            if (decided == FenceAction.ACCEPT || decided == FenceAction.ACCEPT_AND_RAISE || decided == FenceAction.ADOPT) {
                loaded.put(profileId, ref);
            }
            return decided;
        });
        switch (action) {
            case ACCEPT -> callbacks.onAccepted(ref, store, buffer, stamp, false);
            case ACCEPT_AND_RAISE -> callbacks.onAccepted(ref, store, buffer, stamp, true);
            case ADOPT -> callbacks.onAdopt(ref, store, buffer, stamp);
            case REMOVE -> buffer.removeEntity(ref, RemoveReason.REMOVE);
            case IGNORE -> { }
        }
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
                               @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {
        TameworkCompanionComponent stamp = store.getComponent(ref, stampType);
        if (stamp == null || stamp.getProfileId() == null) {
            return;
        }
        if (loaded.removeIfSame(stamp.getProfileId(), ref)) {
            callbacks.onLoadedBodyRemoved(ref, reason, store, buffer, stamp.getProfileId());
        }
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return stampType;
    }

    @Override
    @Nonnull
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    private boolean isOwnedAndTamed(Store<EntityStore> store, Ref<EntityStore> ref) {
        TameworkOwnerComponent owner = store.getComponent(ref, ownerType);
        TameworkTamedComponent tamed = store.getComponent(ref, tamedType);
        return owner != null && owner.getOwnerId() != null && tamed != null && tamed.isTamed();
    }
}
