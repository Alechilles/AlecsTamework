package com.alechilles.alecstamework.npc.movement;

import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import javax.annotation.Nonnull;

final class TameworkFlyAnimationState {
    static final double HORIZONTAL_IDLE_SPEED = 0.05;
    private static final double HORIZONTAL_INPUT_DEAD_ZONE = 0.025;

    private TameworkFlyAnimationState() {
    }

    static boolean resolveHorizontalIdle(TameworkRideMountComponent ride, double horizontalSpeed) {
        return ride != null ? !hasHorizontalInputIntent(ride) : horizontalSpeed < HORIZONTAL_IDLE_SPEED;
    }

    static boolean resolveFast(TameworkRideMountComponent ride, boolean horizontalIdle) {
        return !horizontalIdle && ride != null && ride.isRiderSprinting();
    }

    private static boolean hasHorizontalInputIntent(@Nonnull TameworkRideMountComponent ride) {
        if (!ride.hasWishMovement()) {
            return false;
        }
        double horizontalIntent = Math.sqrt(ride.getWishX() * ride.getWishX() + ride.getWishZ() * ride.getWishZ());
        return horizontalIntent > HORIZONTAL_INPUT_DEAD_ZONE;
    }
}
