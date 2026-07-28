package com.alechilles.alecstamework.companion.coop.runtime;

import com.hypixel.hytale.server.core.universe.world.World;
import javax.annotation.Nonnull;

/**
 * Best-effort presentation seam invoked on the world thread at a physical coop transition.
 *
 * <p>Implementations must not throw or participate in persistence success decisions.</p>
 */
@FunctionalInterface
public interface CoopTransitionEffectSink {
    CoopTransitionEffectSink NONE = (world, x, y, z, coopId) -> {
    };

    /** Plays optional presentation for one newly applied physical transition. */
    void play(
            @Nonnull World world,
            double x,
            double y,
            double z,
            @Nonnull String coopId
    );
}
