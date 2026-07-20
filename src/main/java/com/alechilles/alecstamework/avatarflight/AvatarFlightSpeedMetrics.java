package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import javax.annotation.Nullable;

/**
 * Stateless speed math shared by avatar flight presentation and vigour balance checks.
 */
public final class AvatarFlightSpeedMetrics {

    private AvatarFlightSpeedMetrics() {
    }

    public static double horizontalSpeed(double x, double y, double z) {
        double finiteX = finiteOrZero(x);
        double finiteZ = finiteOrZero(z);
        return Math.sqrt(finiteX * finiteX + finiteZ * finiteZ);
    }

    public static double boostedHorizontalCap(@Nullable TwAvatarFlightConfig config) {
        if (config == null) {
            return 0.0;
        }
        double maxForwardSpeed = finiteOrZero(config.getMovement().getMaxForwardSpeed());
        double forwardImpulse = finiteOrZero(config.getBoost().getForwardImpulse());
        return Math.max(glideHorizontalCap(config), maxForwardSpeed + forwardImpulse);
    }

    public static double glideHorizontalCap(@Nullable TwAvatarFlightConfig config) {
        if (config == null) {
            return 0.0;
        }
        double maxForwardSpeed = finiteOrZero(config.getMovement().getMaxForwardSpeed());
        double maxGlideSpeed = finiteOrZero(config.getMovement().getMaxGlideSpeed());
        return Math.max(maxForwardSpeed, maxGlideSpeed);
    }

    public static double speedRatio(double horizontalSpeed, @Nullable TwAvatarFlightConfig config) {
        double cap = boostedHorizontalCap(config);
        if (cap <= 0.0) {
            return 0.0;
        }
        return clamp01(finiteOrZero(horizontalSpeed) / cap);
    }

    public static double fastFlightThreshold(@Nullable TwAvatarFlightConfig config) {
        if (config == null) {
            return 0.0;
        }
        return glideHorizontalCap(config)
                * finiteOrZero(config.getVigour().getFastFlightRechargeSpeedRatio());
    }

    public static boolean isFastFlightSpeed(double horizontalSpeed, @Nullable TwAvatarFlightConfig config) {
        double threshold = fastFlightThreshold(config);
        return threshold > 0.0 && Double.isFinite(horizontalSpeed) && horizontalSpeed >= threshold;
    }

    /**
     * @deprecated Use {@link #fastFlightThreshold(TwAvatarFlightConfig)}; the threshold now drives all fast-flight behavior.
     */
    @Deprecated
    public static double fastRechargeThreshold(@Nullable TwAvatarFlightConfig config) {
        return fastFlightThreshold(config);
    }

    /**
     * @deprecated Use {@link #isFastFlightSpeed(double, TwAvatarFlightConfig)}; recharge follows the shared fast-flight state.
     */
    @Deprecated
    public static boolean isFastFlightRechargeSpeed(double horizontalSpeed, @Nullable TwAvatarFlightConfig config) {
        return isFastFlightSpeed(horizontalSpeed, config);
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
