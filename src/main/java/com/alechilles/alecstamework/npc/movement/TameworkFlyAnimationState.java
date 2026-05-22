package com.alechilles.alecstamework.npc.movement;

import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import javax.annotation.Nonnull;

final class TameworkFlyAnimationState {
    static final double HORIZONTAL_IDLE_SPEED = 0.05;
    private static final double FORWARD_INPUT_DEAD_ZONE = 0.25;
    private static final double FORWARD_INPUT_DOMINANCE = 0.5;

    private TameworkFlyAnimationState() {
    }

    static boolean resolveHorizontalIdle(TameworkRideMountComponent ride, double horizontalSpeed) {
        return resolveHorizontalIdle(ride, horizontalSpeed, false);
    }

    static boolean resolveHorizontalIdle(TameworkRideMountComponent ride,
                                         double horizontalSpeed,
                                         boolean verticalDominant) {
        if (verticalDominant) {
            return true;
        }
        return ride != null ? !hasHorizontalInputIntent(ride) : horizontalSpeed < HORIZONTAL_IDLE_SPEED;
    }

    static boolean resolveFast(TameworkRideMountComponent ride, boolean horizontalIdle) {
        return !horizontalIdle && ride != null && ride.isRiderSprinting();
    }

    private static boolean hasHorizontalInputIntent(@Nonnull TameworkRideMountComponent ride) {
        if (!ride.hasWishMovement()) {
            return false;
        }
        double forward = Math.abs(ride.getWishZ());
        double strafe = Math.abs(ride.getWishX());
        return forward > FORWARD_INPUT_DEAD_ZONE && forward >= strafe * FORWARD_INPUT_DOMINANCE;
    }
}
