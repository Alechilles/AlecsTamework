package com.alechilles.alecstamework.npc.movement;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.movement.MotionKind;
import com.hypixel.hytale.server.npc.movement.Steering;
import com.hypixel.hytale.server.npc.movement.controllers.MotionControllerFly;
import com.hypixel.hytale.server.npc.role.Role;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/**
 * Tamework flight controller that supports autonomous NPC steering and optional rider input tuning.
 */
public final class MotionControllerTameworkFly extends MotionControllerFly {
    private static final double INPUT_DEAD_ZONE = 0.01;
    private static final String FLY_IDLE_ANIMATION = "FlyIdle";
    private static final String FLY_ANIMATION = "Fly";
    private static final String FLY_FAST_ANIMATION = "FlyFast";
    private static final String NO_FORCED_MOVEMENT_ANIMATION = "";
    private final double mountedMaxHorizontalSpeed;
    private final double mountedMaxClimbSpeed;
    private final double mountedMaxSinkSpeed;
    private final double mountedAcceleration;
    private final double mountedDeceleration;
    private final double mountedSprintMultiplier;
    private long lastDebugMs;
    private double lastInputX;
    private double lastInputY;
    private double lastInputZ;
    private boolean lastRiderSprinting;
    private boolean lastRidden;
    private double lastHorizontalSpeedLimit;
    private double lastClimbSpeedLimit;
    private double lastSinkSpeedLimit;
    private double lastAccelerationRate;
    private double lastDecelerationRate;
    private double lastTargetVelocityX;
    private double lastTargetVelocityY;
    private double lastTargetVelocityZ;
    private float lastTargetYaw;
    private float lastTargetPitch;
    private float lastVisualPitch;
    private final RiddenBackwardBrake.State riddenBackwardBrakeState = new RiddenBackwardBrake.State();
    private boolean lastRiddenBackwardBraking;
    private String lastFlightMovementAnimation = NO_FORCED_MOVEMENT_ANIMATION;

    public MotionControllerTameworkFly(@Nonnull BuilderSupport builderSupport,
                                       @Nonnull BuilderMotionControllerTameworkFly builder) {
        super(builderSupport, builder);
        this.mountedMaxHorizontalSpeed = builder.getMountedMaxHorizontalSpeed(builderSupport, maxHorizontalSpeed);
        this.mountedMaxClimbSpeed = builder.getMountedMaxClimbSpeed(builderSupport, maxClimbSpeed);
        this.mountedMaxSinkSpeed = builder.getMountedMaxSinkSpeed(builderSupport, maxSinkSpeed);
        this.mountedAcceleration = builder.getMountedAcceleration(builderSupport, acceleration);
        this.mountedDeceleration = builder.getMountedDeceleration(builderSupport, deceleration);
        this.mountedSprintMultiplier = builder.getMountedSprintMultiplier(builderSupport);
    }

    @Nonnull
    @Override
    public String getType() {
        return BuilderMotionControllerTameworkFly.BUILDER_ID;
    }

    @Override
    public boolean canAct(@Nonnull Ref<EntityStore> ref, @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        return isAlive(ref, componentAccessor)
                && role.couldBreatheCached()
                && forceVelocity.equals(Vector3d.ZERO)
                && appliedVelocities.isEmpty()
                && effectHorizontalSpeedMultiplier != 0.0;
    }

