package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.AvatarFlightLaunchSettings;
import javax.annotation.Nullable;

/**
 * Stateless charged-launch curve math for avatar flight.
 */
public final class AvatarFlightLaunchCurve {

    private AvatarFlightLaunchCurve() {
    }

    public record Impulse(double up, double forward, double charge) {
    }

    public static double charge(@Nullable AvatarFlightLaunchSettings settings, long holdMs) {
        if (settings == null || holdMs < settings.getMinChargeMs()) {
            return 0.0;
        }
        if (holdMs >= settings.getMaxChargeMs()) {
            return 1.0;
        }
        double range = Math.max(1.0, settings.getMaxChargeMs() - settings.getMinChargeMs());
        double normalized = (holdMs - settings.getMinChargeMs()) / range;
        return Math.pow(clamp01(normalized), positiveOrOne(settings.getChargeExponent()));
    }

    public static Impulse impulse(@Nullable AvatarFlightLaunchSettings settings, long holdMs) {
        if (settings == null || holdMs < settings.getMinChargeMs()) {
            return new Impulse(0.0, 0.0, 0.0);
        }
        double charge = charge(settings, holdMs);
        double up = interpolate(settings.getMinUpImpulse(), settings.getMaxUpImpulse(), charge);
        double forward = interpolate(settings.getMinForwardImpulse(), settings.getMaxForwardImpulse(), charge);
        return new Impulse(up, forward, charge);
    }

    public static double cost(@Nullable AvatarFlightLaunchSettings settings, long holdMs) {
        if (settings == null || holdMs < settings.getMinChargeMs()) {
            return 0.0;
        }
        double charge = charge(settings, holdMs);
        return charge >= settings.getFullChargeCostThreshold()
                ? settings.getFullChargeCost()
                : settings.getPartialChargeCost();
    }

    private static double interpolate(double min, double max, double amount) {
        return min + (max - min) * clamp01(amount);
    }

    private static double positiveOrOne(double value) {
        return Double.isFinite(value) && value > 0.0 ? value : 1.0;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
