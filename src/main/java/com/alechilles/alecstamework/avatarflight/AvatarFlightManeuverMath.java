package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import javax.annotation.Nullable;

/**
 * Stateless maneuver curve math for avatar flight tuning.
 */
public final class AvatarFlightManeuverMath {
    private static final double MAX_PITCH_RADIANS = Math.toRadians(70.0);

    private AvatarFlightManeuverMath() {
    }

    public static double updateLoad(double current,
                                    boolean active,
                                    double dt,
                                    double rampSeconds,
                                    double decaySeconds) {
        double load = clamp01(finiteOrZero(current));
        double seconds = finiteOrZero(dt);
        double rate = active
                ? 1.0 / Math.max(0.001, finiteOrZero(rampSeconds))
                : -1.0 / Math.max(0.001, finiteOrZero(decaySeconds));
        return clamp01(load + rate * Math.max(0.0, seconds));
    }

    public static double pitchPower(double pitchRadians, boolean down, double exponent) {
        double pitch = finiteOrZero(pitchRadians);
        if (down && pitch >= 0.0) {
            return 0.0;
        }
        if (!down && pitch <= 0.0) {
            return 0.0;
        }
        double normalized = clamp01(Math.abs(pitch) / MAX_PITCH_RADIANS);
        return Math.pow(normalized, positiveOrOne(exponent));
    }

    public static double climbEligibility(double horizontalSpeed, @Nullable TwAvatarFlightConfig config) {
        if (config == null) {
            return 0.0;
        }
        double speed = finiteOrZero(horizontalSpeed);
        double neutral = finiteOrZero(config.getMovement().getNeutralGlideSpeed());
        double maxGlide = finiteOrZero(config.getMovement().getMaxGlideSpeed());
        double range = Math.max(0.001, maxGlide - neutral);
        double ratio = clamp01((speed - neutral) / range);
        if (ratio <= 0.0) {
            return 0.0;
        }
        return Math.pow(ratio, Math.max(0.0, finiteOrZero(config.getCurve().getClimbSpeedEligibilityExponent())));
    }

    public static double decayBoostedExcess(double horizontalSpeed, @Nullable TwAvatarFlightConfig config, double dt) {
        double speed = Math.max(0.0, finiteOrZero(horizontalSpeed));
        if (config == null) {
            return speed;
        }
        double naturalCap = Math.max(0.0, finiteOrZero(config.getMovement().getMaxGlideSpeed()));
        if (speed <= naturalCap) {
            return speed;
        }
        double seconds = finiteOrZero(dt);
        double decay = finiteOrZero(config.getCurve().getBoostedSpeedDecay());
        if (seconds <= 0.0 || decay <= 0.0) {
            return speed;
        }
        return Math.max(naturalCap, speed - decay * seconds);
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    private static double positiveOrOne(double value) {
        return Double.isFinite(value) && value > 0.0 ? value : 1.0;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
