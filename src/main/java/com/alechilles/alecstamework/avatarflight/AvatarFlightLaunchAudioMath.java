package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.AvatarFlightAudioSettings;
import javax.annotation.Nullable;

/**
 * Pure cadence, volume, and pitch interpolation for launch-charge audio.
 */
public final class AvatarFlightLaunchAudioMath {
    private AvatarFlightLaunchAudioMath() {
    }

    public static long pulseIntervalMs(@Nullable AvatarFlightAudioSettings settings, double progress) {
        AvatarFlightAudioSettings resolved = settings == null ? new AvatarFlightAudioSettings() : settings;
        return Math.max(1L, Math.round(interpolate(
                resolved.getLaunchChargeEarlyIntervalMs(),
                resolved.getLaunchChargeFullIntervalMs(),
                progress
        )));
    }

    public static float pulseVolume(@Nullable AvatarFlightAudioSettings settings, double progress) {
        AvatarFlightAudioSettings resolved = settings == null ? new AvatarFlightAudioSettings() : settings;
        return (float) interpolate(
                resolved.getLaunchChargeMinVolume(),
                resolved.getLaunchChargeMaxVolume(),
                progress
        );
    }

    public static float pulsePitch(@Nullable AvatarFlightAudioSettings settings, double progress) {
        AvatarFlightAudioSettings resolved = settings == null ? new AvatarFlightAudioSettings() : settings;
        return (float) interpolate(
                resolved.getLaunchChargeMinPitch(),
                resolved.getLaunchChargeMaxPitch(),
                progress
        );
    }

    private static double interpolate(double from, double to, double progress) {
        return from + (to - from) * clamp01(progress);
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }
}
