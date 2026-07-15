package com.alechilles.alecstamework.npc.movement;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.physics.util.PhysicsMath;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.corecomponents.BodyMotionBase;
import com.hypixel.hytale.server.npc.movement.Steering;
import com.hypixel.hytale.server.npc.movement.controllers.MotionController;
import com.hypixel.hytale.server.npc.movement.controllers.MotionControllerFly;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Target-relative orbit, approach, facing, and loose waypoint flight steering. */
public final class BodyMotionTameworkFlyingOrbit extends BodyMotionBase {
    private static final double MIN_DIRECTION_LENGTH = 1.0E-6;
    private static final double MIN_APPROACH_SPEED_FACTOR = 0.2;
    private static final double RADIAL_CORRECTION_WEIGHT = 0.85;

    private final BuilderBodyMotionTameworkFlyingOrbit.Mode mode;
    private final double orbitRadius;
    private final double orbitRadiusTolerance;
    private final double orbitDurationMin;
    private final double orbitDurationMax;
    private final double approachDurationMin;
    private final double approachDurationMax;
    private final double approachStopDistance;
    private final double approachSlowDownDistance;
    private final double[] wanderRadiusRange;
    private final double[] wanderRetargetTimeRange;
    private final double wanderStopDistance;
    private final double relativeSpeed;
    private final double[] desiredAltitudeRange;
    private final double climbRelativeSpeed;
    private final double sinkRelativeSpeed;
    private final Vector3d targetPosition = new Vector3d();
    private final Vector3d wanderDestination = new Vector3d();
    private final Vector3d translation = new Vector3d();
    private final Vector3d facingDirection = new Vector3d();

    private Phase phase = Phase.ORBIT;
    private double phaseRemaining;
    private int orbitDirection = 1;
    private boolean hasWanderDestination;
    private double wanderRetargetRemaining;

    BodyMotionTameworkFlyingOrbit(@Nonnull BuilderBodyMotionTameworkFlyingOrbit builder,
                                  @Nonnull BuilderSupport support) {
        super(builder);
        mode = builder.getMode(support);
        orbitRadius = builder.getOrbitRadius(support);
        orbitRadiusTolerance = builder.getOrbitRadiusTolerance(support);
        orbitDurationMin = Math.min(builder.getOrbitDurationMin(support), builder.getOrbitDurationMax(support));
        orbitDurationMax = Math.max(builder.getOrbitDurationMin(support), builder.getOrbitDurationMax(support));
        approachDurationMin = Math.min(
                builder.getApproachDurationMin(support), builder.getApproachDurationMax(support));
        approachDurationMax = Math.max(
                builder.getApproachDurationMin(support), builder.getApproachDurationMax(support));
        approachStopDistance = builder.getApproachStopDistance(support);
        approachSlowDownDistance = Math.max(builder.getApproachSlowDownDistance(support), approachStopDistance);
        wanderRadiusRange = builder.getWanderRadiusRange(support);
        wanderRetargetTimeRange = builder.getWanderRetargetTimeRange(support);
        wanderStopDistance = builder.getWanderStopDistance(support);
        relativeSpeed = builder.getRelativeSpeed(support);
        desiredAltitudeRange = builder.getDesiredAltitudeRange(support);
        climbRelativeSpeed = builder.getClimbRelativeSpeed(support);
        sinkRelativeSpeed = builder.getSinkRelativeSpeed(support);
        beginOrbit();
    }

    @Override
    public void activate(@Nonnull Ref<EntityStore> ref,
                         @Nonnull Role role,
                         @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        hasWanderDestination = false;
        wanderRetargetRemaining = 0.0;
        if (mode == BuilderBodyMotionTameworkFlyingOrbit.Mode.CYCLE) {
            beginOrbit();
        }
    }

    @Override
    public boolean computeSteering(@Nonnull Ref<EntityStore> ref,
                                   @Nonnull Role role,
                                   @Nullable InfoProvider sensorInfo,
                                   double dt,
                                   @Nonnull Steering desiredSteering,
                                   @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        desiredSteering.clear();
        MotionController active = role.getActiveMotionController();
        if (!(active instanceof MotionControllerFly fly)
                || sensorInfo == null || !sensorInfo.getPositionProvider().providePosition(targetPosition)) {
            return true;
        }

        TransformComponent transform = componentAccessor.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) {
            return true;
        }

