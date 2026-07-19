package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Configures positional avatar-flight sound presentation.
 */
public final class AvatarFlightAudioSettings {
    public static final String DEFAULT_CHARGE_SOUND = "SFX_Tamework_AvatarFlight_Launch_Charge_Pulse";
    public static final String DEFAULT_READY_SOUND = "SFX_Tamework_AvatarFlight_Launch_Ready";
    public static final String DEFAULT_CANCEL_SOUND = "SFX_Tamework_AvatarFlight_Launch_Cancel";
    public static final String DEFAULT_PARTIAL_SOUND = "SFX_Tamework_AvatarFlight_Launch_Release_Partial";
    public static final String DEFAULT_MID_SOUND = "SFX_Tamework_AvatarFlight_Launch_Release_Mid";
    public static final String DEFAULT_FULL_SOUND = "SFX_Tamework_AvatarFlight_Launch_Release_Full";
    public static final String DEFAULT_UPWARD_FLAP_SOUND = "SFX_Tamework_AvatarFlight_Flap";
    public static final String DEFAULT_FORWARD_BOOST_SOUND = "SFX_Tamework_AvatarFlight_Forward_Boost";
    public static final String DEFAULT_AIRBRAKE_SOUND = "SFX_Tamework_AvatarFlight_Airbrake";
    public static final double DEFAULT_EARLY_INTERVAL_MS = 600.0;
    public static final double DEFAULT_FULL_INTERVAL_MS = 180.0;
    public static final double DEFAULT_MIN_VOLUME = 0.32;
    public static final double DEFAULT_MAX_VOLUME = 0.90;
    public static final double DEFAULT_MIN_PITCH = 0.85;
    public static final double DEFAULT_MAX_PITCH = 1.12;
    private static final double MIN_INTERVAL_MS = 50.0;
    private static final double MIN_MODIFIER = 0.01;
    private static final double MAX_MODIFIER = 4.0;

