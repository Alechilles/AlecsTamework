package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * World-space particle presentation settings for avatar flight.
 */
public final class AvatarFlightVfxSettings {
    public static final String DEFAULT_CHARGE_SYSTEM = "Tamework_AvatarFlight_Launch_Charge_Pulse";
    public static final String DEFAULT_CANCEL_SYSTEM = "Tamework_AvatarFlight_Launch_Cancel";
    public static final String DEFAULT_PARTIAL_SYSTEM = "Tamework_AvatarFlight_Launch_Release_Partial";
    public static final String DEFAULT_MID_SYSTEM = "Tamework_AvatarFlight_Launch_Release_Mid";
    public static final String DEFAULT_FULL_SYSTEM = "Tamework_AvatarFlight_Launch_Release_Full";

    private static final double DEFAULT_GROUND_OFFSET_Y = 0.05;
    private static final double DEFAULT_MAX_DURATION_SECONDS = 1.25;
    private static final double DEFAULT_EARLY_INTERVAL_MS = 600.0;
    private static final double DEFAULT_FULL_INTERVAL_MS = 150.0;
    private static final double DEFAULT_CHARGE_MIN_SCALE = 0.65;
    private static final double DEFAULT_CHARGE_MAX_SCALE = 1.15;
    private static final double DEFAULT_CANCEL_SCALE = 0.70;
    private static final double DEFAULT_PARTIAL_SCALE = 0.75;
    private static final double DEFAULT_MID_SCALE = 1.0;
    private static final double DEFAULT_FULL_SCALE = 1.20;
    private static final double DEFAULT_MID_THRESHOLD = 0.45;
    private static final double DEFAULT_FULL_THRESHOLD = 0.80;
    private static final double MIN_INTERVAL_MS = 50.0;

