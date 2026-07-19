package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.vfx.projectile.HomingVisualProjectileAnchor;
import com.alechilles.alecstamework.vfx.projectile.HomingVisualProjectileSpec;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Validated capture-channel cadence and steering settings for homing visual motes. */
public final class CaptureHomingProjectileSettings {
    public static final double DEFAULT_SPAWN_INTERVAL_SECONDS = 0.12D;
    public static final int DEFAULT_MAX_CONCURRENT = 16;
    private static final int MAX_ALLOWED_CONCURRENT = 64;

    private final boolean enabled;
    private final String modelId;
    private final double spawnIntervalSeconds;
    private final double speed;
    private final double turnRateDegreesPerSecond;
    private final double arrivalRadius;
    private final double lifetimeSeconds;
    private final int maxConcurrent;

    public CaptureHomingProjectileSettings(boolean enabled,
                                           @Nullable String modelId,
                                           double spawnIntervalSeconds,
                                           double speed,
                                           double turnRateDegreesPerSecond,
                                           double arrivalRadius,
                                           double lifetimeSeconds,
                                           int maxConcurrent) {
        this.enabled = enabled;
        this.modelId = modelId == null ? "" : modelId.trim();
        this.spawnIntervalSeconds = positive(spawnIntervalSeconds, DEFAULT_SPAWN_INTERVAL_SECONDS);
        HomingVisualProjectileSpec sanitized = new HomingVisualProjectileSpec(
                this.modelId,
                HomingVisualProjectileAnchor.HELD_ITEM,
                speed,
                turnRateDegreesPerSecond,
                arrivalRadius,
                lifetimeSeconds
        );
        this.speed = sanitized.speed();
        this.turnRateDegreesPerSecond = sanitized.turnRateDegreesPerSecond();
        this.arrivalRadius = sanitized.arrivalRadius();
        this.lifetimeSeconds = sanitized.lifetimeSeconds();
        this.maxConcurrent = Math.max(1, Math.min(MAX_ALLOWED_CONCURRENT,
                maxConcurrent <= 0 ? DEFAULT_MAX_CONCURRENT : maxConcurrent));
    }

    @Nonnull
    public static CaptureHomingProjectileSettings disabled() {
        return new CaptureHomingProjectileSettings(
                false, null, DEFAULT_SPAWN_INTERVAL_SECONDS,
                8.0D, 0.0D, 0.18D, 2.0D, DEFAULT_MAX_CONCURRENT
        );
    }

    public boolean isEnabled() {
        return enabled && !modelId.isBlank();
    }

    @Nonnull
    public String getModelId() {
        return modelId;
    }

    public double getSpawnIntervalSeconds() {
        return spawnIntervalSeconds;
    }

    public long getSpawnIntervalMs() {
        return Math.max(1L, Math.round(spawnIntervalSeconds * 1000.0D));
    }

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    @Nonnull
    public HomingVisualProjectileSpec toProjectileSpec() {
        return new HomingVisualProjectileSpec(
                modelId,
                HomingVisualProjectileAnchor.HELD_ITEM,
                speed,
                turnRateDegreesPerSecond,
                arrivalRadius,
                lifetimeSeconds
        );
    }

    private static double positive(double value, double fallback) {
        return Double.isFinite(value) && value > 0.0D ? value : fallback;
    }
}