    public static final BuilderCodec<AvatarFlightAudioSettings> CODEC = BuilderCodec.builder(
            AvatarFlightAudioSettings.class,
            AvatarFlightAudioSettings::new
    )
            .<Boolean>append(new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (settings, value) -> settings.enabled = value == null || value,
                    settings -> settings.enabled)
            .documentation("Whether avatar-flight audio is enabled. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("LaunchChargeSoundEvent", Codec.STRING),
                    (settings, value) -> settings.launchChargeSoundEvent = blankOrTrim(value),
                    settings -> settings.launchChargeSoundEvent)
            .documentation("Short positional wind sound emitted repeatedly while launch charge builds. Blank disables this cue. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("LaunchChargeEarlyIntervalMs", Codec.DOUBLE),
                    (settings, value) -> settings.launchChargeEarlyIntervalMs = intervalOrDefault(value, DEFAULT_EARLY_INTERVAL_MS),
                    settings -> settings.launchChargeEarlyIntervalMs)
            .documentation("Charge-pulse interval at the start of launch charging. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("LaunchChargeFullIntervalMs", Codec.DOUBLE),
                    (settings, value) -> settings.launchChargeFullIntervalMs = intervalOrDefault(value, DEFAULT_FULL_INTERVAL_MS),
                    settings -> settings.launchChargeFullIntervalMs)
            .documentation("Charge-pulse interval at full launch charge. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("LaunchChargeMinVolume", Codec.DOUBLE),
                    (settings, value) -> settings.launchChargeMinVolume = modifierOrDefault(value, DEFAULT_MIN_VOLUME),
                    settings -> settings.launchChargeMinVolume)
            .documentation("Linear volume modifier for the first launch-charge pulse. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("LaunchChargeMaxVolume", Codec.DOUBLE),
                    (settings, value) -> settings.launchChargeMaxVolume = modifierOrDefault(value, DEFAULT_MAX_VOLUME),
                    settings -> settings.launchChargeMaxVolume)
            .documentation("Linear volume modifier for launch-charge pulses at full charge. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("LaunchChargeMinPitch", Codec.DOUBLE),
                    (settings, value) -> settings.launchChargeMinPitch = modifierOrDefault(value, DEFAULT_MIN_PITCH),
                    settings -> settings.launchChargeMinPitch)
            .documentation("Linear pitch modifier for the first launch-charge pulse. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("LaunchChargeMaxPitch", Codec.DOUBLE),
                    (settings, value) -> settings.launchChargeMaxPitch = modifierOrDefault(value, DEFAULT_MAX_PITCH),
                    settings -> settings.launchChargeMaxPitch)
            .documentation("Linear pitch modifier for launch-charge pulses at full charge. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("LaunchReadySoundEvent", Codec.STRING),
                    (settings, value) -> settings.launchReadySoundEvent = blankOrTrim(value),
                    settings -> settings.launchReadySoundEvent)
            .documentation("One-shot positional cue emitted once when launch charge first reaches full. Blank disables this cue. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("LaunchCancelSoundEvent", Codec.STRING),
                    (settings, value) -> settings.launchCancelSoundEvent = blankOrTrim(value),
                    settings -> settings.launchCancelSoundEvent)
            .documentation("One-shot positional cue emitted when launch charge is released too early. Blank disables this cue. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("LaunchReleasePartialSoundEvent", Codec.STRING),
                    (settings, value) -> settings.launchReleasePartialSoundEvent = blankOrTrim(value),
                    settings -> settings.launchReleasePartialSoundEvent)
            .documentation("One-shot positional cue for a partial launch release. Blank disables this cue. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("LaunchReleaseMidSoundEvent", Codec.STRING),
                    (settings, value) -> settings.launchReleaseMidSoundEvent = blankOrTrim(value),
                    settings -> settings.launchReleaseMidSoundEvent)
            .documentation("One-shot positional cue for a mid-strength launch release. Blank disables this cue. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("LaunchReleaseFullSoundEvent", Codec.STRING),
                    (settings, value) -> settings.launchReleaseFullSoundEvent = blankOrTrim(value),
                    settings -> settings.launchReleaseFullSoundEvent)
            .documentation("One-shot positional cue for a full launch release. Blank disables this cue. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("UpwardFlapSoundEvent", Codec.STRING),
                    (settings, value) -> settings.upwardFlapSoundEvent = blankOrTrim(value),
                    settings -> settings.upwardFlapSoundEvent)
            .documentation("One-shot positional cue for an accepted upward flap. Blank disables this cue. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("ForwardBoostSoundEvent", Codec.STRING),
                    (settings, value) -> settings.forwardBoostSoundEvent = blankOrTrim(value),
                    settings -> settings.forwardBoostSoundEvent)
            .documentation("One-shot positional cue for an accepted forward boost. Blank disables this cue. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("AirbrakeSoundEvent", Codec.STRING),
                    (settings, value) -> settings.airbrakeSoundEvent = blankOrTrim(value),
                    settings -> settings.airbrakeSoundEvent)
            .documentation("One-shot positional cue when airbrake first becomes active. Blank disables this cue. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("IdleFlightFlapSoundEvent", Codec.STRING),
                    (settings, value) -> settings.idleFlightFlapSoundEvent = blankOrTrim(value),
                    settings -> settings.idleFlightFlapSoundEvent)
            .documentation("One-shot positional wing cue scheduled while hovering. Blank disables hover cadence. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("IdleFlightFlapIntervalMs", Codec.DOUBLE),
                    (settings, value) -> settings.idleFlightFlapIntervalMs = intervalOrDisabled(value),
                    settings -> settings.idleFlightFlapIntervalMs)
            .documentation("Milliseconds between hover wing cues. Zero disables hover cadence. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("FlightFlapSoundEvent", Codec.STRING),
                    (settings, value) -> settings.flightFlapSoundEvent = blankOrTrim(value),
                    settings -> settings.flightFlapSoundEvent)
            .documentation("One-shot positional wing cue scheduled during normal forward flight. Blank disables forward-flight cadence. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("FlightFlapIntervalMs", Codec.DOUBLE),
                    (settings, value) -> settings.flightFlapIntervalMs = intervalOrDisabled(value),
                    settings -> settings.flightFlapIntervalMs)
            .documentation("Milliseconds between normal forward-flight wing cues. Zero disables forward-flight cadence. Inheritance: missing nested key inherits parent value.")
            .add()
            .build();