    public static final BuilderCodec<AvatarFlightVfxSettings> CODEC = BuilderCodec.builder(
            AvatarFlightVfxSettings.class,
            AvatarFlightVfxSettings::new
    )
            .<Boolean>append(new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (settings, value) -> settings.enabled = value == null || value,
                    settings -> settings.enabled)
            .documentation("Whether avatar-flight particle presentation is enabled. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("GroundOffsetY", Codec.DOUBLE),
                    (settings, value) -> settings.groundOffsetY = finiteOrDefault(value, DEFAULT_GROUND_OFFSET_Y),
                    settings -> settings.groundOffsetY)
            .documentation("Vertical offset from the avatar foot position for launch particles. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("MaxDurationSeconds", Codec.DOUBLE),
                    (settings, value) -> settings.maxDurationSeconds = positiveOrDefault(value, DEFAULT_MAX_DURATION_SECONDS),
                    settings -> settings.maxDurationSeconds)
            .documentation("Defensive client-side duration limit for launch particle systems. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Boolean>append(new KeyedCodec<>("LaunchChargeEnabled", Codec.BOOLEAN),
                    (settings, value) -> settings.launchChargeEnabled = value == null || value,
                    settings -> settings.launchChargeEnabled)
            .documentation("Whether grounded launch charging emits pressure pulses. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("LaunchChargeParticleSystem", Codec.STRING),
                    (settings, value) -> settings.launchChargeParticleSystem = stringOrDefault(value, DEFAULT_CHARGE_SYSTEM),
                    settings -> settings.launchChargeParticleSystem)
            .documentation("Particle system emitted for each grounded launch-charge pulse. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("LaunchChargeEarlyIntervalMs", Codec.DOUBLE),
                    (settings, value) -> settings.launchChargeEarlyIntervalMs = intervalOrDefault(value, DEFAULT_EARLY_INTERVAL_MS),
                    settings -> settings.launchChargeEarlyIntervalMs)
            .documentation("Charge-pulse interval at the start of the hold. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("LaunchChargeFullIntervalMs", Codec.DOUBLE),
                    (settings, value) -> settings.launchChargeFullIntervalMs = intervalOrDefault(value, DEFAULT_FULL_INTERVAL_MS),
                    settings -> settings.launchChargeFullIntervalMs)
            .documentation("Charge-pulse interval at full charge. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("LaunchChargeMinScale", Codec.DOUBLE),
                    (settings, value) -> settings.launchChargeMinScale = positiveOrDefault(value, DEFAULT_CHARGE_MIN_SCALE),
                    settings -> settings.launchChargeMinScale)
            .documentation("Whole-system scale for the first launch-charge pulse. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("LaunchChargeMaxScale", Codec.DOUBLE),
                    (settings, value) -> settings.launchChargeMaxScale = positiveOrDefault(value, DEFAULT_CHARGE_MAX_SCALE),
                    settings -> settings.launchChargeMaxScale)
            .documentation("Whole-system scale for a full-charge pulse. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Boolean>append(new KeyedCodec<>("LaunchCancelEnabled", Codec.BOOLEAN),
                    (settings, value) -> settings.launchCancelEnabled = value == null || value,
                    settings -> settings.launchCancelEnabled)
            .documentation("Whether a consumed but unapplied launch release emits a fizzle. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("LaunchCancelParticleSystem", Codec.STRING),
                    (settings, value) -> settings.launchCancelParticleSystem = stringOrDefault(value, DEFAULT_CANCEL_SYSTEM),
                    settings -> settings.launchCancelParticleSystem)
            .documentation("Particle system emitted for a canceled or rejected launch release. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("LaunchCancelScale", Codec.DOUBLE),
                    (settings, value) -> settings.launchCancelScale = positiveOrDefault(value, DEFAULT_CANCEL_SCALE),
                    settings -> settings.launchCancelScale)
            .documentation("Whole-system scale for the launch cancel effect. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Boolean>append(new KeyedCodec<>("LaunchReleaseEnabled", Codec.BOOLEAN),
                    (settings, value) -> settings.launchReleaseEnabled = value == null || value,
                    settings -> settings.launchReleaseEnabled)
            .documentation("Whether successful charged launches emit release particles. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("LaunchReleasePartialParticleSystem", Codec.STRING),
                    (settings, value) -> settings.launchReleasePartialParticleSystem = stringOrDefault(value, DEFAULT_PARTIAL_SYSTEM),
                    settings -> settings.launchReleasePartialParticleSystem)
            .documentation("Particle system for a partial charged launch. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("LaunchReleaseMidParticleSystem", Codec.STRING),
                    (settings, value) -> settings.launchReleaseMidParticleSystem = stringOrDefault(value, DEFAULT_MID_SYSTEM),
                    settings -> settings.launchReleaseMidParticleSystem)
            .documentation("Particle system for a mid charged launch. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("LaunchReleaseFullParticleSystem", Codec.STRING),
                    (settings, value) -> settings.launchReleaseFullParticleSystem = stringOrDefault(value, DEFAULT_FULL_SYSTEM),
                    settings -> settings.launchReleaseFullParticleSystem)
            .documentation("Particle system for a full charged launch. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("LaunchReleasePartialScale", Codec.DOUBLE),
                    (settings, value) -> settings.launchReleasePartialScale = positiveOrDefault(value, DEFAULT_PARTIAL_SCALE),
                    settings -> settings.launchReleasePartialScale)
            .documentation("Whole-system scale for a partial launch release. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("LaunchReleaseMidScale", Codec.DOUBLE),
                    (settings, value) -> settings.launchReleaseMidScale = positiveOrDefault(value, DEFAULT_MID_SCALE),
                    settings -> settings.launchReleaseMidScale)
            .documentation("Whole-system scale for a mid launch release. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("LaunchReleaseFullScale", Codec.DOUBLE),
                    (settings, value) -> settings.launchReleaseFullScale = positiveOrDefault(value, DEFAULT_FULL_SCALE),
                    settings -> settings.launchReleaseFullScale)
            .documentation("Whole-system scale for a full launch release. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("LaunchReleaseMidThreshold", Codec.DOUBLE),
                    (settings, value) -> settings.launchReleaseMidThreshold = clamp01(value, DEFAULT_MID_THRESHOLD),
                    settings -> settings.launchReleaseMidThreshold)
            .documentation("Launch-curve charge where the mid release effect begins. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("LaunchReleaseFullThreshold", Codec.DOUBLE),
                    (settings, value) -> settings.launchReleaseFullThreshold = clamp01(value, DEFAULT_FULL_THRESHOLD),
                    settings -> settings.launchReleaseFullThreshold)
            .documentation("Launch-curve charge where the full release effect begins. Inheritance: missing nested key inherits parent value.")
            .add()
            .build();

