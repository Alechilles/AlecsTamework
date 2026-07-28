package com.alechilles.alecstamework.companion.placement;

import javax.annotation.Nonnull;

/**
 * Exact world-qualified transform frozen before one crash-recoverable companion spawn.
 *
 * <p>This value deliberately contains only Hytale-neutral primitives. Placement policy runs
 * before persistence submission; recovery reuses these exact values instead of resolving a new
 * location from mutable world state.</p>
 */
public record CompanionSpawnPlacement(
        @Nonnull String worldKey,
        double x,
        double y,
        double z,
        float pitchRadians,
        float yawRadians,
        float rollRadians
) {
    public CompanionSpawnPlacement {
        if (worldKey == null || worldKey.isBlank()
                || !Double.isFinite(x)
                || !Double.isFinite(y)
                || !Double.isFinite(z)
                || !Float.isFinite(pitchRadians)
                || !Float.isFinite(yawRadians)
                || !Float.isFinite(rollRadians)) {
            throw new IllegalArgumentException(
                    "Finite world-qualified companion spawn placement is required"
            );
        }
        worldKey = worldKey.trim();
    }
}
