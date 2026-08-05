package com.alechilles.alecstamework.npc.movement;

import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.physics.util.PhysicsMath;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.corecomponents.BodyMotionBase;
import com.hypixel.hytale.server.npc.movement.Steering;
import com.hypixel.hytale.server.npc.movement.controllers.MotionController;
import com.hypixel.hytale.server.npc.movement.controllers.MotionControllerFly;
import com.hypixel.hytale.server.npc.movement.controllers.ProbeMoveData;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Target-relative orbit, approach, facing, loose waypoint, and pass-through flight steering. */
public final class BodyMotionTameworkFlyingOrbit extends BodyMotionBase {
    private static final double MIN_DIRECTION_LENGTH = 1.0E-6;
    private static final double MIN_APPROACH_SPEED_FACTOR = 0.2;
    private static final double RADIAL_CORRECTION_WEIGHT = 0.85;
    private static final double WANDER_ENVELOPE_RADIUS_MULTIPLIER = 1.75;
    private static final double WANDER_ENVELOPE_ALTITUDE_MARGIN = 8.0;
    private static final int WANDER_CANDIDATE_LIMIT = 3;

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
    private final double passThroughDistance;
    private final double passThroughStopDistance;
    private final double relativeSpeed;
    private final double[] desiredAltitudeRange;
    private final double climbRelativeSpeed;
    private final double sinkRelativeSpeed;
    private final boolean avoidObstacles;
    private final FlyingObstacleAvoidance obstacleAvoidance = new FlyingObstacleAvoidance();
    private final ProbeMoveData obstacleProbeData = new ProbeMoveData();
    private final Vector3d targetPosition = new Vector3d();
    private final Vector3d wanderDestination = new Vector3d();
    private final Vector3d waypointRoute = new Vector3d();
    private final Vector3d obstacleReference = new Vector3d();
    private final Vector3d obstacleProbeOrigin = new Vector3d();
    private final Vector3d passThroughDestination = new Vector3d();
    private final Vector3d translation = new Vector3d();
    private final Vector3d facingDirection = new Vector3d();
    private final Vector3d[] wanderCandidates = {
            new Vector3d(), new Vector3d(), new Vector3d()
    };
    private final double[] wanderClearances = new double[WANDER_CANDIDATE_LIMIT];
    private final FlyingObstacleAvoidance.Probe obstacleProbe = this::probeObstacle;

    @Nullable
    private Ref<EntityStore> obstacleProbeRef;
    @Nullable
    private MotionControllerFly obstacleProbeController;
    @Nullable
    private ComponentAccessor<EntityStore> obstacleProbeAccessor;

