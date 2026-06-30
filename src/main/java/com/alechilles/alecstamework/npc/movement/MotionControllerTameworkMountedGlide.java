package com.alechilles.alecstamework.npc.movement;

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
import javax.annotation.Nonnull;
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
    protected double computeMove(@Nonnull Ref<EntityStore> ref,
                                 @Nonnull com.hypixel.hytale.server.npc.role.Role role,
                                 @Nonnull Steering steering,
                                 double dt,
                                 @Nonnull Vector3d translation,
                                 @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        TameworkMountedGlideComponent glide = glideMount(ref, componentAccessor);
        ridden = glide != null;
        activeGlideSpeed = MountedGlideControllerSupport.resolveMountedClientSpeed(glide, maxHorizontalSpeed);
        return super.computeMove(ref, role, steering, dt, translation, componentAccessor);
    }

    @Override
    public void updateMovementState(@Nonnull Ref<EntityStore> ref,
                                    @Nonnull MovementStates movementStates,
                                    @Nonnull Steering steering,
                                    @Nonnull Vector3d velocity,
                                    @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        super.updateMovementState(ref, movementStates, steering, velocity, componentAccessor);
        TameworkMountedGlideComponent glide = glideMount(ref, componentAccessor);
        if (glide == null) {
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
        return ridden ? activeGlideSpeed : super.getMaximumSpeed();
    }

    private TameworkMountedGlideComponent glideMount(@Nonnull Ref<EntityStore> ref,
                                                     @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        ComponentType<EntityStore, TameworkMountedGlideComponent> type = TameworkMountedGlideComponent.getComponentType();
        return type == null ? null : componentAccessor.getComponent(ref, type);
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