        if (mode == BuilderBodyMotionTameworkFlyingOrbit.Mode.CYCLE) {
            tickPhase(dt);
        }
        Vector3d selfPosition = transform.getPosition();
        boolean approaching = usesApproachSteering(mode, phase);
        boolean facingTarget = facesTarget(mode, phase);
        boolean wandering = mode == BuilderBodyMotionTameworkFlyingOrbit.Mode.WANDER_TARGET;
        if (wandering) {
            updateWanderDestination(selfPosition, targetPosition, dt);
            resolveWaypointTranslation(
                    selfPosition.x(), selfPosition.y(), selfPosition.z(),
                    wanderDestination.x(), wanderDestination.y(), wanderDestination.z(),
                    wanderStopDistance, relativeSpeed, translation);
        } else if (mode == BuilderBodyMotionTameworkFlyingOrbit.Mode.FACE_TARGET) {
            translation.zero();
        } else if (!approaching) {
            resolveOrbitTranslation(
                    selfPosition.x(), selfPosition.z(), targetPosition.x(), targetPosition.z(),
                    orbitRadius, orbitRadiusTolerance, orbitDirection, relativeSpeed, translation);
        } else {
            resolveApproachTranslation(
                    selfPosition.x(), selfPosition.z(), targetPosition.x(), targetPosition.z(),
                    approachStopDistance, approachSlowDownDistance, relativeSpeed, translation);
        }