    private boolean canApplyTameworkFlySteering(@Nonnull Ref<EntityStore> ref,
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
        if (!canApplyTameworkFlySteering(ref, componentAccessor)) {
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
        targetPitch = TameworkFlyVisualState.limitPitch(targetPitch);

        translation.assign(steering.getTranslation());
        TameworkRideMountComponent ride = rideMount(ref, componentAccessor);
        lastRiderSprinting = ride != null && ride.isRiderSprinting();
        lastRidden = ride != null;
        if (lastRidden && onGround() && translation.y < 0.0) {
            translation.y = 0.0;
        }
        if (lastRidden) {
            targetYaw = approachAngle(getYaw(), targetYaw, maxTurnSpeed * (float) dt);
            resolveRiddenTranslation(ride, targetYaw, targetPitch, translation);
        }
        lastTargetYaw = targetYaw;
        lastTargetPitch = targetPitch;
        lastInputX = translation.x;
        lastInputY = translation.y;
        lastInputZ = translation.z;
        double inputLength = steering.hasTranslation() ? translation.length() : 0.0;
        boolean brakingFromBackwardInput = hasBackwardInput(ride);
        if (inputLength <= INPUT_DEAD_ZONE && !brakingFromBackwardInput) {
            resetRiddenBackwardBrake();
            captureActiveLimits();
            setMotionKind(onGround() ? MotionKind.STANDING : MotionKind.FLYING);
            lastVelocity.assign(0.0);
            lastSpeed = 0.0;
            translation.assign(0.0);
            lastTargetVelocityX = 0.0;
            lastTargetVelocityY = 0.0;
            lastTargetVelocityZ = 0.0;
            lastVisualPitch = TameworkFlyVisualState.approachVisualAngle(lastVisualPitch, 0.0f, dt);
            lastTargetPitch = lastVisualPitch;
            lastRoll = approach(lastRoll, 0.0f, rollStep(dt));
            steering.setYaw(targetYaw);
            steering.setPitch(lastVisualPitch);
            steering.setRoll(lastRoll);
            return dt;
        }
        if (inputLength > 1.0) {
            translation.scale(1.0 / inputLength);
        }

        captureActiveLimits();
        double horizontalSpeedMultiplier = lastRiderSprinting ? mountedSprintMultiplier : 1.0;
        Vector3d targetVelocity = new Vector3d(
                translation.x * lastHorizontalSpeedLimit * horizontalSpeedMultiplier,
                translation.y * (translation.y >= 0.0 ? lastClimbSpeedLimit : lastSinkSpeedLimit),
                translation.z * lastHorizontalSpeedLimit * horizontalSpeedMultiplier
        );
        if (lastRidden) {
            RiddenBackwardBrake.apply(targetVelocity, lastVelocity, riddenBackwardBrakeState, brakingFromBackwardInput, dt);
            lastRiddenBackwardBraking = riddenBackwardBrakeState.isBraking();
        } else {
            resetRiddenBackwardBrake();
            targetVelocity.scale(effectHorizontalSpeedMultiplier);
        }
        lastTargetVelocityX = targetVelocity.x;
        lastTargetVelocityY = targetVelocity.y;
        lastTargetVelocityZ = targetVelocity.z;
        double targetSpeed = targetVelocity.length();
        double maxDelta = (targetSpeed > lastSpeed ? lastAccelerationRate : lastDecelerationRate) * dt;
        approachVelocity(targetVelocity, maxDelta);
        lastSpeed = lastVelocity.length();

        boolean verticalDominantFlight = TameworkFlyVisualState.isVerticalDominantFlight(lastVelocity);
        if (verticalDominantFlight) {
            setMotionKind(MotionKind.FLYING);
        } else if (lastVelocity.y > INPUT_DEAD_ZONE) {
            setMotionKind(MotionKind.ASCENDING);
        } else if (lastVelocity.y < -INPUT_DEAD_ZONE) {
            setMotionKind(MotionKind.DESCENDING);
        }

        double strafeRoll = computeStrafeRoll(steering.getTranslation(), targetYaw, targetSpeed);
        lastRoll = approach(lastRoll, (float) strafeRoll, rollStep(dt));
        float visualPitch = TameworkFlyVisualState.resolveVisualPitch(targetPitch, lastVelocity);
        lastVisualPitch = TameworkFlyVisualState.approachVisualAngle(lastVisualPitch, visualPitch, dt);
        lastTargetPitch = lastVisualPitch;
        steering.setYaw(targetYaw);
        steering.setPitch(lastVisualPitch);
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
    public void updateMovementState(@Nonnull Ref<EntityStore> ref,
                                    @Nonnull MovementStates movementStates,
                                    @Nonnull Steering steering,
                                    @Nonnull Vector3d velocity,
                                    @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        super.updateMovementState(ref, movementStates, steering, velocity, componentAccessor);
        if (onGround() || inWater()) {
            clearForcedMovementAnimation(ref, componentAccessor);
            return;
        }

        TameworkRideMountComponent ride = rideMount(ref, componentAccessor);
        boolean horizontalIdle = TameworkFlyAnimationState.resolveHorizontalIdle(
                ride,
                horizontalSpeed(),
                TameworkFlyVisualState.isVerticalDominantFlight(lastVelocity)
        );
        boolean fast = TameworkFlyAnimationState.resolveFast(ride, horizontalIdle);
        updateFlyingStates(movementStates, horizontalIdle, fast);
        clearGroundMovementStates(movementStates);
        movementStates.horizontalIdle = horizontalIdle;
        playFlightMovementAnimation(ref, horizontalIdle, fast, componentAccessor);
    }

    @Override
    public double getMaximumSpeed() {
        if (!lastRidden) {
            return super.getMaximumSpeed();
        }
        return getMountedClientSpeed(lastRiderSprinting);
    }

    public double getMountedClientSpeed(boolean riderSprinting) {
        double sprintMultiplier = riderSprinting ? mountedSprintMultiplier : 1.0;
        return Math.max(
                mountedHorizontalSpeedLimit() * sprintMultiplier,
                Math.max(mountedMaxClimbSpeed, mountedMaxSinkSpeed)
        );
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
        return speed < TameworkFlyAnimationState.HORIZONTAL_IDLE_SPEED;
    }

    public void clearForcedMovementAnimation(@Nonnull Ref<EntityStore> ref,
                                             @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        if (NO_FORCED_MOVEMENT_ANIMATION.equals(lastFlightMovementAnimation)) {
            return;
        }
        NPCEntity npc = componentAccessor.getComponent(ref, NPCEntity.getComponentType());
        if (npc != null) {
            npc.playAnimation(ref, AnimationSlot.Movement, null, componentAccessor);
        }
        lastFlightMovementAnimation = NO_FORCED_MOVEMENT_ANIMATION;
    }

    private void playFlightMovementAnimation(@Nonnull Ref<EntityStore> ref,
                                             boolean horizontalIdle,
                                             boolean fast,
                                             @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        NPCEntity npc = componentAccessor.getComponent(ref, NPCEntity.getComponentType());
        if (npc == null) {
            return;
        }
        String animation = horizontalIdle ? FLY_IDLE_ANIMATION : fast ? FLY_FAST_ANIMATION : FLY_ANIMATION;
        if (animation.equals(lastFlightMovementAnimation)) {
            return;
        }
        npc.playAnimation(ref, AnimationSlot.Movement, animation, componentAccessor);
        lastFlightMovementAnimation = animation;
    }

    private TameworkRideMountComponent rideMount(@Nonnull Ref<EntityStore> ref,
                                                 @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        ComponentType<EntityStore, TameworkRideMountComponent> type = TameworkRideMountComponent.getComponentType();
        return type == null ? null : componentAccessor.getComponent(ref, type);
    }

    private double horizontalSpeed() {
        return Math.sqrt(lastVelocity.x * lastVelocity.x + lastVelocity.z * lastVelocity.z);
    }

    private void clearGroundMovementStates(@Nonnull MovementStates movementStates) {
        movementStates.walking = false;
        movementStates.running = false;
        movementStates.sprinting = false;
        movementStates.onGround = false;
        movementStates.flying = true;
    }

    private void captureActiveLimits() {
        if (lastRidden) {
            lastHorizontalSpeedLimit = mountedHorizontalSpeedLimit();
            lastClimbSpeedLimit = mountedMaxClimbSpeed;
            lastSinkSpeedLimit = mountedMaxSinkSpeed;
            lastAccelerationRate = mountedAcceleration;
            lastDecelerationRate = mountedDeceleration;
            return;
        }
        lastHorizontalSpeedLimit = maxHorizontalSpeed;
        lastClimbSpeedLimit = maxClimbSpeed;
        lastSinkSpeedLimit = maxSinkSpeed;
        lastAccelerationRate = acceleration;
        lastDecelerationRate = deceleration;
    }

    private double mountedHorizontalSpeedLimit() {
        return mountedMaxHorizontalSpeed;
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

    private void resetRiddenBackwardBrake() {
        riddenBackwardBrakeState.reset();
        lastRiddenBackwardBraking = false;
    }

    private boolean hasBackwardInput(TameworkRideMountComponent ride) {
        return ride != null && ride.isRiderBackwardBrakeInput();
    }

    private float rollStep(double dt) {
        double speed = maxRollAngle > 0.0f ? Math.min(maxRollSpeed, maxRollAngle) : maxRollSpeed;
        return (float) Math.max(0.0, speed * dt);
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

    static void resolveRiddenTranslation(@Nonnull TameworkRideMountComponent ride,
                                         float yaw,
                                         float pitch,
                                         @Nonnull Vector3d translation) {
        if (!ride.hasWishMovement()) {
            return;
        }
        double strafe = ride.getWishX();
        double forwardAmount = ride.getWishZ();
        double vertical = translation.y;
        float movementPitch = forwardAmount > INPUT_DEAD_ZONE ? pitch : 0.0f;
        double cosPitch = Math.cos(movementPitch);
        double forwardX = -Math.sin(yaw) * cosPitch;
        double forwardZ = -Math.cos(yaw) * cosPitch;
        double rightX = -Math.sin(yaw - Math.PI / 2.0);
        double rightZ = -Math.cos(yaw - Math.PI / 2.0);
        translation.assign(
                forwardX * forwardAmount + rightX * strafe,
                vertical,
                forwardZ * forwardAmount + rightZ * strafe
        );
        double length = translation.length();
        if (length > 1.0) {
            translation.scale(1.0 / length);
        }
    }

    private static float approach(float value, float target, float maxDelta) {
        if (Math.abs(target - value) <= maxDelta) {
            return target;
        }
        return value + Math.copySign(maxDelta, target - value);
    }

    static float approachAngle(float value, float target, float maxDelta) {
        float delta = normalizeAngle(target - value);
        if (Math.abs(delta) <= maxDelta) {
            return normalizeAngle(target);
        }
        return normalizeAngle(value + Math.copySign(maxDelta, delta));
    }

    private static float normalizeAngle(float angle) {
        while (angle <= -Math.PI) {
            angle += Math.PI * 2.0f;
        }
        while (angle > Math.PI) {
            angle -= Math.PI * 2.0f;
        }
        return angle;
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
                "TameworkFly debug: flyController pos=%s/%s/%s requestedMove=%s/%s/%s lastVelocity=%s/%s/%s " +
                        "targetVelocity=%s/%s/%s input=%s/%s/%s yaw=%s pitch=%s onGround=%s inWater=%s " +
                        "motionKind=%s remaining=%s ridden=%s sprinting=%s horizontalLimit=%s climbLimit=%s " +
                        "sinkLimit=%s acceleration=%s deceleration=%s backwardAirbrake=%s effectHorizontalSpeedMultiplier=%s " +
                        "movementAnimation=%s",
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
                remaining,
                lastRidden,
                lastRiderSprinting,
                lastHorizontalSpeedLimit,
                lastClimbSpeedLimit,
                lastSinkSpeedLimit,
                lastAccelerationRate,
                lastDecelerationRate,
                lastRiddenBackwardBraking,
                effectHorizontalSpeedMultiplier,
                lastFlightMovementAnimation
        );
    }
}
