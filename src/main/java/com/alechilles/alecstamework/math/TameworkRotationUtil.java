package com.alechilles.alecstamework.math;

import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.vector.Transform;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Shared helpers for Update 5's split between Hytale rotations and JOML vectors.
 */
public final class TameworkRotationUtil {
    private TameworkRotationUtil() {
    }

    public static Vector3d directionFrom(@Nullable Rotation3f rotation) {
        if (rotation == null) {
            return Transform.getDirection(0.0F, 0.0F);
        }
        return Transform.getDirection(rotation.pitch(), rotation.yaw());
    }

    public static Rotation3f copyOrDefault(@Nullable Rotation3f rotation) {
        return rotation != null ? new Rotation3f(rotation) : new Rotation3f();
    }
}
