package com.alechilles.alecstamework.npc.movement;

import com.alechilles.alecstamework.npc.components.TameworkMountedGlideComponent;

/**
 * Pure helpers shared by mounted glide motion-controller code and tests.
 */
final class MountedGlideControllerSupport {
    private MountedGlideControllerSupport() {
    }

    static double resolveMountedClientSpeed(TameworkMountedGlideComponent glide, double fallback) {
        if (glide == null || glide.getGlideSpeed() <= 0.0) {
            return fallback;
        }
        return glide.getGlideSpeed();
    }
}