        double altitudeCorrection = 0.0;
        if (!wandering) {
            altitudeCorrection = resolveTargetRelativeAltitudeCorrection(
                    selfPosition.y(), targetPosition.y(), desiredAltitudeRange, climbRelativeSpeed, sinkRelativeSpeed);
            translation.y = altitudeCorrection;
        }
        desiredSteering.setTranslation(translation);
        Vector3d yawDirection = facingTarget
                ? resolveTargetDirection(selfPosition.x(), selfPosition.z(), targetPosition.x(), targetPosition.z(), facingDirection)
                : translation;
        if (yawDirection.lengthSquared() > MIN_DIRECTION_LENGTH * MIN_DIRECTION_LENGTH) {
            desiredSteering.setYaw(PhysicsMath.headingFromDirection(yawDirection.x, yawDirection.z));
        }
        if (altitudeCorrection != 0.0
                && translation.x * translation.x + translation.z * translation.z <= MIN_DIRECTION_LENGTH * MIN_DIRECTION_LENGTH) {
            desiredSteering.setPitch(altitudeCorrection > 0.0 ? fly.getMaxClimbAngle() : -fly.getMaxSinkAngle());
        }
        desiredSteering.setRelativeTurnSpeed(1.0);
        return true;
    }

    private void updateWanderDestination(@Nonnull Vector3d selfPosition,
                                         @Nonnull Vector3d currentTargetPosition,
                                         double dt) {
        wanderRetargetRemaining -= Math.max(0.0, dt);
        boolean reachedDestination = selfPosition.distanceSquared(wanderDestination)
                <= wanderStopDistance * wanderStopDistance;
        boolean destinationWithinBand = isWithinTargetBand(
                wanderDestination.x(), wanderDestination.y(), wanderDestination.z(),
                currentTargetPosition.x(), currentTargetPosition.y(), currentTargetPosition.z(),
                wanderRadiusRange, desiredAltitudeRange);
        if (!hasWanderDestination || wanderRetargetRemaining <= 0.0
                || reachedDestination || !destinationWithinBand) {
            chooseWanderDestination(currentTargetPosition);
        }
    }

    private void chooseWanderDestination(@Nonnull Vector3d currentTargetPosition) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double minimumRadius = wanderRadiusRange[0];
        double maximumRadius = wanderRadiusRange[1];
        double radiusSquared = randomDuration(
                minimumRadius * minimumRadius, maximumRadius * maximumRadius);
        double radius = Math.sqrt(radiusSquared);
        double angle = random.nextDouble(Math.PI * 2.0);
        double altitude = randomDuration(desiredAltitudeRange[0], desiredAltitudeRange[1]);
        wanderDestination.set(
                currentTargetPosition.x() + Math.cos(angle) * radius,
                currentTargetPosition.y() + altitude,
                currentTargetPosition.z() + Math.sin(angle) * radius);
        wanderRetargetRemaining = randomDuration(wanderRetargetTimeRange[0], wanderRetargetTimeRange[1]);
        hasWanderDestination = true;
    }

    private void tickPhase(double dt) {
        phaseRemaining -= Math.max(0.0, dt);
        if (phaseRemaining > 0.0) {
            return;
        }
        if (phase == Phase.ORBIT) {
            phase = Phase.APPROACH;
            phaseRemaining = randomDuration(approachDurationMin, approachDurationMax);
        } else {
            beginOrbit();
        }
    }

    private void beginOrbit() {
        phase = Phase.ORBIT;
        phaseRemaining = randomDuration(orbitDurationMin, orbitDurationMax);
        orbitDirection = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
    }

    private static double randomDuration(double min, double max) {
        return max <= min ? min : ThreadLocalRandom.current().nextDouble(min, max);
    }

    static boolean usesApproachSteering(@Nonnull BuilderBodyMotionTameworkFlyingOrbit.Mode mode,
                                        @Nonnull Phase phase) {
        return mode == BuilderBodyMotionTameworkFlyingOrbit.Mode.APPROACH
                || mode == BuilderBodyMotionTameworkFlyingOrbit.Mode.CYCLE && phase == Phase.APPROACH;
    }

    static boolean facesTarget(@Nonnull BuilderBodyMotionTameworkFlyingOrbit.Mode mode,
                               @Nonnull Phase phase) {
        return mode == BuilderBodyMotionTameworkFlyingOrbit.Mode.FACE_TARGET
                || usesApproachSteering(mode, phase);
    }

    static double resolveAltitudeCorrection(double currentY,
                                            double minimumY,
                                            double maximumY,
                                            double climbSpeed,
                                            double sinkSpeed) {
        if (currentY < minimumY) {
            return climbSpeed;
        }
        if (currentY > maximumY) {
            return -sinkSpeed;
        }
        return 0.0;
    }

    static double resolveTargetRelativeAltitudeCorrection(double selfY,
                                                          double targetY,
                                                          double[] altitudeRange,
                                                          double climbSpeed,
                                                          double sinkSpeed) {
        return resolveAltitudeCorrection(
                selfY, targetY + altitudeRange[0], targetY + altitudeRange[1], climbSpeed, sinkSpeed);
    }

    static Vector3d resolveTargetDirection(double selfX,
                                           double selfZ,
                                           double targetX,
                                           double targetZ,
                                           @Nonnull Vector3d output) {
        return output.set(targetX - selfX, 0.0, targetZ - selfZ);
    }

    static boolean isWithinTargetBand(double pointX,
                                      double pointY,
                                      double pointZ,
                                      double targetX,
                                      double targetY,
                                      double targetZ,
                                      double[] radiusRange,
                                      double[] altitudeRange) {
        double offsetX = pointX - targetX;
        double offsetZ = pointZ - targetZ;
        double horizontalDistanceSquared = offsetX * offsetX + offsetZ * offsetZ;
        double minimumRadiusSquared = radiusRange[0] * radiusRange[0];
        double maximumRadiusSquared = radiusRange[1] * radiusRange[1];
        double altitude = pointY - targetY;
        return horizontalDistanceSquared >= minimumRadiusSquared
                && horizontalDistanceSquared <= maximumRadiusSquared
                && altitude >= altitudeRange[0]
                && altitude <= altitudeRange[1];
    }

    static Vector3d resolveWaypointTranslation(double selfX,
                                               double selfY,
                                               double selfZ,
                                               double waypointX,
                                               double waypointY,
                                               double waypointZ,
                                               double stopDistance,
                                               double speed,
                                               @Nonnull Vector3d output) {
        double moveX = waypointX - selfX;
        double moveY = waypointY - selfY;
        double moveZ = waypointZ - selfZ;
        double distance = Math.sqrt(moveX * moveX + moveY * moveY + moveZ * moveZ);
        if (distance <= stopDistance || distance <= MIN_DIRECTION_LENGTH) {
            return output.zero();
        }
        return output.set(moveX / distance * speed, moveY / distance * speed, moveZ / distance * speed);
    }

    static Vector3d resolveOrbitTranslation(double selfX,
                                            double selfZ,
                                            double targetX,
                                            double targetZ,
                                            double radius,
                                            double tolerance,
                                            int direction,
                                            double speed,
                                            @Nonnull Vector3d output) {
        double outwardX = selfX - targetX;
        double outwardZ = selfZ - targetZ;
        double distance = Math.sqrt(outwardX * outwardX + outwardZ * outwardZ);
        if (distance <= MIN_DIRECTION_LENGTH) {
            return output.set(speed * (direction < 0 ? -1.0 : 1.0), 0.0, 0.0);
        }

        outwardX /= distance;
        outwardZ /= distance;
        double side = direction < 0 ? -1.0 : 1.0;
        double tangentX = -outwardZ * side;
        double tangentZ = outwardX * side;
        double radiusError = clamp((distance - radius) / Math.max(tolerance, MIN_DIRECTION_LENGTH), -1.0, 1.0);
        double moveX = tangentX - outwardX * radiusError * RADIAL_CORRECTION_WEIGHT;
        double moveZ = tangentZ - outwardZ * radiusError * RADIAL_CORRECTION_WEIGHT;
        double moveLength = Math.sqrt(moveX * moveX + moveZ * moveZ);
        return output.set(moveX / moveLength * speed, 0.0, moveZ / moveLength * speed);
    }

    static Vector3d resolveApproachTranslation(double selfX,
                                               double selfZ,
                                               double targetX,
                                               double targetZ,
                                               double stopDistance,
                                               double slowDownDistance,
                                               double speed,
                                               @Nonnull Vector3d output) {
        double moveX = targetX - selfX;
        double moveZ = targetZ - selfZ;
        double distance = Math.sqrt(moveX * moveX + moveZ * moveZ);
        if (distance <= stopDistance || distance <= MIN_DIRECTION_LENGTH) {
            return output.zero();
        }
        double slowRange = Math.max(slowDownDistance - stopDistance, MIN_DIRECTION_LENGTH);
        double speedFactor = clamp((distance - stopDistance) / slowRange, MIN_APPROACH_SPEED_FACTOR, 1.0);
        return output.set(moveX / distance * speed * speedFactor, 0.0, moveZ / distance * speed * speedFactor);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    enum Phase {
        ORBIT,
        APPROACH
    }
}
