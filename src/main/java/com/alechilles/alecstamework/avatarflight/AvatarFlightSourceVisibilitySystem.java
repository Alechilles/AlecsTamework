package com.alechilles.alecstamework.avatarflight;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Iterator;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Hides parked avatar-flight source NPCs from every entity viewer until their session ends. */
public final class AvatarFlightSourceVisibilitySystem extends EntityTickingSystem<EntityStore> {
    private final ComponentType<EntityStore, AvatarFlightSourceComponent> sourceType;
    private final ComponentType<EntityStore, EntityTrackerSystems.EntityViewer> viewerType;
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, EntityTrackerSystems.CollectVisible.class)
    );

    public AvatarFlightSourceVisibilitySystem(
            @Nonnull ComponentType<EntityStore, AvatarFlightSourceComponent> sourceType,
            @Nonnull ComponentType<EntityStore, EntityTrackerSystems.EntityViewer> viewerType) {
        this.sourceType = sourceType;
        this.viewerType = viewerType;
    }

    @Override
    public void tick(float dt,
                     int index,
                     @Nonnull ArchetypeChunk<EntityStore> chunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        EntityTrackerSystems.EntityViewer viewer = chunk.getComponent(index, viewerType);
        if (viewer == null) return;
        for (Iterator<Ref<EntityStore>> iterator = viewer.visible.iterator(); iterator.hasNext();) {
            Ref<EntityStore> targetRef = iterator.next();
            if (targetRef != null && targetRef.isValid()
                    && commandBuffer.getComponent(targetRef, sourceType) != null) {
                iterator.remove();
                viewer.hiddenCount++;
            }
        }
    }

    @Nullable
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return EntityTrackerSystems.FIND_VISIBLE_ENTITIES_GROUP;
    }

    @Nonnull @Override public Query<EntityStore> getQuery() { return viewerType; }
    @Nonnull @Override public Set<Dependency<EntityStore>> getDependencies() { return dependencies; }
}