    private Phase phase = Phase.ORBIT;
    private double phaseRemaining;
    private int orbitDirection = 1;
    private boolean hasWanderDestination;
    private double wanderRetargetRemaining;
    private boolean hasPassThroughDestination;

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
        passThroughDistance = builder.getPassThroughDistance(support);
        passThroughStopDistance = builder.getPassThroughStopDistance(support);
        relativeSpeed = builder.getRelativeSpeed(support);
        desiredAltitudeRange = builder.getDesiredAltitudeRange(support);
        climbRelativeSpeed = builder.getClimbRelativeSpeed(support);
        sinkRelativeSpeed = builder.getSinkRelativeSpeed(support);
        avoidObstacles = builder.isAvoidObstacles(support);
        beginOrbit();
    }

    @Override
    public void activate(@Nonnull Ref<EntityStore> ref,
                         @Nonnull Role role,
                         @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        hasWanderDestination = false;
        wanderRetargetRemaining = 0.0;
        hasPassThroughDestination = false;
        obstacleAvoidance.reset();
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

        Vector3d selfPosition = transform.getPosition();
        TameworkRideMountComponent tameworkRide = avoidObstacles
                ? this.tameworkRide(ref, componentAccessor) : null;
        NPCMountComponent nativeMount = avoidObstacles
                ? this.resolveNativeMount(ref, componentAccessor) : null;
        boolean autonomousAvoidance = avoidObstacles && !isRiderControlled(tameworkRide, nativeMount);
        if (autonomousAvoidance) {
            obstacleAvoidance.beginUpdate(dt);
            bindObstacleProbe(ref, selfPosition, fly, componentAccessor);
        } else {
            obstacleAvoidance.reset();
        }

        try {
            if (mode == BuilderBodyMotionTameworkFlyingOrbit.Mode.CYCLE) {
                tickPhase(dt);
            }
            boolean approaching = usesApproachSteering(mode, phase);
            boolean facingTarget = facesTarget(mode, phase);
            boolean wandering = mode == BuilderBodyMotionTameworkFlyingOrbit.Mode.WANDER_TARGET;
            boolean passingThrough = mode == BuilderBodyMotionTameworkFlyingOrbit.Mode.PASS_THROUGH_TARGET;
            if (wandering) {
                updateWanderDestination(selfPosition, targetPosition, dt, autonomousAvoidance, fly);
                resolveWaypointTranslation(
                        selfPosition.x(), selfPosition.y(), selfPosition.z(),
                        wanderDestination.x(), wanderDestination.y(), wanderDestination.z(),
                        wanderStopDistance, relativeSpeed, translation);
            } else if (passingThrough) {
                if (!hasPassThroughDestination) {
                    double altitudeOffset = randomDuration(desiredAltitudeRange[0], desiredAltitudeRange[1]);
                    resolvePassThroughDestination(
                            selfPosition.x(), selfPosition.z(),
                            targetPosition.x(), targetPosition.y(), targetPosition.z(),
                            passThroughDistance, altitudeOffset, passThroughDestination);
                    hasPassThroughDestination = true;
                }
                resolveWaypointTranslation(
                        selfPosition.x(), selfPosition.y(), selfPosition.z(),
                        passThroughDestination.x(), passThroughDestination.y(), passThroughDestination.z(),
                        passThroughStopDistance, relativeSpeed, translation);
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
            if (!wandering && !passingThrough) {
                altitudeCorrection = resolveTargetRelativeAltitudeCorrection(
                        selfPosition.y(), targetPosition.y(), desiredAltitudeRange,
                        climbRelativeSpeed, sinkRelativeSpeed);
                translation.y = altitudeCorrection;
            }

            if (shouldApplyObstacleAvoidance(
                    avoidObstacles, tameworkRide, nativeMount, translation)) {
                resolveTargetDirection(
                        selfPosition.x(), selfPosition.z(),
                        targetPosition.x(), targetPosition.z(), obstacleReference);
                obstacleAvoidance.adjust(
                        translation,
                        obstacleReference,
                        fly.getMaximumSpeed(),
                        fly.getCurrentTurnRadius(),
                        obstacleProbe,
                        translation);
            }

            desiredSteering.setTranslation(translation);
            Vector3d yawDirection = facingTarget
                    ? resolveTargetDirection(
                            selfPosition.x(), selfPosition.z(),
                            targetPosition.x(), targetPosition.z(), facingDirection)
                    : translation;
            if (yawDirection.lengthSquared() > MIN_DIRECTION_LENGTH * MIN_DIRECTION_LENGTH) {
                desiredSteering.setYaw(PhysicsMath.headingFromDirection(yawDirection.x, yawDirection.z));
            }
            if (altitudeCorrection != 0.0
                    && translation.x * translation.x + translation.z * translation.z
                    <= MIN_DIRECTION_LENGTH * MIN_DIRECTION_LENGTH) {
                desiredSteering.setPitch(
                        altitudeCorrection > 0.0 ? fly.getMaxClimbAngle() : -fly.getMaxSinkAngle());
            }
            desiredSteering.setRelativeTurnSpeed(1.0);
            return true;
        } finally {
            clearObstacleProbeContext();
        }
    }

    private void updateWanderDestination(@Nonnull Vector3d selfPosition,
                                         @Nonnull Vector3d currentTargetPosition,
                                         double dt,
                                         boolean preflight,
                                         @Nonnull MotionControllerFly fly) {
        wanderRetargetRemaining -= Math.max(0.0, dt);
        boolean reachedDestination = selfPosition.distanceSquared(wanderDestination)
                <= wanderStopDistance * wanderStopDistance;
        boolean outsideSafetyEnvelope = isOutsideTargetEnvelope(
                selfPosition.x(), selfPosition.y(), selfPosition.z(),
                currentTargetPosition.x(), currentTargetPosition.y(), currentTargetPosition.z(),
                wanderRadiusRange, desiredAltitudeRange);
        if (!hasWanderDestination || wanderRetargetRemaining <= 0.0
                || reachedDestination || outsideSafetyEnvelope) {
            chooseWanderDestination(selfPosition, currentTargetPosition, preflight, fly);
        }
    }

    private void chooseWanderDestination(@Nonnull Vector3d selfPosition,
                                         @Nonnull Vector3d currentTargetPosition,
                                         boolean preflight,
                                         @Nonnull MotionControllerFly fly) {
        if (!preflight) {
            generateWanderCandidate(currentTargetPosition, wanderDestination);
        } else {
            int candidateCount = 0;
            for (int i = 0; i < WANDER_CANDIDATE_LIMIT; i++) {
                Vector3d candidate = wanderCandidates[i];
                generateWanderCandidate(currentTargetPosition, candidate);
                waypointRoute.set(candidate).sub(selfPosition);
                double clearance = obstacleAvoidance.probeWaypoint(
                        waypointRoute,
                        fly.getMaximumSpeed(),
                        fly.getCurrentTurnRadius(),
                        obstacleProbe);
                wanderClearances[i] = clearance;
                candidateCount++;
                if (clearance >= 1.0) {
                    break;
                }
            }
            int selected = selectWanderCandidate(wanderClearances, candidateCount);
            if (selected >= 0) {
                wanderDestination.set(wanderCandidates[selected]);
            } else {
                generateWanderCandidate(currentTargetPosition, wanderDestination);
            }
        }
        wanderRetargetRemaining = randomDuration(wanderRetargetTimeRange[0], wanderRetargetTimeRange[1]);
        hasWanderDestination = true;
    }

    private void generateWanderCandidate(@Nonnull Vector3d currentTargetPosition,
                                         @Nonnull Vector3d output) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double minimumRadius = wanderRadiusRange[0];
        double maximumRadius = wanderRadiusRange[1];
        double radiusSquared = randomDuration(
                minimumRadius * minimumRadius, maximumRadius * maximumRadius);
        double radius = Math.sqrt(radiusSquared);
        double angle = random.nextDouble(Math.PI * 2.0);
        double altitude = randomDuration(desiredAltitudeRange[0], desiredAltitudeRange[1]);
        output.set(
                currentTargetPosition.x() + Math.cos(angle) * radius,
                currentTargetPosition.y() + altitude,
                currentTargetPosition.z() + Math.sin(angle) * radius);
    }

    @Nullable
    private TameworkRideMountComponent tameworkRide(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        ComponentType<EntityStore, TameworkRideMountComponent> type =
                TameworkRideMountComponent.getComponentType();
        return type == null ? null : componentAccessor.getComponent(ref, type);
    }

    @Nullable
    private NPCMountComponent resolveNativeMount(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        ComponentType<EntityStore, NPCMountComponent> type = NPCMountComponent.getComponentType();
        return type == null ? null : componentAccessor.getComponent(ref, type);
    }

    private void bindObstacleProbe(@Nonnull Ref<EntityStore> ref,
                                   @Nonnull Vector3d selfPosition,
                                   @Nonnull MotionControllerFly fly,
                                   @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        obstacleProbeRef = ref;
        obstacleProbeOrigin.set(selfPosition);
        obstacleProbeController = fly;
        obstacleProbeAccessor = componentAccessor;
    }

    private double probeObstacle(@Nonnull Vector3d direction) {
        Ref<EntityStore> ref = obstacleProbeRef;
        MotionControllerFly fly = obstacleProbeController;
        ComponentAccessor<EntityStore> componentAccessor = obstacleProbeAccessor;
        if (ref == null || fly == null || componentAccessor == null) {
            return 0.0;
        }
        return fly.probeMove(ref, obstacleProbeOrigin, direction, obstacleProbeData, componentAccessor);
    }

    private void clearObstacleProbeContext() {
        obstacleProbeRef = null;
        obstacleProbeController = null;
        obstacleProbeAccessor = null;
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

    static boolean isRiderControlled(@Nullable TameworkRideMountComponent tameworkRide,
                                     @Nullable NPCMountComponent nativeMount) {
        return tameworkRide != null || nativeMount != null;
    }

    static boolean shouldApplyObstacleAvoidance(boolean enabled,
                                                @Nullable TameworkRideMountComponent tameworkRide,
                                                @Nullable NPCMountComponent nativeMount,
                                                @Nonnull Vector3d desiredTranslation) {
        return enabled
                && !isRiderControlled(tameworkRide, nativeMount)
                && Double.isFinite(desiredTranslation.x)
                && Double.isFinite(desiredTranslation.y)
                && Double.isFinite(desiredTranslation.z)
                && desiredTranslation.lengthSquared() > MIN_DIRECTION_LENGTH * MIN_DIRECTION_LENGTH;
    }

    static int selectWanderCandidate(@Nonnull double[] clearanceFractions, int candidateCount) {
        int limit = Math.min(Math.max(0, candidateCount), clearanceFractions.length);
        int bestIndex = -1;
        double bestClearance = -1.0;
        for (int i = 0; i < limit; i++) {
            double clearance = clearanceFractions[i];
            if (clearance >= 1.0) {
                return i;
            }
            if (clearance > bestClearance) {
                bestClearance = clearance;
                bestIndex = i;
            }
        }
        return bestIndex;
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

    static boolean isOutsideTargetEnvelope(double pointX,
                                           double pointY,
                                           double pointZ,
                                           double targetX,
                                           double targetY,
                                           double targetZ,
                                           double[] radiusRange,
                                           double[] altitudeRange) {
        double offsetX = pointX - targetX;
        double offsetZ = pointZ - targetZ;
        double maximumRadius = radiusRange[1] * WANDER_ENVELOPE_RADIUS_MULTIPLIER;
        double altitude = pointY - targetY;
        return offsetX * offsetX + offsetZ * offsetZ > maximumRadius * maximumRadius
                || altitude < altitudeRange[0] - WANDER_ENVELOPE_ALTITUDE_MARGIN
                || altitude > altitudeRange[1] + WANDER_ENVELOPE_ALTITUDE_MARGIN;
    }

    static Vector3d resolvePassThroughDestination(double selfX,
                                                  double selfZ,
                                                  double targetX,
                                                  double targetY,
                                                  double targetZ,
                                                  double passThroughDistance,
                                                  double altitudeOffset,
                                                  @Nonnull Vector3d output) {
        double directionX = targetX - selfX;
        double directionZ = targetZ - selfZ;
        double directionLength = Math.sqrt(directionX * directionX + directionZ * directionZ);
        if (directionLength <= MIN_DIRECTION_LENGTH) {
            directionX = 0.0;
            directionZ = 1.0;
        } else {
            directionX /= directionLength;
            directionZ /= directionLength;
        }
        return output.set(
                targetX + directionX * passThroughDistance,
                targetY + altitudeOffset,
                targetZ + directionZ * passThroughDistance);
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
