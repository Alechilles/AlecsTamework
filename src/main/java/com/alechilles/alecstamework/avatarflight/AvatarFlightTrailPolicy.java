package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.AvatarFlightTrailSettings;
import javax.annotation.Nonnull;

/** Applies fast-glide trail thresholds without coupling the decision to Hytale runtime state. */
final class AvatarFlightTrailPolicy {

    private AvatarFlightTrailPolicy() {
    }

    static boolean shouldRunFastGlideTrail(boolean currentlyRunning,
                                           double horizontalSpeed,
                                           double maxGlideSpeed,
                                           @Nonnull AvatarFlightTrailSettings settings) {
        if (!settings.isEnabled()
                || settings.getFastGlideRootInteraction().isBlank()
                || !Double.isFinite(horizontalSpeed)
                || !Double.isFinite(maxGlideSpeed)
                || maxGlideSpeed <= 0.0) {
            return false;
        }
        double ratio = currentlyRunning
                ? settings.getFastGlideStopSpeedRatio()
                : settings.getFastGlideStartSpeedRatio();
        return horizontalSpeed >= maxGlideSpeed * ratio;
    }
}
