package com.alechilles.alecstamework.vfx.projectile;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable, validated launch settings shared by interactions and capture-channel emitters. */
public record HomingVisualProjectileSpec(
        String modelId,
        HomingVisualProjectileAnchor destinationAnchor,
        double speed,
        double turnRateDegreesPerSecond,
        double arrivalRadius,
        double lifetimeSeconds) {
    private static final double DEFAULT_SPEED = 8.0D;
    private static final double DEFAULT_ARRIVAL_RADIUS = 0.18D;
    private static final double DEFAULT_LIFETIME_SECONDS = 2.0D;

    public HomingVisualProjectileSpec {
        modelId = clean(modelId);
        destinationAnchor = destinationAnchor == null
                ? HomingVisualProjectileAnchor.BODY
                : destinationAnchor;
        speed = positive(speed, DEFAULT_SPEED);
        turnRateDegreesPerSecond = nonNegative(turnRateDegreesPerSecond);
        arrivalRadius = positive(arrivalRadius, DEFAULT_ARRIVAL_RADIUS);
        lifetimeSeconds = positive(lifetimeSeconds, DEFAULT_LIFETIME_SECONDS);
    }

    public boolean isValid() {
        return !modelId.isBlank();
    }

    @Nonnull
    private static String clean(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    private static double positive(double value, double fallback) {
        return Double.isFinite(value) && value > 0.0D ? value : fallback;
    }

    private static double nonNegative(double value) {
        return Double.isFinite(value) && value >= 0.0D ? value : 0.0D;
    }
}
