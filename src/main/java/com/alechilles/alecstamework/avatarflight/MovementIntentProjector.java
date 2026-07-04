package com.alechilles.alecstamework.avatarflight;

import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.Vector3d;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Projects packet movement vectors onto the player's current facing basis.
 */
public final class MovementIntentProjector {
    private static final double MIN_HORIZONTAL_LENGTH = 0.0001;

    private MovementIntentProjector() {
    }

    @Nonnull
    public static MovementIntent projectPacket(
            @Nullable Position wishMovement,
            @Nullable Vector3d velocity,
            @Nullable Position absolutePosition,
            @Nullable PositionSnapshot previousAbsolutePosition,
            @Nonnull String basisName,
            @Nonnull DirectionSnapshot basis) {
        AxisProjection wish = wishMovement == null
                ? AxisProjection.none()
                : project(wishMovement.x, wishMovement.z, basis);
        AxisProjection velocityProjection = velocity == null
                ? AxisProjection.none()
                : project(velocity.x, velocity.z, basis);
        AxisProjection absolute = resolveAbsolute(absolutePosition, previousAbsolutePosition, basis);
        return new MovementIntent(basisName, basis, wish, velocityProjection, absolute);
    }

    @Nonnull
    private static AxisProjection resolveAbsolute(@Nullable Position absolutePosition,
                                                  @Nullable PositionSnapshot previous,
                                                  @Nonnull DirectionSnapshot basis) {
        if (absolutePosition == null) {
            return AxisProjection.none();
        }
        if (previous == null) {
            return AxisProjection.first();
        }
        return project(absolutePosition.x - previous.x(), absolutePosition.z - previous.z(), basis);
    }

    @Nonnull
    public static AxisProjection project(double worldX, double worldZ, @Nonnull DirectionSnapshot basis) {
        double horizontalLength = Math.sqrt(worldX * worldX + worldZ * worldZ);
        if (horizontalLength <= MIN_HORIZONTAL_LENGTH) {
            return AxisProjection.idle();
        }
        double normalizedX = worldX / horizontalLength;
        double normalizedZ = worldZ / horizontalLength;
        double forwardX = -Math.sin(basis.yaw());
        double forwardZ = -Math.cos(basis.yaw());
        double rightX = -Math.sin(basis.yaw() - Math.PI / 2.0);
        double rightZ = -Math.cos(basis.yaw() - Math.PI / 2.0);
        double strafe = clamp(normalizedX * rightX + normalizedZ * rightZ, -1.0, 1.0);
        double forward = clamp(normalizedX * forwardX + normalizedZ * forwardZ, -1.0, 1.0);
        return new AxisProjection(forward, strafe, horizontalLength, label(forward, strafe));
    }

    @Nonnull
    private static String label(double forward, double strafe) {
        double absForward = Math.abs(forward);
        double absStrafe = Math.abs(strafe);
        if (absForward < 0.25 && absStrafe < 0.25) {
            return "idle";
        }
        if (absForward >= absStrafe * 1.25) {
            return forward > 0.0 ? "forward" : "back";
        }
        if (absStrafe >= absForward * 1.25) {
            return strafe > 0.0 ? "right" : "left";
        }
        String vertical = forward > 0.0 ? "forward" : "back";
        String horizontal = strafe > 0.0 ? "right" : "left";
        return vertical + "+" + horizontal;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @Nonnull
    public static DirectionSnapshot fromDirection(@Nullable Direction direction) {
        return direction == null
                ? new DirectionSnapshot(0.0, 0.0, 0.0)
                : new DirectionSnapshot(direction.yaw, direction.pitch, direction.roll);
    }

    @Nonnull
    public static PositionSnapshot fromPosition(@Nonnull Position position) {
        return new PositionSnapshot(position.x, position.y, position.z);
    }

    public record PositionSnapshot(double x, double y, double z) {
    }

    public record DirectionSnapshot(double yaw, double pitch, double roll) {
    }

    public record AxisProjection(double forward, double strafe, double magnitude, @Nonnull String label) {
        @Nonnull
        public static AxisProjection none() {
            return new AxisProjection(0.0, 0.0, 0.0, "<none>");
        }

        @Nonnull
        public static AxisProjection first() {
            return new AxisProjection(0.0, 0.0, 0.0, "<first>");
        }

        @Nonnull
        public static AxisProjection idle() {
            return new AxisProjection(0.0, 0.0, 0.0, "idle");
        }

        public boolean hasUsableIntent() {
            return magnitude > MIN_HORIZONTAL_LENGTH && !"<none>".equals(label) && !"<first>".equals(label);
        }

        @Nonnull
        public String summary(@Nonnull String source) {
            return source + ":" + label + "(f=" + formatAxis(forward) +
                    ",s=" + formatAxis(strafe) +
                    ",mag=" + formatMagnitude(magnitude) + ")";
        }

        @Nonnull
        public String signature() {
            return label + ":" + formatAxis(forward) + ":" + formatAxis(strafe);
        }
    }

    public record MovementIntent(@Nonnull String basisName,
                                 @Nonnull DirectionSnapshot basis,
                                 @Nonnull AxisProjection wish,
                                 @Nonnull AxisProjection velocity,
                                 @Nonnull AxisProjection absolute) {
        @Nonnull
        public AxisProjection primaryProjection() {
            if (wish.hasUsableIntent()) {
                return wish;
            }
            if (velocity.hasUsableIntent()) {
                return velocity;
            }
            if (absolute.hasUsableIntent()) {
                return absolute;
            }
            return AxisProjection.idle();
        }

        @Nonnull
        public String summary() {
            return "basis=" + basisName + "(yaw=" + String.format("%.3f", basis.yaw()) + ") "
                    + wish.summary("wish") + " "
                    + velocity.summary("velocity") + " "
                    + absolute.summary("absoluteDelta");
        }

        @Nonnull
        public String signature() {
            return basisName + ":" + String.format("%.2f", basis.yaw()) + "|"
                    + wish.signature() + "|"
                    + velocity.signature() + "|"
                    + absolute.signature();
        }
    }

    @Nonnull
    private static String formatAxis(double value) {
        return String.format("%.2f", value);
    }

    @Nonnull
    private static String formatMagnitude(double value) {
        return String.format("%.3f", value);
    }
}
