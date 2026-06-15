package com.alechilles.alecstamework.npc.movement;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.corecomponents.BodyMotionBase;
import com.hypixel.hytale.server.npc.movement.Steering;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.IPositionProvider;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Steers directly toward a preflighted needs resource target without invoking vanilla seek pathing.
 */
public final class BodyMotionTameworkNeedsResourceApproach extends BodyMotionBase {
    static final double DEFAULT_STOP_DISTANCE = 2.0;
    static final double DEFAULT_RELATIVE_SPEED = 1.0;
    private static final double MIN_HORIZONTAL_DISTANCE = 0.0001;

    private final double stopDistance;
    private final double relativeSpeed;

    public BodyMotionTameworkNeedsResourceApproach(
            @Nonnull BuilderBodyMotionTameworkNeedsResourceApproach builder,
            @Nonnull BuilderSupport support
    ) {
        super(builder);
        this.stopDistance = builder.getStopDistance(support);
        this.relativeSpeed = builder.getRelativeSpeed(support);
    }

    @Override
    public boolean computeSteering(@Nonnull Ref<EntityStore> ref,
                                   @Nonnull Role role,
                                   @Nullable InfoProvider sensorInfo,
                                   double dt,
                                   @Nonnull Steering desiredSteering,
                                   @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        desiredSteering.clear();
        Vector3d current = resolveCurrentPosition(ref, componentAccessor);
        Vector3d target = resolveTargetPosition(sensorInfo);
        if (current == null || target == null) {
            return true;
        }
        Vector3d translation = resolveApproachTranslation(current, target, stopDistance);
        if (translation.lengthSquared() <= MIN_HORIZONTAL_DISTANCE * MIN_HORIZONTAL_DISTANCE) {
            return true;
        }
        desiredSteering.setTranslation(translation);
        desiredSteering.setTranslationRelativeSpeed(relativeSpeed);
        desiredSteering.setMaxDistance(Math.max(0.0, horizontalDistance(current, target) - stopDistance));
        desiredSteering.setYaw(resolveYawForTranslation(translation));
        desiredSteering.setRelativeTurnSpeed(1.0);
        return true;
    }

    static boolean hasUsableApproachTarget(@Nonnull Vector3d current,
                                           @Nonnull Vector3d target,
                                           double stopDistance) {
        double distance = horizontalDistance(current, target);
        return Double.isFinite(distance)
                && distance > Math.max(sanitizeStopDistance(stopDistance), MIN_HORIZONTAL_DISTANCE);
    }

    @Nonnull
    static Vector3d resolveApproachTranslation(@Nonnull Vector3d current,
                                               @Nonnull Vector3d target,
                                               double stopDistance) {
        if (!hasUsableApproachTarget(current, target, stopDistance)) {
            return new Vector3d();
        }
        double dx = target.x - current.x;
        double dz = target.z - current.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        return new Vector3d(dx / distance, 0.0, dz / distance);
    }

    static double sanitizeStopDistance(double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            return DEFAULT_STOP_DISTANCE;
        }
        return value;
    }

    static double sanitizeRelativeSpeed(double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            return DEFAULT_RELATIVE_SPEED;
        }
        return value;
    }

    private static float resolveYawForTranslation(@Nonnull Vector3d translation) {
        return (float) Math.atan2(-translation.x, -translation.z);
    }

    private static double horizontalDistance(@Nonnull Vector3d current, @Nonnull Vector3d target) {
        double dx = target.x - current.x;
        double dz = target.z - current.z;
        if (!Double.isFinite(dx) || !Double.isFinite(dz)) {
            return Double.NaN;
        }
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Nullable
    private static Vector3d resolveCurrentPosition(@Nonnull Ref<EntityStore> ref,
                                                   @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        TransformComponent transform = componentAccessor.getComponent(ref, TransformComponent.getComponentType());
        Vector3d position = transform == null ? null : transform.getPosition();
        if (position == null || !isFinite(position)) {
            return null;
        }
        return position;
    }

    @Nullable
    private static Vector3d resolveTargetPosition(@Nullable InfoProvider sensorInfo) {
        if (sensorInfo == null || !sensorInfo.hasPosition()) {
            return null;
        }
        IPositionProvider provider = sensorInfo.getPositionProvider();
        if (provider == null || !provider.hasPosition()) {
            return null;
        }
        double x = provider.getX();
        double y = provider.getY();
        double z = provider.getZ();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return null;
        }
        return new Vector3d(x, y, z);
    }

    private static boolean isFinite(@Nonnull Vector3d vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }
}
