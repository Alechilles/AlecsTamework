package com.alechilles.alecstamework.npc.movement;

import org.joml.Vector3d;
import javax.annotation.Nonnull;

final class TameworkFlyVisualState {
    private static final double INPUT_DEAD_ZONE = 0.01;
    private static final float MAX_VISUAL_PITCH = (float) Math.toRadians(40.0);
    private static final float VISUAL_PITCH_RADIANS_PER_SECOND = MAX_VISUAL_PITCH;

    private TameworkFlyVisualState() {
    }

    static float resolveVisualPitch(float targetPitch, @Nonnull Vector3d velocity) {
        return isVerticalDominantFlight(velocity) ? 0.0f : limitPitch(targetPitch);
    }

    static float limitPitch(float pitch) {
        return clamp(pitch, -MAX_VISUAL_PITCH, MAX_VISUAL_PITCH);
    }

    static float approachVisualAngle(float current, float target, double dt) {
        float maxDelta = (float) Math.max(0.0, VISUAL_PITCH_RADIANS_PER_SECOND * dt);
        if (Math.abs(target - current) <= maxDelta) {
            return target;
        }
        return current + Math.copySign(maxDelta, target - current);
    }

    static boolean isVerticalDominantFlight(@Nonnull Vector3d velocity) {
        double horizontal = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        double vertical = Math.abs(velocity.y);
        return vertical > INPUT_DEAD_ZONE && vertical > horizontal;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
