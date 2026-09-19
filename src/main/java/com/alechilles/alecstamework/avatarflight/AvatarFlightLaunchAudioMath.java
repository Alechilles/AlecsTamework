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
        return Math.max(1L, Math.round(AvatarFlightLaunchCurve.interpolate(
                resolved.getLaunchChargeEarlyIntervalMs(),
                resolved.getLaunchChargeFullIntervalMs(),
                progress
        )));
    }

    public static float pulseVolume(@Nullable AvatarFlightAudioSettings settings, double progress) {
        AvatarFlightAudioSettings resolved = settings == null ? new AvatarFlightAudioSettings() : settings;
        return (float) AvatarFlightLaunchCurve.interpolate(
                resolved.getLaunchChargeMinVolume(),
                resolved.getLaunchChargeMaxVolume(),
                progress
        );
    }

    public static float pulsePitch(@Nullable AvatarFlightAudioSettings settings, double progress) {
        AvatarFlightAudioSettings resolved = settings == null ? new AvatarFlightAudioSettings() : settings;
        return (float) AvatarFlightLaunchCurve.interpolate(
                resolved.getLaunchChargeMinPitch(),
                resolved.getLaunchChargeMaxPitch(),
                progress
        );
    }
}
