package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.server.npc.movement.controllers.MotionControllerFly;
import java.util.function.BooleanSupplier;
import javax.annotation.Nullable;

/** Applies the guarded controller transition used when a flying NPC physically touches down. */
final class LandingContactTransition {
    private LandingContactTransition() {
    }

    static boolean confirm(@Nullable String activeControllerType,
                           boolean onGround,
                           @Nullable BooleanSupplier switchToWalk) {
        if (!canConfirm(activeControllerType, onGround) || switchToWalk == null) {
            return false;
        }
        return switchToWalk.getAsBoolean();
    }

    static boolean canConfirm(@Nullable String activeControllerType, boolean onGround) {
        return MotionControllerFly.TYPE.equals(activeControllerType) && onGround;
    }
}