    private boolean enabled = true;
    private double groundOffsetY = DEFAULT_GROUND_OFFSET_Y;
    private double maxDurationSeconds = DEFAULT_MAX_DURATION_SECONDS;
    private boolean launchChargeEnabled = true;
    private String launchChargeParticleSystem = DEFAULT_CHARGE_SYSTEM;
    private double launchChargeEarlyIntervalMs = DEFAULT_EARLY_INTERVAL_MS;
    private double launchChargeFullIntervalMs = DEFAULT_FULL_INTERVAL_MS;
    private double launchChargeMinScale = DEFAULT_CHARGE_MIN_SCALE;
    private double launchChargeMaxScale = DEFAULT_CHARGE_MAX_SCALE;
    private boolean launchCancelEnabled = true;
    private String launchCancelParticleSystem = DEFAULT_CANCEL_SYSTEM;
    private double launchCancelScale = DEFAULT_CANCEL_SCALE;
    private boolean launchReleaseEnabled = true;
    private String launchReleasePartialParticleSystem = DEFAULT_PARTIAL_SYSTEM;
    private String launchReleaseMidParticleSystem = DEFAULT_MID_SYSTEM;
    private String launchReleaseFullParticleSystem = DEFAULT_FULL_SYSTEM;
    private double launchReleasePartialScale = DEFAULT_PARTIAL_SCALE;
    private double launchReleaseMidScale = DEFAULT_MID_SCALE;
    private double launchReleaseFullScale = DEFAULT_FULL_SCALE;
    private double launchReleaseMidThreshold = DEFAULT_MID_THRESHOLD;
    private double launchReleaseFullThreshold = DEFAULT_FULL_THRESHOLD;

    public void inheritMissingFrom(@Nonnull AvatarFlightVfxSettings parent, @Nullable Set<String> keys) {
        if (keys == null) return;
        if (!keys.contains("Enabled")) enabled = parent.enabled;
        if (!keys.contains("GroundOffsetY")) groundOffsetY = parent.groundOffsetY;
        if (!keys.contains("MaxDurationSeconds")) maxDurationSeconds = parent.maxDurationSeconds;
        if (!keys.contains("LaunchChargeEnabled")) launchChargeEnabled = parent.launchChargeEnabled;
        if (!keys.contains("LaunchChargeParticleSystem")) launchChargeParticleSystem = parent.launchChargeParticleSystem;
        if (!keys.contains("LaunchChargeEarlyIntervalMs")) launchChargeEarlyIntervalMs = parent.launchChargeEarlyIntervalMs;
        if (!keys.contains("LaunchChargeFullIntervalMs")) launchChargeFullIntervalMs = parent.launchChargeFullIntervalMs;
        if (!keys.contains("LaunchChargeMinScale")) launchChargeMinScale = parent.launchChargeMinScale;
        if (!keys.contains("LaunchChargeMaxScale")) launchChargeMaxScale = parent.launchChargeMaxScale;
        if (!keys.contains("LaunchCancelEnabled")) launchCancelEnabled = parent.launchCancelEnabled;
        if (!keys.contains("LaunchCancelParticleSystem")) launchCancelParticleSystem = parent.launchCancelParticleSystem;
        if (!keys.contains("LaunchCancelScale")) launchCancelScale = parent.launchCancelScale;
        if (!keys.contains("LaunchReleaseEnabled")) launchReleaseEnabled = parent.launchReleaseEnabled;
        if (!keys.contains("LaunchReleasePartialParticleSystem")) launchReleasePartialParticleSystem = parent.launchReleasePartialParticleSystem;
        if (!keys.contains("LaunchReleaseMidParticleSystem")) launchReleaseMidParticleSystem = parent.launchReleaseMidParticleSystem;
        if (!keys.contains("LaunchReleaseFullParticleSystem")) launchReleaseFullParticleSystem = parent.launchReleaseFullParticleSystem;
        if (!keys.contains("LaunchReleasePartialScale")) launchReleasePartialScale = parent.launchReleasePartialScale;
        if (!keys.contains("LaunchReleaseMidScale")) launchReleaseMidScale = parent.launchReleaseMidScale;
        if (!keys.contains("LaunchReleaseFullScale")) launchReleaseFullScale = parent.launchReleaseFullScale;
        if (!keys.contains("LaunchReleaseMidThreshold")) launchReleaseMidThreshold = parent.launchReleaseMidThreshold;
        if (!keys.contains("LaunchReleaseFullThreshold")) launchReleaseFullThreshold = parent.launchReleaseFullThreshold;
    }

