package com.alechilles.alecstamework.npc.movement;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.movement.MotionKind;
import com.hypixel.hytale.server.npc.movement.Steering;
import com.hypixel.hytale.server.npc.movement.controllers.MotionControllerFly;
import com.hypixel.hytale.server.npc.movement.controllers.builders.BuilderMotionControllerFly;
import com.hypixel.hytale.server.npc.role.Role;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/**
 * Rider-oriented flight controller variant that permits low-speed hovering and ground takeoff.
 */
public final class MotionControllerTameworkRideFly extends MotionControllerFly {
    private static final double INPUT_DEAD_ZONE = 0.01;
    private long lastDebugMs;
    private double lastInputX;
    private double lastInputY;
    private double lastInputZ;
    private double lastTargetVelocityX;
    private double lastTargetVelocityY;
    private double lastTargetVelocityZ;
    private float lastTargetYaw;
    private float lastTargetPitch;

    public MotionControllerTameworkRideFly(@Nonnull BuilderSupport builderSupport,
                                           @Nonnull BuilderMotionControllerFly builder) {
        super(builderSupport, builder);
    }

    @Nonnull
    @Override
    public String getType() {
        return BuilderMotionControllerTameworkRideFly.BUILDER_ID;
    }

    @Override
    public boolean canAct(@Nonnull Ref<EntityStore> ref, @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        return isAlive(ref, componentAccessor)
                && role.couldBreatheCached()
                && forceVelocity.equals(Vector3d.ZERO)
                && appliedVelocities.isEmpty()
                && effectHorizontalSpeedMultiplier != 0.0;
    }

    private boolean canApplyRiderSteering(@Nonnull Ref<EntityStore> ref,
                                          @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        return isAlive(ref, componentAccessor)
                && forceVelocity.equals(Vector3d.ZERO)
                && appliedVelocities.isEmpty()
                && effectHorizontalSpeedMultiplier != 0.0;
    }

