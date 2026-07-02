package com.alechilles.alecstamework.npc.movement;

import com.alechilles.alecstamework.config.assets.TwMountedGlideConfig;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideComponent;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.corecomponents.BodyMotionBase;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderBodyMotionBase;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.movement.Steering;
import com.hypixel.hytale.server.npc.movement.controllers.MotionController;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Suppresses normal AI body motion while an NPC is controlled by mounted glide input.
 */
public final class BodyMotionTameworkMountedGlide extends BodyMotionBase {
    private static final double INPUT_DEAD_ZONE = 0.025;

    public BodyMotionTameworkMountedGlide(@Nonnull BuilderBodyMotionBase builderMotionBase) {
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
        ComponentType<EntityStore, TameworkMountedGlideComponent> glideType =
                TameworkMountedGlideComponent.getComponentType();
        if (glideType == null) {
            return true;
        }
        TameworkMountedGlideComponent glide = componentAccessor.getComponent(ref, glideType);
        if (glide == null) {
            return true;
        }
        if (!shouldApplyGlideSteering(glide)) {
            return true;
        }
        applyGlideSteering(ref, role, glide, dt, desiredSteering, componentAccessor);
        return true;
    }

    public static void applyGlideSteering(@Nonnull Ref<EntityStore> ref,
                                          @Nonnull Role role,
                                          @Nonnull TameworkMountedGlideComponent glide,
                                          double dt,
                                          @Nonnull Steering desiredSteering,
                                          @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        desiredSteering.clear();
        if (!shouldApplyGlideSteering(glide)) {
            return;
        }
        TwMountedGlideConfig config = resolveConfig(role, glide);
        MotionController active = ensureGlideController(ref, role, glide, componentAccessor);
        maybeTakeOff(ref, glide, config, active, componentAccessor);
        MountedGlidePhysicsState state = glide.toPhysicsState();
        if (state.getGlideSpeed() <= 0.0) {
            state.setGlideSpeed(config.getGlide().getBaseSpeed());
        }
        MountedGlidePhysics.Input input = new MountedGlidePhysics.Input(
                resolvePitchRadians(glide),
                glide.hasMovementIntent() ? glide.getForwardIntent() : 1.0,
                glide.hasMovementIntent() ? glide.getStrafeIntent() : 0.0,
                glide.isJumpHeld(),
                glide.isSprinting(),
                glide.isCrouching()
        );
        MountedGlidePhysics.Output output = MountedGlidePhysics.update(state, config, input, dt);
        glide.applyPhysicsState(state);

        float fallbackYaw = resolveFallbackYaw(ref, componentAccessor);
        float yaw = resolveYawRadians(glide, fallbackYaw);
        Vector3d translation = resolveTranslation(yaw, input.forwardIntent(), input.strafeIntent(), output);
        clampGroundedSink(translation, active != null && active.onGround());
        desiredSteering.setTranslation(translation);
        desiredSteering.setYaw(yaw);
        desiredSteering.setPitch((float) -resolvePitchRadians(glide));
        desiredSteering.setRelativeTurnSpeed(1.0);
    }

    private static MotionController ensureGlideController(@Nonnull Ref<EntityStore> ref,
                                                          @Nonnull Role role,
                                                          @Nonnull TameworkMountedGlideComponent glide,
                                                          @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        MotionController active = role.getActiveMotionController();
        String controller = glide.getGlideController();
        if (isActiveController(active, controller)) {
            return active;
        }
        NPCEntity npc = componentAccessor.getComponent(ref, NPCEntity.getComponentType());
        if (npc == null || controller.isBlank()) {
            return active;
        }
        role.setActiveMotionController(ref, npc, controller, componentAccessor);
        return role.getActiveMotionController();
    }

    private static void maybeTakeOff(@Nonnull Ref<EntityStore> ref,
                                     @Nonnull TameworkMountedGlideComponent glide,
                                     @Nonnull TwMountedGlideConfig config,
                                     @Nullable MotionController active,
                                     @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        if (!(active instanceof MotionControllerTameworkMountedGlide glideController)
                || !shouldTakeOffFromGround(glide, active.onGround())) {
            return;
        }
        double launchSpeed = Math.max(2.0, Math.min(config.getGlide().getBaseSpeed(), active.getMaximumSpeed()) * 0.25);
        glideController.takeOff(ref, launchSpeed, componentAccessor);
    }

    static boolean shouldTakeOffFromGround(@Nonnull TameworkMountedGlideComponent glide, boolean grounded) {
        return grounded && glide.isJumpHeld();
    }

    static boolean shouldApplyGlideSteering(@Nonnull TameworkMountedGlideComponent glide) {
        return glide.isFlightActive();
    }

    static void clampGroundedSink(@Nonnull Vector3d translation, boolean grounded) {
        if (grounded && translation.y < 0.0) {
            translation.y = 0.0;
        }
    }

    static boolean isActiveController(@Nullable MotionController active, @Nonnull String controller) {
        return active != null && !controller.isBlank() && controller.equals(active.getType());
    }

    @Nonnull
    private static TwMountedGlideConfig resolveConfig(@Nonnull Role role, @Nonnull TameworkMountedGlideComponent glide) {
        if (!glide.getConfigId().isBlank()) {
            var assetMap = TwMountedGlideConfig.getAssetMap();
            if (assetMap != null && assetMap.getAssetMap() != null) {
                TwMountedGlideConfig byId = assetMap.getAssetMap().get(glide.getConfigId());
                if (byId != null) {
                    return byId;
                }
            }
        }
        TwMountedGlideConfig byRole = TwMountedGlideConfig.resolveForRole(role.getRoleName());
        return byRole == null ? new TwMountedGlideConfig() : byRole;
    }

    private static float resolveFallbackYaw(@Nonnull Ref<EntityStore> ref,
                                            @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        TransformComponent transform = componentAccessor.getComponent(ref, TransformComponent.getComponentType());
        Rotation3f rotation = transform == null ? null : transform.getRotation();
        return rotation == null ? 0.0f : rotation.yaw();
    }

    static double resolvePitchRadians(@Nonnull TameworkMountedGlideComponent glide) {
        return glide.hasLookRotation() ? Math.toRadians(glide.getLookPitchDegrees()) : 0.0;
    }

    static float resolveYawRadians(@Nonnull TameworkMountedGlideComponent glide, float fallbackYaw) {
        return glide.hasLookRotation() ? (float) Math.toRadians(glide.getLookYawDegrees()) : fallbackYaw;
    }

    @Nonnull
    static Vector3d resolveTranslation(double yawRadians,
                                       double forwardIntent,
                                       double strafeIntent,
                                       @Nonnull MountedGlidePhysics.Output output) {
        double forwardAmount = Math.abs(forwardIntent) <= INPUT_DEAD_ZONE ? 1.0 : forwardIntent;
        double forwardX = -Math.sin(yawRadians);
        double forwardZ = -Math.cos(yawRadians);
        double rightX = -Math.sin(yawRadians - Math.PI / 2.0);
        double rightZ = -Math.cos(yawRadians - Math.PI / 2.0);
        double verticalRatio = output.forwardSpeed() <= 0.0001
                ? 0.0
                : output.verticalVelocity() / output.forwardSpeed();
        Vector3d result = new Vector3d(
                forwardX * forwardAmount + rightX * strafeIntent,
                verticalRatio,
                forwardZ * forwardAmount + rightZ * strafeIntent
        );
        double length = result.length();
        if (length > 1.0) {
            result.mul(1.0 / length);
        }
        return result;
    }
}