    private boolean enabled = true;
    private String launchChargeSoundEvent = DEFAULT_CHARGE_SOUND;
    private double launchChargeEarlyIntervalMs = DEFAULT_EARLY_INTERVAL_MS;
    private double launchChargeFullIntervalMs = DEFAULT_FULL_INTERVAL_MS;
    private double launchChargeMinVolume = DEFAULT_MIN_VOLUME;
    private double launchChargeMaxVolume = DEFAULT_MAX_VOLUME;
    private double launchChargeMinPitch = DEFAULT_MIN_PITCH;
    private double launchChargeMaxPitch = DEFAULT_MAX_PITCH;
    private String launchReadySoundEvent = DEFAULT_READY_SOUND;
    private String launchCancelSoundEvent = DEFAULT_CANCEL_SOUND;
    private String launchReleasePartialSoundEvent = DEFAULT_PARTIAL_SOUND;
    private String launchReleaseMidSoundEvent = DEFAULT_MID_SOUND;
    private String launchReleaseFullSoundEvent = DEFAULT_FULL_SOUND;
    private String upwardFlapSoundEvent = DEFAULT_UPWARD_FLAP_SOUND;
    private String forwardBoostSoundEvent = DEFAULT_FORWARD_BOOST_SOUND;
    private String airbrakeSoundEvent = DEFAULT_AIRBRAKE_SOUND;
    private String idleFlightFlapSoundEvent = "";
    private double idleFlightFlapIntervalMs;
    private String flightFlapSoundEvent = "";
    private double flightFlapIntervalMs;

    public void inheritMissingFrom(@Nonnull AvatarFlightAudioSettings parent, @Nullable Set<String> keys) {
        if (keys == null) return;
        if (!keys.contains("Enabled")) enabled = parent.enabled;
        if (!keys.contains("LaunchChargeSoundEvent")) launchChargeSoundEvent = parent.launchChargeSoundEvent;
        if (!keys.contains("LaunchChargeEarlyIntervalMs")) launchChargeEarlyIntervalMs = parent.launchChargeEarlyIntervalMs;
        if (!keys.contains("LaunchChargeFullIntervalMs")) launchChargeFullIntervalMs = parent.launchChargeFullIntervalMs;
        if (!keys.contains("LaunchChargeMinVolume")) launchChargeMinVolume = parent.launchChargeMinVolume;
        if (!keys.contains("LaunchChargeMaxVolume")) launchChargeMaxVolume = parent.launchChargeMaxVolume;
        if (!keys.contains("LaunchChargeMinPitch")) launchChargeMinPitch = parent.launchChargeMinPitch;
        if (!keys.contains("LaunchChargeMaxPitch")) launchChargeMaxPitch = parent.launchChargeMaxPitch;
        if (!keys.contains("LaunchReadySoundEvent")) launchReadySoundEvent = parent.launchReadySoundEvent;
        if (!keys.contains("LaunchCancelSoundEvent")) launchCancelSoundEvent = parent.launchCancelSoundEvent;
        if (!keys.contains("LaunchReleasePartialSoundEvent")) launchReleasePartialSoundEvent = parent.launchReleasePartialSoundEvent;
        if (!keys.contains("LaunchReleaseMidSoundEvent")) launchReleaseMidSoundEvent = parent.launchReleaseMidSoundEvent;
        if (!keys.contains("LaunchReleaseFullSoundEvent")) launchReleaseFullSoundEvent = parent.launchReleaseFullSoundEvent;
        if (!keys.contains("UpwardFlapSoundEvent")) upwardFlapSoundEvent = parent.upwardFlapSoundEvent;
        if (!keys.contains("ForwardBoostSoundEvent")) forwardBoostSoundEvent = parent.forwardBoostSoundEvent;
        if (!keys.contains("AirbrakeSoundEvent")) airbrakeSoundEvent = parent.airbrakeSoundEvent;
        if (!keys.contains("IdleFlightFlapSoundEvent")) idleFlightFlapSoundEvent = parent.idleFlightFlapSoundEvent;
        if (!keys.contains("IdleFlightFlapIntervalMs")) idleFlightFlapIntervalMs = parent.idleFlightFlapIntervalMs;
        if (!keys.contains("FlightFlapSoundEvent")) flightFlapSoundEvent = parent.flightFlapSoundEvent;
        if (!keys.contains("FlightFlapIntervalMs")) flightFlapIntervalMs = parent.flightFlapIntervalMs;
    }

