package com.alechilles.alecstamework.npc.movement;

import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import javax.annotation.Nonnull;
import org.joml.Vector3d;

/**
 * Short mounted-flight recovery that stops rider input from repeatedly shoving a mount into collidable geometry.
 */
final class MountedFlightCollisionRecovery {
    private static final int RECOVERY_TICKS = 10;
    private static final double CLIMB_INPUT = 0.65;
    private static final double HORIZONTAL_OBSTRUCTION_EPSILON_SQUARED = 0.01;

    private MountedFlightCollisionRecovery() {
    }

    static void apply(@Nonnull TameworkRideMountComponent ride,
                      @Nonnull Vector3d translation,
                      @Nonnull State state) {
        state.setActive(false);
        if (state.getTicks() <= 0) {
            return;
        }
        state.decrementTicks();
        state.setActive(true);
        translation.x = 0.0;
        translation.z = 0.0;
        if (!ride.isRiderCrouching()) {
            translation.y = Math.max(translation.y, CLIMB_INPUT);
        }
        normalize(translation);
    }

    static boolean recordMoveResult(@Nonnull Vector3d requestedMove,
                                    boolean obstructed,
                                    boolean ridden,
                                    boolean airborne,
                                    @Nonnull Vector3d lastVelocity,
                                    @Nonnull State state) {
        if (!ridden) {
            state.reset();
            return false;
        }
        if (!obstructed || !airborne
                || horizontalLengthSquared(requestedMove) <= HORIZONTAL_OBSTRUCTION_EPSILON_SQUARED) {
            return false;
        }
        state.start();
        lastVelocity.x = 0.0;
        lastVelocity.z = 0.0;
        if (lastVelocity.y < 0.0) {
            lastVelocity.y = 0.0;
        }
        return true;
    }

    private static double horizontalLengthSquared(@Nonnull Vector3d value) {
        return value.x * value.x + value.z * value.z;
    }

    private static void normalize(@Nonnull Vector3d value) {
        double length = value.length();
        if (length > 1.0) {
            value.mul(1.0 / length);
        }
    }

    static final class State {
        private int ticks;
        private boolean active;

        void start() {
            ticks = RECOVERY_TICKS;
        }

        void decrementTicks() {
            ticks = Math.max(0, ticks - 1);
        }

        void reset() {
            ticks = 0;
            active = false;
        }

        int getTicks() {
            return ticks;
        }

        boolean isActive() {
            return active;
        }

        void setActive(boolean active) {
            this.active = active;
        }
    }
}