    @Override
    protected double computeMove(@Nonnull Ref<EntityStore> ref,
                                 @Nonnull Role role,
                                 @Nonnull Steering steering,
                                 double dt,
                                 @Nonnull Vector3d translation,
                                 @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        saveMotionKind();
        setMotionKind(inWater() ? MotionKind.MOVING : MotionKind.FLYING);
        moveProbe.probePosition(ref, collisionBoundingBox, position, collisionResult, componentAccessor);
        currentRelativeSpeed = steering.getSpeed();

        if (!isAlive(ref, componentAccessor)) {
            forceVelocity.assign(0.0);
            appliedVelocities.clear();
        }
        if (!forceVelocity.equals(Vector3d.ZERO) || !appliedVelocities.isEmpty()) {
            return super.computeMove(ref, role, steering, dt, translation, componentAccessor);
        }
        if (!canApplyRiderSteering(ref, componentAccessor)) {
            steering.setYaw(getYaw());
            steering.setPitch(getPitch());
            steering.setRoll(getRoll());
            if (onGround()) {
                setMotionKind(MotionKind.STANDING);
                lastVelocity.assign(0.0);
                lastSpeed = 0.0;
                translation.assign(0.0);
                return dt;
            }
            return super.computeMove(ref, role, steering, dt, translation, componentAccessor);
        }

        float targetYaw = steering.hasYawOrDirection() ? steering.getYawOrDirection() : getYaw();
        float targetPitch = steering.hasPitchOrDirection() ? steering.getPitchOrDirection() : getPitch();
        targetPitch = clamp(targetPitch, -maxSinkAngle, maxClimbAngle);
        lastTargetYaw = targetYaw;
        lastTargetPitch = targetPitch;

        translation.assign(steering.getTranslation());
        lastInputX = translation.x;
        lastInputY = translation.y;
        lastInputZ = translation.z;
        double inputLength = steering.hasTranslation() ? translation.length() : 0.0;
        if (inputLength <= INPUT_DEAD_ZONE) {
            setMotionKind(onGround() ? MotionKind.STANDING : MotionKind.FLYING);
            lastVelocity.assign(0.0);
            lastSpeed = 0.0;
            translation.assign(0.0);
            lastTargetVelocityX = 0.0;
            lastTargetVelocityY = 0.0;
            lastTargetVelocityZ = 0.0;
            lastRoll = approach(lastRoll, 0.0f, (float) (maxRollSpeed * dt));
            steering.setYaw(targetYaw);
            steering.setPitch(targetPitch);
            steering.setRoll(lastRoll);
            return dt;
        }
        if (inputLength > 1.0) {
            translation.scale(1.0 / inputLength);
        }

        Vector3d targetVelocity = new Vector3d(
                translation.x * maxHorizontalSpeed,
                translation.y * (translation.y >= 0.0 ? maxClimbSpeed : maxSinkSpeed),
                translation.z * maxHorizontalSpeed
        );
        targetVelocity.scale(effectHorizontalSpeedMultiplier);
        lastTargetVelocityX = targetVelocity.x;
        lastTargetVelocityY = targetVelocity.y;
        lastTargetVelocityZ = targetVelocity.z;
        double targetSpeed = targetVelocity.length();
        double maxDelta = (targetSpeed > lastSpeed ? acceleration : deceleration) * dt;
        approachVelocity(targetVelocity, maxDelta);
        lastSpeed = lastVelocity.length();

        if (lastVelocity.y > INPUT_DEAD_ZONE) {
            setMotionKind(MotionKind.ASCENDING);
        } else if (lastVelocity.y < -INPUT_DEAD_ZONE) {
            setMotionKind(MotionKind.DESCENDING);
        }

        double strafeRoll = computeStrafeRoll(steering.getTranslation(), targetYaw, targetSpeed);
        lastRoll = approach(lastRoll, (float) strafeRoll, (float) (maxRollSpeed * dt));
        steering.setYaw(targetYaw);
        steering.setPitch(targetPitch);
        steering.setRoll(lastRoll);

        translation.assign(lastVelocity);
        translation.scale(dt);
        return dt;
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
    public boolean canRestAtPlace() {
        return true;
    }

    @Override
    public double getDesiredAltitudeWeight() {
        return 0.0;
    }

    @Override
    public boolean isHorizontalIdle(double speed) {
        return speed < 0.05;
    }

    private void approachVelocity(@Nonnull Vector3d targetVelocity, double maxDelta) {
        Vector3d delta = new Vector3d(targetVelocity).subtract(lastVelocity);
        double deltaLength = delta.length();
        if (deltaLength <= maxDelta || deltaLength <= 1.0E-6) {
            lastVelocity.assign(targetVelocity);
            return;
        }
        lastVelocity.addScaled(delta, maxDelta / deltaLength);
    }

    private double computeStrafeRoll(@Nonnull Vector3d input, float yaw, double targetSpeed) {
        if (targetSpeed <= INPUT_DEAD_ZONE) {
            return 0.0;
        }
        double rightX = -Math.sin(yaw - Math.PI / 2.0);
        double rightZ = -Math.cos(yaw - Math.PI / 2.0);
        double strafe = clamp(input.x * rightX + input.z * rightZ, -1.0, 1.0);
        return -maxRollAngle * strafe;
    }

    private static float approach(float value, float target, float maxDelta) {
        if (Math.abs(target - value) <= maxDelta) {
            return target;
        }
        return value + Math.copySign(maxDelta, target - value);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
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
                "TameworkRide debug: flyController pos=%s/%s/%s requestedMove=%s/%s/%s lastVelocity=%s/%s/%s " +
                        "targetVelocity=%s/%s/%s input=%s/%s/%s yaw=%s pitch=%s onGround=%s inWater=%s motionKind=%s remaining=%s",
                position.x,
                position.y,
                position.z,
                requestedMove.x,
                requestedMove.y,
                requestedMove.z,
                lastVelocity.x,
                lastVelocity.y,
                lastVelocity.z,
                lastTargetVelocityX,
                lastTargetVelocityY,
                lastTargetVelocityZ,
                lastInputX,
                lastInputY,
                lastInputZ,
                lastTargetYaw,
                lastTargetPitch,
                onGround(),
                inWater(),
                getMotionKind(),
                remaining
        );
    }
}
