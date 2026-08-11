package com.alechilles.alecstamework.npc.movement;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.physics.util.PhysicsMath;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderBodyMotionBase;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.movement.Steering;
import com.hypixel.hytale.server.npc.movement.controllers.MotionController;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Suppresses normal AI body motion while mounted and steers from the latest rider input snapshot.
 */
public final class BodyMotionTameworkRide extends TameworkBodyMotionBase {
    private static final double INPUT_DEAD_ZONE = 0.025;
    private static final double GROUNDED_FLIGHT_ASSIST = 0.35;
    private long lastDebugMs;

    public BodyMotionTameworkRide(@Nonnull BuilderBodyMotionBase builderMotionBase) {
        super(builderMotionBase);
    }

    @Override
    public boolean computeSteering(@Nonnull Ref<EntityStore> ref,
                                   @Nonnull Role role,
                                   @Nullable InfoProvider sensorInfo,
                                   double dt,
                                   @Nonnull Steering desiredSteering,
                                   @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        desiredSteering.clear();
        ComponentType<EntityStore, TameworkRideMountComponent> rideType =
                TameworkRideMountComponent.getComponentType();
        if (rideType == null) {
            return true;
        }
        TameworkRideMountComponent ride = componentAccessor.getComponent(ref, rideType);
        if (ride == null) {
            return true;
        }
        NPCEntity npc = componentAccessor.getComponent(ref, NPCEntity.getComponentType());
        if (npc == null) {
            return true;
        }

        boolean flyingControllerActive = isActiveController(role, ride.getFlightController());
        boolean grounded = isGrounded(role);
        updateGroundedCrouchDismount(ride, grounded);

        if (shouldUseFlight(ride, flyingControllerActive, grounded)) {
            switchController(ref, role, npc, ride.getFlightController(), componentAccessor);
            flyingControllerActive = true;
        } else if (flyingControllerActive && grounded && !ride.isRiderJumping() && ride.getWishY() <= INPUT_DEAD_ZONE) {
            switchController(ref, role, npc, ride.getGroundController(), componentAccessor);
            flyingControllerActive = false;
        }

        float yaw = resolveYaw(ref, ride, componentAccessor);
        Vector3d translation = resolveTranslation(ride, yaw, flyingControllerActive);
        applyGroundedFlightAssist(ride, translation, grounded, flyingControllerActive);
        desiredSteering.setTranslation(translation);
        desiredSteering.setYaw(yaw);
        desiredSteering.setRelativeTurnSpeed(1.0);
        if (flyingControllerActive) {
            desiredSteering.setPitch(resolveControlPitchFromSnapshot(ride));
        }
        maybeLogDebug(role, ride, grounded, flyingControllerActive, yaw, translation, desiredSteering);
        return true;
    }

    static void updateGroundedCrouchDismount(@Nonnull TameworkRideMountComponent ride, boolean grounded) {
        if (!grounded) {
            ride.resetGroundedCrouchTicks();
            return;
        }
        if (!ride.isRiderCrouching()) {
            ride.setGroundedCrouchDismountArmed(true);
            ride.resetGroundedCrouchTicks();
            return;
        }
        if (ride.hasWishMovement()) {
            ride.resetGroundedCrouchTicks();
            return;
        }
        if (ride.isGroundedCrouchDismountArmed()) {
            ride.incrementGroundedCrouchTicks();
            return;
        }
        ride.resetGroundedCrouchTicks();
    }

    private boolean shouldUseFlight(@Nonnull TameworkRideMountComponent ride,
                                    boolean flyingControllerActive,
                                    boolean grounded) {
        if (!grounded) {
            return true;
        }
        if (ride.isRiderFlying()
                || ride.isRiderJumping()
                || Math.abs(ride.getWishY()) > INPUT_DEAD_ZONE) {
            return true;
        }
        if (flyingControllerActive && !ride.hasWishMovement()) {
            return false;
        }
        return hasPitchTakeoffIntent(ride);
    }

    private boolean hasPitchTakeoffIntent(@Nonnull TameworkRideMountComponent ride) {
        return ride.hasWishMovement()
                && ride.getWishZ() > INPUT_DEAD_ZONE
                && resolveControlPitchFromSnapshot(ride) > 0.12f;
    }

    private void switchController(@Nonnull Ref<EntityStore> ref,
                                  @Nonnull Role role,
                                  @Nonnull NPCEntity npc,
                                  @Nonnull String controller,
                                  @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        if (controller.isBlank() || isActiveController(role, controller)) {
            return;
        }
        role.setActiveMotionController(ref, npc, controller, componentAccessor);
        MotionController active = role.getActiveMotionController();
        if (active instanceof MotionControllerTameworkFly fly) {
            fly.takeOff(ref, Math.max(1.5, active.getMaximumSpeed() * 0.2), componentAccessor);
        } else {
            npc.playAnimation(ref, AnimationSlot.Movement, null, componentAccessor);
        }
    }

    private boolean isActiveController(@Nonnull Role role, @Nonnull String controller) {
        MotionController active = role.getActiveMotionController();
        return active != null && active.getType().equals(controller);
    }

    private boolean isGrounded(@Nonnull Role role) {
        MotionController active = role.getActiveMotionController();
        return active != null && active.onGround();
    }

    private float resolveYaw(@Nonnull Ref<EntityStore> ref,
                             @Nonnull TameworkRideMountComponent ride,
                             @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        TransformComponent transform = componentAccessor.getComponent(ref, TransformComponent.getComponentType());
        float fallbackYaw = 0.0f;
        if (transform != null) {
            Rotation3f rotation = transform.getRotation();
            fallbackYaw = rotation.yaw();
        }
        return resolveYawFromSnapshot(ride, fallbackYaw);
    }