    public boolean isEnabled() { return enabled; }
    public double getGroundOffsetY() { return finiteOrDefault(groundOffsetY, DEFAULT_GROUND_OFFSET_Y); }
    public float getMaxDurationSeconds() { return (float) positiveOrDefault(maxDurationSeconds, DEFAULT_MAX_DURATION_SECONDS); }
    public boolean isLaunchChargeEnabled() { return launchChargeEnabled; }
    public String getLaunchChargeParticleSystem() { return stringOrDefault(launchChargeParticleSystem, DEFAULT_CHARGE_SYSTEM); }
    public long getLaunchChargeEarlyIntervalMs() { return Math.round(intervalOrDefault(launchChargeEarlyIntervalMs, DEFAULT_EARLY_INTERVAL_MS)); }
    public long getLaunchChargeFullIntervalMs() {
        return Math.min(getLaunchChargeEarlyIntervalMs(), Math.round(intervalOrDefault(launchChargeFullIntervalMs, DEFAULT_FULL_INTERVAL_MS)));
    }
    public double getLaunchChargeMinScale() { return positiveOrDefault(launchChargeMinScale, DEFAULT_CHARGE_MIN_SCALE); }
    public double getLaunchChargeMaxScale() { return Math.max(getLaunchChargeMinScale(), positiveOrDefault(launchChargeMaxScale, DEFAULT_CHARGE_MAX_SCALE)); }
    public boolean isLaunchCancelEnabled() { return launchCancelEnabled; }
    public String getLaunchCancelParticleSystem() { return stringOrDefault(launchCancelParticleSystem, DEFAULT_CANCEL_SYSTEM); }
    public double getLaunchCancelScale() { return positiveOrDefault(launchCancelScale, DEFAULT_CANCEL_SCALE); }
    public boolean isLaunchReleaseEnabled() { return launchReleaseEnabled; }
    public String getLaunchReleasePartialParticleSystem() { return stringOrDefault(launchReleasePartialParticleSystem, DEFAULT_PARTIAL_SYSTEM); }
    public String getLaunchReleaseMidParticleSystem() { return stringOrDefault(launchReleaseMidParticleSystem, DEFAULT_MID_SYSTEM); }
    public String getLaunchReleaseFullParticleSystem() { return stringOrDefault(launchReleaseFullParticleSystem, DEFAULT_FULL_SYSTEM); }
    public double getLaunchReleasePartialScale() { return positiveOrDefault(launchReleasePartialScale, DEFAULT_PARTIAL_SCALE); }
    public double getLaunchReleaseMidScale() { return positiveOrDefault(launchReleaseMidScale, DEFAULT_MID_SCALE); }
    public double getLaunchReleaseFullScale() { return positiveOrDefault(launchReleaseFullScale, DEFAULT_FULL_SCALE); }
    public double getLaunchReleaseMidThreshold() { return clamp01(launchReleaseMidThreshold, DEFAULT_MID_THRESHOLD); }
    public double getLaunchReleaseFullThreshold() { return Math.max(getLaunchReleaseMidThreshold(), clamp01(launchReleaseFullThreshold, DEFAULT_FULL_THRESHOLD)); }

    private static String stringOrDefault(@Nullable String value, @Nonnull String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static double positiveOrDefault(@Nullable Double value, double fallback) {
        return value != null && Double.isFinite(value) && value > 0.0 ? value : fallback;
    }

    private static double finiteOrDefault(@Nullable Double value, double fallback) {
        return value != null && Double.isFinite(value) ? value : fallback;
    }

    private static double intervalOrDefault(@Nullable Double value, double fallback) {
        return Math.max(MIN_INTERVAL_MS, positiveOrDefault(value, fallback));
    }

    private static double clamp01(@Nullable Double value, double fallback) {
        double resolved = value != null && Double.isFinite(value) ? value : fallback;
        return Math.max(0.0, Math.min(1.0, resolved));
    }
}
