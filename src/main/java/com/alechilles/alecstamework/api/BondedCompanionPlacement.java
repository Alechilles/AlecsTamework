package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;

/** Immutable world-qualified placement supplied to a bonded action. */
public record BondedCompanionPlacement(
        @Nonnull String worldKey,
        double x,
        double y,
        double z,
        float pitchRadians,
        float yawRadians,
        float rollRadians
) {
    public BondedCompanionPlacement {
        worldKey = Objects.requireNonNull(worldKey, "worldKey").trim();
        if (worldKey.isEmpty()
                || !Double.isFinite(x)
                || !Double.isFinite(y)
                || !Double.isFinite(z)
                || !Float.isFinite(pitchRadians)
                || !Float.isFinite(yawRadians)
                || !Float.isFinite(rollRadians)) {
            throw new IllegalArgumentException(
                    "Finite world-qualified bonded placement is required."
            );
        }
    }
}
