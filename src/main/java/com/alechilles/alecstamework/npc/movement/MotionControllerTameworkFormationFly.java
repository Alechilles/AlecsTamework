package com.alechilles.alecstamework.npc.movement;

import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.movement.controllers.MotionControllerFly;
import javax.annotation.Nonnull;

/**
 * Native fly movement with a pitch-aware speed scale for formation steering.
 */
public final class MotionControllerTameworkFormationFly extends MotionControllerFly {
    public MotionControllerTameworkFormationFly(@Nonnull BuilderSupport builderSupport,
                                                @Nonnull BuilderMotionControllerTameworkFormationFly builder) {
        super(builderSupport, builder);
    }

    double getSteeringSpeedScale() {
        return computeMaxSpeedFromPitch(getPitch()) * effectHorizontalSpeedMultiplier;
    }
}
