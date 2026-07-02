package com.alechilles.alecstamework.npc.movement;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideComponent;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.movement.Steering;
import com.hypixel.hytale.server.npc.movement.controllers.MotionControllerFly;
import com.hypixel.hytale.server.npc.role.Role;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Fly-controller wrapper that exposes active mounted glide speed and movement state.
 */
public final class MotionControllerTameworkMountedGlide extends MotionControllerFly {
    private static final String GLIDE_ANIMATION = "Fly";
    private static final String FLAP_ANIMATION = "FlyFast";
    private static final String AIRBRAKE_ANIMATION = "FlyIdle";
    private boolean ridden;
    private double activeGlideSpeed;
    private String lastAnimation = "";
    private long lastDebugMs;
    private boolean lastCanSteer;
    private String lastCanSteerReason = "";
    private final Vector3d lastSteering = new Vector3d();
    private final Vector3d lastComputedMove = new Vector3d();

    public MotionControllerTameworkMountedGlide(@Nonnull BuilderSupport builderSupport,
                                                @Nonnull BuilderMotionControllerTameworkMountedGlide builder) {
        super(builderSupport, builder);
    }

    @Nonnull
    @Override
    public String getType() {
        return BuilderMotionControllerTameworkMountedGlide.BUILDER_ID;
    }

    @Override
    public boolean canSteer(@Nonnull Ref<EntityStore> ref, @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        TameworkMountedGlideComponent glide = glideMount(ref, componentAccessor);
        if (glide == null) {
            lastCanSteer = super.canSteer(ref, componentAccessor);
            lastCanSteerReason = lastCanSteer ? "" : String.valueOf(super.canSteerFailReason(ref, componentAccessor));
            return lastCanSteer;
        }
        lastCanSteer = isAlive(ref, componentAccessor)
                && role != null
                && role.couldBreatheCached()
                && !isForcePushed()
                && effectHorizontalSpeedMultiplier != 0.0;
        lastCanSteerReason = lastCanSteer ? "" : mountedGlideCanSteerFailReason(ref, componentAccessor);
        return lastCanSteer;
    }

    @Nullable
    @Override
    public String canSteerFailReason(@Nonnull Ref<EntityStore> ref,
                                     @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        TameworkMountedGlideComponent glide = glideMount(ref, componentAccessor);
        if (glide == null) {
            return super.canSteerFailReason(ref, componentAccessor);
        }
        return mountedGlideCanSteerFailReason(ref, componentAccessor);
    }

    @Override
    protected double computeMove(@Nonnull Ref<EntityStore> ref,
                                 @Nonnull Role role,
                                 @Nonnull Steering steering,
                                 double dt,
                                 @Nonnull Vector3d translation,
                                 @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        TameworkMountedGlideComponent glide = glideMount(ref, componentAccessor);
        ridden = glide != null && glide.isFlightActive();
        activeGlideSpeed = MountedGlideControllerSupport.resolveMountedClientSpeed(glide, maxHorizontalSpeed);
        lastSteering.set(steering.getTranslation());
        double remaining = super.computeMove(ref, role, steering, dt, translation, componentAccessor);
        lastComputedMove.set(translation);
        return remaining;
    }

    @Override
    protected double executeMove(@Nonnull Ref<EntityStore> ref,
                                 @Nonnull Role role,
                                 double dt,
                                 @Nonnull Vector3d translation,
                                 @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        Vector3d requestedMove = new Vector3d(translation);
        double remaining = super.executeMove(ref, role, dt, translation, componentAccessor);
        maybeLogDebug(requestedMove, remaining);
        return remaining;
    }

    @Override
    public void updateMovementState(@Nonnull Ref<EntityStore> ref,
                                    @Nonnull MovementStates movementStates,
                                    @Nonnull Steering steering,
                                    @Nonnull Vector3d velocity,
                                    @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        super.updateMovementState(ref, movementStates, steering, velocity, componentAccessor);
        TameworkMountedGlideComponent glide = glideMount(ref, componentAccessor);
        if (glide == null || !glide.isFlightActive()) {
            return;
        }
        movementStates.onGround = false;
        movementStates.flying = true;
        movementStates.horizontalIdle = false;
        movementStates.sprinting = glide.isSprinting();
        playMovementAnimation(ref, glide, componentAccessor);
    }

    @Override
    public double getMaximumSpeed() {
        return MountedGlideControllerSupport.resolveMountedSpeedLimit(ridden, activeGlideSpeed, super.getMaximumSpeed());
    }

    @Override
    protected double computeMaxSpeedFromPitch(double pitch) {
        return MountedGlideControllerSupport.resolveMountedSpeedLimit(
                ridden,
                activeGlideSpeed,
                super.computeMaxSpeedFromPitch(pitch)
        );
    }

    private TameworkMountedGlideComponent glideMount(@Nonnull Ref<EntityStore> ref,
                                                     @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        ComponentType<EntityStore, TameworkMountedGlideComponent> type = TameworkMountedGlideComponent.getComponentType();
        return type == null ? null : componentAccessor.getComponent(ref, type);
    }

    @Nonnull
    private String mountedGlideCanSteerFailReason(@Nonnull Ref<EntityStore> ref,
                                                  @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        if (!isAlive(ref, componentAccessor)) {
            return "DEAD";
        }
        if (role == null) {
            return "NO_ROLE";
        }
        if (!role.couldBreatheCached()) {
            return "CANNOT_BREATHE";
        }
        if (isForcePushed()) {
            return "EXT_FORCE";
        }
        if (effectHorizontalSpeedMultiplier == 0.0) {
            return "ZERO_SPEED_EFFECT";
        }
        return "";
    }

    private void maybeLogDebug(@Nonnull Vector3d requestedMove, double remaining) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || !instance.isDebugRideEnabled() || instance.getLogger() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastDebugMs < 1000) {
            return;
        }
        lastDebugMs = now;
        instance.getLogger().at(Level.INFO).log(
                "TameworkGlide debug: controller pos=%s/%s/%s requestedMove=%s/%s/%s computedMove=%s/%s/%s " +
                        "steering=%s/%s/%s canSteer=%s reason=%s onGround=%s inWater=%s motionKind=%s ridden=%s " +
                        "activeSpeed=%s maxSpeed=%s remaining=%s",
                position.x,
                position.y,
                position.z,
                requestedMove.x,
                requestedMove.y,
                requestedMove.z,
                lastComputedMove.x,
                lastComputedMove.y,
                lastComputedMove.z,
                lastSteering.x,
                lastSteering.y,
                lastSteering.z,
                lastCanSteer,
                lastCanSteerReason.isBlank() ? "<none>" : lastCanSteerReason,
                onGround(),
                inWater(),
                getMotionKind(),
                ridden,
                activeGlideSpeed,
                getMaximumSpeed(),
                remaining
        );
    }

    private void playMovementAnimation(@Nonnull Ref<EntityStore> ref,
                                       @Nonnull TameworkMountedGlideComponent glide,
                                       @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        String animation = glide.getBoostRemainingSeconds() > 0.0
                ? FLAP_ANIMATION
                : glide.isCrouching() ? AIRBRAKE_ANIMATION : GLIDE_ANIMATION;
        if (animation.equals(lastAnimation)) {
            return;
        }
        NPCEntity npc = componentAccessor.getComponent(ref, NPCEntity.getComponentType());
        if (npc != null) {
            npc.playAnimation(ref, AnimationSlot.Movement, animation, componentAccessor);
        }
        lastAnimation = animation;
    }
}
