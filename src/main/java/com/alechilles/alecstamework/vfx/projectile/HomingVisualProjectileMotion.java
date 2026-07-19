package com.alechilles.alecstamework.vfx.projectile;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Pure deterministic movement math for homing visual projectiles. */
public final class HomingVisualProjectileMotion {
    private static final double EPSILON = 1.0e-8D;
    private static final double MAX_DT_SECONDS = 0.25D;

    private HomingVisualProjectileMotion() {
    }

    @Nonnull
    public static Step step(@Nullable Vector3d current,
                            @Nullable Vector3d destination,
                            @Nullable Vector3d lastDirection,
                            double speed,
                            double turnRateDegreesPerSecond,
                            double arrivalRadius,
                            double dtSeconds) {
        if (!finite(current) || !finite(destination)
                || !Double.isFinite(speed) || speed <= 0.0D
                || !Double.isFinite(arrivalRadius) || arrivalRadius <= 0.0D
                || !Double.isFinite(dtSeconds) || dtSeconds <= 0.0D) {
            return Step.invalid(current);
        }

        Vector3d delta = new Vector3d(destination).sub(current);
        double distance = delta.length();
        if (!Double.isFinite(distance)) {
            return Step.invalid(current);
        }
        if (distance <= arrivalRadius) {
            return Step.arrived(destination, normalizedOrZero(delta));
        }

        double dt = Math.min(dtSeconds, MAX_DT_SECONDS);
        double travel = speed * dt;
        if (!Double.isFinite(travel) || travel <= 0.0D) {
            return Step.invalid(current);
        }
        if (travel + arrivalRadius >= distance) {
            return Step.arrived(destination, normalizedOrZero(delta));
        }

        Vector3d desired = delta.div(distance);
        Vector3d direction = limitedDirection(lastDirection, desired, turnRateDegreesPerSecond, dt);
        Vector3d position = new Vector3d(current).fma(travel, direction);
        return finite(position)
                ? new Step(position, direction, false, true)
                : Step.invalid(current);
    }

    @Nonnull
    static Vector3d limitedDirection(@Nullable Vector3d lastDirection,
                                     @Nonnull Vector3d desired,
                                     double turnRateDegreesPerSecond,
                                     double dtSeconds) {
        if (!finite(lastDirection) || lastDirection.lengthSquared() <= EPSILON
                || !Double.isFinite(turnRateDegreesPerSecond) || turnRateDegreesPerSecond <= 0.0D) {
            return new Vector3d(desired);
        }
        Vector3d previous = new Vector3d(lastDirection).normalize();
        double dot = Math.max(-1.0D, Math.min(1.0D, previous.dot(desired)));
        double angle = Math.acos(dot);
        double maxAngle = Math.toRadians(turnRateDegreesPerSecond) * dtSeconds;
        if (!Double.isFinite(angle) || angle <= maxAngle || angle <= EPSILON) {
            return new Vector3d(desired);
        }

        Vector3d axis = new Vector3d(previous).cross(desired);
        if (axis.lengthSquared() <= EPSILON) {
            axis = Math.abs(previous.y) < 0.9D
                    ? new Vector3d(previous).cross(0.0D, 1.0D, 0.0D)
                    : new Vector3d(previous).cross(1.0D, 0.0D, 0.0D);
        }
        axis.normalize();
        return previous.rotateAxis(maxAngle, axis.x, axis.y, axis.z).normalize();
    }

    private static boolean finite(@Nullable Vector3d value) {
        return value != null
                && Double.isFinite(value.x)
                && Double.isFinite(value.y)
                && Double.isFinite(value.z);
    }

    @Nonnull
    private static Vector3d normalizedOrZero(@Nonnull Vector3d value) {
        return value.lengthSquared() <= EPSILON ? new Vector3d() : new Vector3d(value).normalize();
    }

    public record Step(Vector3d position, Vector3d direction, boolean arrived, boolean valid) {
        @Nonnull
        static Step invalid(@Nullable Vector3d current) {
            return new Step(current == null ? new Vector3d() : new Vector3d(current), new Vector3d(), false, false);
        }

        @Nonnull
        static Step arrived(@Nonnull Vector3d destination, @Nonnull Vector3d direction) {
            return new Step(new Vector3d(destination), new Vector3d(direction), true, true);
        }
    }
}
