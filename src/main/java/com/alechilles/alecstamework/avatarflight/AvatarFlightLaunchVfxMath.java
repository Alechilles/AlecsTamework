package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.AvatarFlightLaunchSettings;
import com.alechilles.alecstamework.config.assets.AvatarFlightVfxSettings;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Pure cadence, scale, and release-tier math for launch particle presentation.
 */
public final class AvatarFlightLaunchVfxMath {

    public enum ReleaseTier {
        PARTIAL,
        MID,
        FULL
    }

    private AvatarFlightLaunchVfxMath() {
    }

    public static double chargeProgress(long heldMs, long maxChargeMs) {
        if (maxChargeMs <= 0L) return heldMs > 0L ? 1.0 : 0.0;
        return clamp01((double) Math.max(0L, heldMs) / maxChargeMs);
    }

    public static long pulseIntervalMs(@Nullable AvatarFlightVfxSettings settings, double progress) {
        AvatarFlightVfxSettings resolved = settings == null ? new AvatarFlightVfxSettings() : settings;
        double interval = interpolate(
                resolved.getLaunchChargeEarlyIntervalMs(),
                resolved.getLaunchChargeFullIntervalMs(),
                progress
        );
        return Math.max(1L, Math.round(interval));
    }

    public static double pulseScale(@Nullable AvatarFlightVfxSettings settings, double progress) {
        AvatarFlightVfxSettings resolved = settings == null ? new AvatarFlightVfxSettings() : settings;
        return interpolate(
                resolved.getLaunchChargeMinScale(),
                resolved.getLaunchChargeMaxScale(),
                progress
        );
    }

    @Nonnull
    public static ReleaseTier releaseTier(@Nullable AvatarFlightLaunchSettings launch,
                                          @Nullable AvatarFlightVfxSettings vfx,
                                          long holdMs) {
        AvatarFlightVfxSettings resolved = vfx == null ? new AvatarFlightVfxSettings() : vfx;
        double charge = AvatarFlightLaunchCurve.charge(launch, holdMs);
        if (charge >= resolved.getLaunchReleaseFullThreshold()) return ReleaseTier.FULL;
        if (charge >= resolved.getLaunchReleaseMidThreshold()) return ReleaseTier.MID;
        return ReleaseTier.PARTIAL;
    }

    private static double interpolate(double from, double to, double progress) {
        return from + (to - from) * clamp01(progress);
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }
}
