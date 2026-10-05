package com.alechilles.alecstamework.avatarflight;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.ModelTransform;
import com.hypixel.hytale.protocol.TransformUpdate;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Turns the owner's own flight model toward the look heading while flying forward.
 *
 * <p>The entity tracker never sends a player their own transform, and the client only turns its
 * body toward the camera while it has wish movement. Coasting without forward input therefore
 * leaves the owner's model on a stale heading unless the server sends the body yaw back.
 */
public final class AvatarFlightOwnerHeadingSyncSystem extends EntityTickingSystem<EntityStore> {
    static final double MIN_FORWARD_SPEED = 1.0;
    static final double YAW_TOLERANCE_RADIANS = Math.toRadians(5.0);

    private final ComponentType<EntityStore, AvatarFlightComponent> flightType;
    private final ComponentType<EntityStore, EntityTrackerSystems.Visible> visibleType;
    private final ComponentType<EntityStore, HeadRotation> headRotationType;
    private final ComponentType<EntityStore, UUIDComponent> uuidType;
    private final Query<EntityStore> query;

    public AvatarFlightOwnerHeadingSyncSystem(
            @Nonnull ComponentType<EntityStore, AvatarFlightComponent> flightType,
            @Nonnull ComponentType<EntityStore, EntityTrackerSystems.Visible> visibleType,
            @Nonnull ComponentType<EntityStore, HeadRotation> headRotationType,
            @Nonnull ComponentType<EntityStore, UUIDComponent> uuidType) {
        this.flightType = flightType;
        this.visibleType = visibleType;
        this.headRotationType = headRotationType;
        this.uuidType = uuidType;
        this.query = Query.and(flightType, visibleType, headRotationType, uuidType);
    }

    @Override
    public void tick(float dt,
                     int index,
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        AvatarFlightComponent flight = archetypeChunk.getComponent(index, flightType);
        EntityTrackerSystems.Visible visible = archetypeChunk.getComponent(index, visibleType);
        HeadRotation headRotation = archetypeChunk.getComponent(index, headRotationType);
        UUIDComponent identity = archetypeChunk.getComponent(index, uuidType);
        if (ref == null || flight == null || visible == null || headRotation == null
                || headRotation.getRotation() == null || identity == null || identity.getUuid() == null) {
            return;
        }
        EntityTrackerSystems.EntityViewer self = visible.visibleTo.get(ref);
        MovementIntentProjector.DirectionSnapshot clientBody =
                AvatarFlightPacketInputCapture.lastBodyDirection(identity.getUuid());
        if (self == null || clientBody == null) {
            return;
        }
        double lookYaw = headRotation.getRotation().yaw();
        if (!shouldSync(flight.getVelocityX(), flight.getVelocityZ(), lookYaw, clientBody.yaw())) {
            return;
        }
        // Body yaw only: the client keeps its own position, look, and body pitch/roll.
        self.queueUpdate(ref, new TransformUpdate(new ModelTransform(
                null,
                new Direction((float) lookYaw, (float) clientBody.pitch(), (float) clientBody.roll()),
                null
        )));
    }

    static boolean shouldSync(double velocityX, double velocityZ, double lookYaw, double clientBodyYaw) {
        double forwardSpeed = velocityX * -Math.sin(lookYaw) + velocityZ * -Math.cos(lookYaw);
        if (forwardSpeed < MIN_FORWARD_SPEED) {
            return false;
        }
        double error = Math.IEEEremainder(lookYaw - clientBodyYaw, 2.0 * Math.PI);
        return Math.abs(error) > YAW_TOLERANCE_RADIANS;
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
}
