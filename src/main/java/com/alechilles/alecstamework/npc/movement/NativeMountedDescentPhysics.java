package com.alechilles.alecstamework.npc.movement;

import com.hypixel.hytale.server.core.modules.physics.util.PhysicsConstants;
import javax.annotation.Nonnull;

/**
 * Deterministic vertical-velocity calculations for configured native mount descent.
 */
public final class NativeMountedDescentPhysics {
    private NativeMountedDescentPhysics() {
    }

    /**
     * Advances an already-descending velocity using the configured gravity multiplier and cap.
     */
    public static double advanceDescending(double verticalVelocity,
                                           @Nonnull Settings settings,
                                           double dt) {
        if (verticalVelocity >= 0.0 || !settings.isValid() || !Double.isFinite(dt) || dt <= 0.0) {
            return verticalVelocity;
        }
        double acceleratedVelocity = verticalVelocity
                - PhysicsConstants.GRAVITY_ACCELERATION * settings.fallAccelerationMultiplier * dt;
        return Math.max(-settings.maxDownwardSpeed, acceleratedVelocity);
    }

    /** Typed settings decoded from the native mounted-descent asset overlay. */
    public record Settings(double maxDownwardSpeed, double fallAccelerationMultiplier) {
        public boolean isValid() {
            return Double.isFinite(maxDownwardSpeed)
                    && maxDownwardSpeed > 0.0
                    && Double.isFinite(fallAccelerationMultiplier)
                    && fallAccelerationMultiplier > 0.0;
        }
    }
}