    public boolean isEnabled() { return enabled; }
    public String getLaunchChargeSoundEvent() { return blankOrTrim(launchChargeSoundEvent); }
    public long getLaunchChargeEarlyIntervalMs() { return Math.round(intervalOrDefault(launchChargeEarlyIntervalMs, DEFAULT_EARLY_INTERVAL_MS)); }
    public long getLaunchChargeFullIntervalMs() {
        return Math.min(getLaunchChargeEarlyIntervalMs(), Math.round(intervalOrDefault(launchChargeFullIntervalMs, DEFAULT_FULL_INTERVAL_MS)));
    }
    public double getLaunchChargeMinVolume() { return modifierOrDefault(launchChargeMinVolume, DEFAULT_MIN_VOLUME); }
    public double getLaunchChargeMaxVolume() { return Math.max(getLaunchChargeMinVolume(), modifierOrDefault(launchChargeMaxVolume, DEFAULT_MAX_VOLUME)); }
    public double getLaunchChargeMinPitch() { return modifierOrDefault(launchChargeMinPitch, DEFAULT_MIN_PITCH); }
    public double getLaunchChargeMaxPitch() { return Math.max(getLaunchChargeMinPitch(), modifierOrDefault(launchChargeMaxPitch, DEFAULT_MAX_PITCH)); }
    public String getLaunchReadySoundEvent() { return blankOrTrim(launchReadySoundEvent); }
    public String getLaunchCancelSoundEvent() { return blankOrTrim(launchCancelSoundEvent); }
    public String getLaunchReleasePartialSoundEvent() { return blankOrTrim(launchReleasePartialSoundEvent); }
    public String getLaunchReleaseMidSoundEvent() { return blankOrTrim(launchReleaseMidSoundEvent); }
    public String getLaunchReleaseFullSoundEvent() { return blankOrTrim(launchReleaseFullSoundEvent); }
    public String getUpwardFlapSoundEvent() { return blankOrTrim(upwardFlapSoundEvent); }
    public String getForwardBoostSoundEvent() { return blankOrTrim(forwardBoostSoundEvent); }
    public String getAirbrakeSoundEvent() { return blankOrTrim(airbrakeSoundEvent); }
    public String getIdleFlightFlapSoundEvent() { return blankOrTrim(idleFlightFlapSoundEvent); }
    public long getIdleFlightFlapIntervalMs() { return Math.round(intervalOrDisabled(idleFlightFlapIntervalMs)); }
    public String getFlightFlapSoundEvent() { return blankOrTrim(flightFlapSoundEvent); }
    public long getFlightFlapIntervalMs() { return Math.round(intervalOrDisabled(flightFlapIntervalMs)); }

    private static String blankOrTrim(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    private static double intervalOrDefault(@Nullable Double value, double fallback) {
        double resolved = value != null && Double.isFinite(value) ? value : fallback;
        return Math.max(MIN_INTERVAL_MS, resolved);
    }

    private static double intervalOrDisabled(@Nullable Double value) {
        if (value == null || !Double.isFinite(value) || value <= 0.0) return 0.0;
        return Math.max(MIN_INTERVAL_MS, value);
    }

    private static double modifierOrDefault(@Nullable Double value, double fallback) {
        double resolved = value != null && Double.isFinite(value) ? value : fallback;
        return Math.max(MIN_MODIFIER, Math.min(MAX_MODIFIER, resolved));
    }
}
