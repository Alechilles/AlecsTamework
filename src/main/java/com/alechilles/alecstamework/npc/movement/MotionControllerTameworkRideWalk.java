package com.alechilles.alecstamework.npc.movement;

import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.movement.Steering;
import com.hypixel.hytale.server.npc.movement.controllers.MotionControllerWalk;
import com.hypixel.hytale.server.npc.role.Role;
import javax.annotation.Nonnull;

/**
 * Vanilla walk plumbing with a rider-specific speed ceiling for mounted land movement.
 */
public final class MotionControllerTameworkRideWalk extends MotionControllerWalk {
    private final double mountedMaxWalkSpeed;
    private final double mountedSprintMultiplier;
    private boolean ridden;
    private boolean riderSprinting;

    public MotionControllerTameworkRideWalk(@Nonnull BuilderMotionControllerTameworkRideWalk builder,
                                            @Nonnull BuilderSupport builderSupport) {
        super(builder, builderSupport);
        this.mountedMaxWalkSpeed = builder.getMountedMaxWalkSpeed(builderSupport, maxHorizontalSpeed);
        this.mountedSprintMultiplier = builder.getMountedSprintMultiplier(builderSupport);
    }

    @Nonnull
    @Override
    public String getType() {
        return BuilderMotionControllerTameworkRideWalk.BUILDER_ID;
    }

    @Override
    protected double computeMove(@Nonnull Ref<EntityStore> ref,
                                 @Nonnull Role role,
                                 @Nonnull Steering steering,
                                 double dt,
                                 @Nonnull Vector3d translation,
                                 @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        TameworkRideMountComponent ride = rideMount(ref, componentAccessor);
        ridden = ride != null;
        riderSprinting = ride != null && ride.isRiderSprinting();
        return super.computeMove(ref, role, steering, dt, translation, componentAccessor);
    }

    @Override
    public double getMaximumSpeed() {
        if (!ridden) {
            return super.getMaximumSpeed();
        }
        return getMountedClientSpeed(riderSprinting);
    }

    public double getMountedClientSpeed(boolean riderSprinting) {
        double sprintMultiplier = riderSprinting ? mountedSprintMultiplier : 1.0;
        return mountedMaxWalkSpeed * horizontalSpeedMultiplier * effectHorizontalSpeedMultiplier * sprintMultiplier;
    }

    @Override
    public void deactivate() {
        ridden = false;
        riderSprinting = false;
        super.deactivate();
    }

    private TameworkRideMountComponent rideMount(@Nonnull Ref<EntityStore> ref,
                                                 @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        ComponentType<EntityStore, TameworkRideMountComponent> type = TameworkRideMountComponent.getComponentType();
        return type == null ? null : componentAccessor.getComponent(ref, type);
    }
}