    static float resolveYawFromSnapshot(@Nonnull TameworkRideMountComponent ride, float fallbackYaw) {
        if (ride.hasHeadRotation()) {
            return ride.getHeadYaw();
        }
        return ride.hasBodyRotation() ? ride.getBodyYaw() : fallbackYaw;
    }

    private float resolveFlightPitch(@Nonnull TameworkRideMountComponent ride) {
        return resolveFlightPitchFromSnapshot(ride);
    }

    static float resolveFlightPitchFromSnapshot(@Nonnull TameworkRideMountComponent ride) {
        if (ride.hasHeadRotation()) {
            return ride.getHeadPitch();
        }
        return ride.hasBodyRotation() ? ride.getBodyPitch() : 0.0f;
    }

    static float resolveControlPitchFromSnapshot(@Nonnull TameworkRideMountComponent ride) {
        return -resolveFlightPitchFromSnapshot(ride);
    }

    @Nonnull
    static Vector3d resolveTranslationFromSnapshot(@Nonnull TameworkRideMountComponent ride,
                                                   float yaw,
                                                   boolean flyingControllerActive) {
        double strafe = ride.hasWishMovement() ? ride.getWishX() : 0.0;
        double forwardAmount = ride.hasWishMovement() ? ride.getWishZ() : 0.0;
        double vertical = 0.0;
        if (flyingControllerActive) {
            vertical = ride.hasWishMovement() ? ride.getWishY() : 0.0;
            if (ride.isRiderJumping()) {
                vertical = Math.max(vertical, 1.0);
            }
            if (ride.isRiderCrouching()) {
                vertical = Math.min(vertical, -1.0);
            }
        }

        float pitch = 0.0f;
        if (flyingControllerActive && forwardAmount > INPUT_DEAD_ZONE) {
            pitch = resolveControlPitchFromSnapshot(ride);
        }

        Vector3d forward = new Vector3d();
        Vector3d right = new Vector3d();
        PhysicsMath.vectorFromAngles(yaw, pitch, forward);
        PhysicsMath.vectorFromAngles(yaw - (float) (Math.PI / 2.0), 0.0f, right);

        Vector3d result = new Vector3d(
                forward.x * forwardAmount + right.x * strafe,
                forward.y * forwardAmount + vertical,
                forward.z * forwardAmount + right.z * strafe
        );
        double length = result.length();
        if (length > 1.0) {
            result.mul(1.0 / length);
        }
        return result;
    }

    static void applyGroundedFlightAssist(@Nonnull TameworkRideMountComponent ride,
                                          @Nonnull Vector3d translation,
                                          boolean grounded,
                                          boolean flyingControllerActive) {
        if (!grounded || !flyingControllerActive || ride.isRiderCrouching()) {
            return;
        }
        double horizontal = Math.sqrt(translation.x * translation.x + translation.z * translation.z);
        boolean takeoffIntent = ride.isRiderJumping()
                || ride.isRiderFlying()
                || horizontal > INPUT_DEAD_ZONE;
        if (!takeoffIntent || translation.y >= GROUNDED_FLIGHT_ASSIST) {
            return;
        }
        translation.y = GROUNDED_FLIGHT_ASSIST;
        double length = translation.length();
        if (length > 1.0) {
            translation.mul(1.0 / length);
        }
    }

    @Nonnull
    private Vector3d resolveTranslation(@Nonnull TameworkRideMountComponent ride,
                                        float yaw,
                                        boolean flyingControllerActive) {
        return resolveTranslationFromSnapshot(ride, yaw, flyingControllerActive);
    }

    private void maybeLogDebug(@Nonnull Role role,
                               @Nonnull TameworkRideMountComponent ride,
                               boolean grounded,
                               boolean flyingControllerActive,
                               float yaw,
                               @Nonnull Vector3d translation,
                               @Nonnull Steering desiredSteering) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || !instance.isDebugRideEnabled() || instance.getLogger() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastDebugMs < 1000) {
            return;
        }
        lastDebugMs = now;
        MotionController active = role.getActiveMotionController();
        String controller = active == null ? "<none>" : active.getType();
        instance.getLogger().at(Level.INFO).log(
                "TameworkRide debug: bodyMotion role=%s controller=%s grounded=%s flyingActive=%s yaw=%s " +
                        "translation=%s/%s/%s hasYaw=%s hasPitch=%s wish=%s/%s/%s hasWish=%s " +
                        "body=%s/%s hasBody=%s head=%s/%s hasHead=%s jump=%s crouch=%s flying=%s sprinting=%s",
                role.getRoleName(),
                controller,
                grounded,
                flyingControllerActive,
                yaw,
                translation.x,
                translation.y,
                translation.z,
                desiredSteering.hasYaw(),
                desiredSteering.hasPitch(),
                ride.getWishX(),
                ride.getWishY(),
                ride.getWishZ(),
                ride.hasWishMovement(),
                ride.getBodyYaw(),
                ride.getBodyPitch(),
                ride.hasBodyRotation(),
                ride.getHeadYaw(),
                ride.getHeadPitch(),
                ride.hasHeadRotation(),
                ride.isRiderJumping(),
                ride.isRiderCrouching(),
                ride.isRiderFlying(),
                ride.isRiderSprinting()
        );
    }
}
