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

/** Target-relative flight steering that spends most of its time orbiting and periodically approaches. */
public final class BodyMotionTameworkFlyingOrbit extends BodyMotionBase {
    private static final double MIN_DIRECTION_LENGTH = 1.0E-6;
    private static final double MIN_APPROACH_SPEED_FACTOR = 0.2;
    private static final double RADIAL_CORRECTION_WEIGHT = 0.85;

    private final double orbitRadius;
    private final double orbitRadiusTolerance;
    private final double orbitDurationMin;
    private final double orbitDurationMax;
    private final double approachDurationMin;
    private final double approachDurationMax;
    private final double approachStopDistance;
    private final double approachSlowDownDistance;
    private final double relativeSpeed;
    private final Vector3d targetPosition = new Vector3d();
    private final Vector3d translation = new Vector3d();

    private Phase phase = Phase.ORBIT;
    private double phaseRemaining;
    private int orbitDirection = 1;

    BodyMotionTameworkFlyingOrbit(@Nonnull BuilderBodyMotionTameworkFlyingOrbit builder,
                                  @Nonnull BuilderSupport support) {
        super(builder);
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
        relativeSpeed = builder.getRelativeSpeed(support);
        beginOrbit();
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
        if (active == null || !active.matchesType(MotionControllerFly.class)
                || sensorInfo == null || !sensorInfo.getPositionProvider().providePosition(targetPosition)) {
            return false;
        }

        TransformComponent transform = componentAccessor.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) {
            return false;
        }

        tickPhase(dt);
        Vector3d selfPosition = transform.getPosition();
        if (phase == Phase.ORBIT) {
            resolveOrbitTranslation(
                    selfPosition.x(), selfPosition.z(), targetPosition.x(), targetPosition.z(),
                    orbitRadius, orbitRadiusTolerance, orbitDirection, relativeSpeed, translation);
        } else {
            resolveApproachTranslation(
                    selfPosition.x(), selfPosition.z(), targetPosition.x(), targetPosition.z(),
                    approachStopDistance, approachSlowDownDistance, relativeSpeed, translation);
        }

        desiredSteering.setTranslation(translation);
        if (translation.lengthSquared() > MIN_DIRECTION_LENGTH * MIN_DIRECTION_LENGTH) {
            desiredSteering.setYaw(PhysicsMath.headingFromDirection(translation.x, translation.z));
        }
        desiredSteering.setRelativeTurnSpeed(1.0);
        return false;
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

    private enum Phase {
        ORBIT,
        APPROACH
    }
}
