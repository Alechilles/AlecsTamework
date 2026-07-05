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
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.vector.Rotation3fc;
import com.hypixel.hytale.protocol.ModelTransform;
import com.hypixel.hytale.protocol.TransformUpdate;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.system.TransformSystems;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.PositionUtil;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Sends avatar-flight pitch and roll back to the transformed player's own client.
 */
public final class AvatarFlightOwnerPoseVisualSystem extends EntityTickingSystem<EntityStore> {
    private final ComponentType<EntityStore, AvatarFlightComponent> flightType;
    private final ComponentType<EntityStore, TransformComponent> transformType;
    private final ComponentType<EntityStore, HeadRotation> headRotationType;
    private final ComponentType<EntityStore, EntityTrackerSystems.EntityViewer> viewerType;
    private final Query<EntityStore> query;
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, TransformSystems.EntityTrackerUpdate.class)
    );

    public AvatarFlightOwnerPoseVisualSystem(
            @Nonnull ComponentType<EntityStore, AvatarFlightComponent> flightType,
            @Nonnull ComponentType<EntityStore, TransformComponent> transformType,
            @Nonnull ComponentType<EntityStore, HeadRotation> headRotationType,
            @Nonnull ComponentType<EntityStore, EntityTrackerSystems.EntityViewer> viewerType) {
        this.flightType = flightType;
        this.transformType = transformType;
        this.headRotationType = headRotationType;
        this.viewerType = viewerType;
        this.query = Query.and(flightType, transformType, viewerType);
    }

    @Override
    public void tick(float dt,
                     int index,
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        TransformComponent transform = archetypeChunk.getComponent(index, transformType);
        EntityTrackerSystems.EntityViewer viewer = archetypeChunk.getComponent(index, viewerType);
        if (ref == null || transform == null || viewer == null) {
            return;
        }
        viewer.queueUpdate(ref, new TransformUpdate(createTransform(transform, resolveHeadRotation(ref, commandBuffer))));
    }

    @Nonnull
    private ModelTransform createTransform(@Nonnull TransformComponent transform,
                                           @Nonnull Rotation3fc headRotation) {
        ModelTransform modelTransform = new ModelTransform();
        modelTransform.position = PositionUtil.toPositionPacket(transform.getPosition());
        modelTransform.bodyOrientation = PositionUtil.toDirectionPacket(transform.getRotation());
        modelTransform.lookOrientation = PositionUtil.toDirectionPacket(new Rotation3f(headRotation));
        return modelTransform;
    }

    @Nonnull
    private Rotation3fc resolveHeadRotation(@Nonnull Ref<EntityStore> ref,
                                            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        HeadRotation headRotation = commandBuffer.getComponent(ref, headRotationType);
        if (headRotation == null || headRotation.getRotation() == null) {
            return Rotation3f.IDENTITY;
        }
        return headRotation.getRotation();
    }

    @Nullable
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return EntityTrackerSystems.QUEUE_UPDATE_GROUP;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }
}
