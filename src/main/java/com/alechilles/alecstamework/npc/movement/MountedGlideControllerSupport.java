package com.alechilles.alecstamework.npc.movement;

import com.alechilles.alecstamework.npc.components.TameworkMountedGlideComponent;
import javax.annotation.Nullable;

/**
 * Pure helpers shared by mounted glide motion-controller code and tests.
 */
final class MountedGlideControllerSupport {
    private MountedGlideControllerSupport() {
    }

    static double resolveMountedClientSpeed(TameworkMountedGlideComponent glide, double fallback) {
        if (!shouldRunFlyController(glide) || glide.getGlideSpeed() <= 0.0) {
            return fallback;
        }
        return glide.getGlideSpeed();
    }

    static boolean shouldRunFlyController(@Nullable TameworkMountedGlideComponent glide) {
        return glide != null && glide.isFlightActive();
    }

    static double resolveMountedSpeedLimit(boolean ridden, double activeGlideSpeed, double fallback) {
        if (!ridden || activeGlideSpeed <= 0.0) {
            return fallback;
        }
        return activeGlideSpeed;
    }
}
