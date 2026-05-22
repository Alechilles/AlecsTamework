package com.alechilles.alecstamework.npc.movement;

import org.joml.Vector3d;
import javax.annotation.Nonnull;

final class RiddenBackwardBrake {
    private RiddenBackwardBrake() {
    }

    static boolean apply(@Nonnull Vector3d targetVelocity,
                         @Nonnull Vector3d currentVelocity,
                         @Nonnull State state,
                         boolean backwardInput,
                         double dt) {
        state.setBraking(false);
        if (!backwardInput) {
            state.reset();
            return false;
        }
        zeroHorizontalTargetVelocity(targetVelocity);
        state.setBraking(true);
        return true;
    }

    private static void zeroHorizontalTargetVelocity(@Nonnull Vector3d targetVelocity) {
        targetVelocity.x = 0.0;
        targetVelocity.z = 0.0;
    }

    static final class State {
        private boolean braking;

        boolean isBraking() {
            return braking;
        }

        private void setBraking(boolean braking) {
            this.braking = braking;
        }

        void reset() {
            braking = false;
        }
    }
}
