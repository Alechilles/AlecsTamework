package com.alechilles.alecstamework.npc.movement;

import com.alechilles.alecstamework.npc.movement.builders.BuilderBodyMotionTameworkMaintainDistance;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.corecomponents.movement.BodyMotionMaintainDistance;
import com.hypixel.hytale.server.npc.sensorinfo.parameterproviders.SingleDoubleParameterProvider;
import javax.annotation.Nonnull;

/**
 * Maintains a configured distance while positioning relative to a target's facing direction.
 */
public final class BodyMotionTameworkMaintainDistance extends BodyMotionMaintainDistance {
    public BodyMotionTameworkMaintainDistance(@Nonnull BuilderBodyMotionTameworkMaintainDistance builder,
                                              @Nonnull BuilderSupport support) {
        super(builder, support);

        SingleDoubleParameterProvider positioningAngleProvider =
                new SingleDoubleParameterProvider(positioningAngleProviderSlot);
        positioningAngleProvider.overrideDouble(Math.toRadians(builder.getPositioningAngle(support)));
        cachedPositioningAngleProvider = positioningAngleProvider;
        // The configured peaceful position must not be replaced by a combat sensor on first use.
        initialised = true;
    }
}
