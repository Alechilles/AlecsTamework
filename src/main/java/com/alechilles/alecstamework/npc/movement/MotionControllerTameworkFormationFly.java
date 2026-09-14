package com.alechilles.alecstamework.npc.movement;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.movement.MotionKind;
import com.hypixel.hytale.server.npc.movement.Steering;
import com.hypixel.hytale.server.npc.movement.controllers.MotionControllerFly;
import com.hypixel.hytale.server.npc.role.Role;
import javax.annotation.Nonnull;
import org.joml.Vector3d;

/**
 * Native fly movement with a pitch-aware speed scale for formation steering.
 */
public final class MotionControllerTameworkFormationFly extends MotionControllerFly {
    private boolean followArrivalRequested;

    public MotionControllerTameworkFormationFly(@Nonnull BuilderSupport builderSupport,
                                                @Nonnull BuilderMotionControllerTameworkFormationFly builder) {
        super(builderSupport, builder);
    }

    double getSteeringSpeedScale() {
        return computeMaxSpeedFromPitch(getPitch()) * effectHorizontalSpeedMultiplier;
    }

    /** A one-move request, renewed by the follow motion while it is settled. */
    void requestFollowArrival(boolean settled) {
        followArrivalRequested = settled;
    }

    @Override
    protected double computeMove(@Nonnull Ref<EntityStore> ref, @Nonnull Role role,
                                 @Nonnull Steering steering, double dt, @Nonnull Vector3d translation,
                                 @Nonnull ComponentAccessor<EntityStore> accessor) {
        boolean arrived = followArrivalRequested;
        followArrivalRequested = false;
        boolean holding = arrived && steering.getTranslation().lengthSquared() == 0;
        if (holding) {
            // Water/ground can change while the bird is still. Refresh native collision
            // state before deciding whether this tick may hold its airborne position.
            moveProbe.probePosition(ref, collisionBoundingBox, position, collisionResult, accessor);
        }
        // Native Fly enforces its constructor's final MinAirSpeed even for zero
        // steering. Hold only a live, freely steering airborne follower; external
        // forces, landing, water and every other motion retain native physics.
        if (holding && !onGround() && !inWater() && canSteer(ref, accessor)) {
            saveMotionKind();
            setMotionKind(MotionKind.FLYING);
            lastVelocity.zero();
            lastSpeed = 0;
            translation.zero();
            return dt;
        }
        return super.computeMove(ref, role, steering, dt, translation, accessor);
    }
}
