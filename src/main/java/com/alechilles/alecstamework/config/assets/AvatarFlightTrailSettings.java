package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Configures interaction-authored model trails for avatar-flight movement events. */
public final class AvatarFlightTrailSettings {
    public static final double DEFAULT_FAST_GLIDE_START_SPEED_RATIO = 0.92;
    public static final double DEFAULT_FAST_GLIDE_STOP_SPEED_RATIO = 0.86;

    public static final BuilderCodec<AvatarFlightTrailSettings> CODEC = BuilderCodec.builder(
            AvatarFlightTrailSettings.class,
            AvatarFlightTrailSettings::new
    )
            .<Boolean>append(new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (settings, value) -> settings.enabled = value == null || value,
                    settings -> settings.enabled)
            .documentation("Whether avatar flight can trigger interaction-authored model trails. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("LaunchRootInteraction", Codec.STRING),
                    (settings, value) -> settings.launchRootInteraction = blankOrTrim(value),
                    settings -> settings.launchRootInteraction)
            .documentation("RootInteraction started after a successful charged launch. Blank disables this cue. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("FlapRootInteraction", Codec.STRING),
                    (settings, value) -> settings.flapRootInteraction = blankOrTrim(value),
                    settings -> settings.flapRootInteraction)
            .documentation("RootInteraction started after a successful upward flap. Blank disables this cue. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("BoostRootInteraction", Codec.STRING),
                    (settings, value) -> settings.boostRootInteraction = blankOrTrim(value),
                    settings -> settings.boostRootInteraction)
            .documentation("RootInteraction started after a successful forward boost. Blank disables this cue. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("FastGlideRootInteraction", Codec.STRING),
                    (settings, value) -> settings.fastGlideRootInteraction = blankOrTrim(value),
                    settings -> settings.fastGlideRootInteraction)
            .documentation("Long-running RootInteraction used while horizontal speed is near the configured maximum glide speed. Blank disables this cue. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("FastGlideStartSpeedRatio", Codec.DOUBLE),
                    (settings, value) -> settings.fastGlideStartSpeedRatio = nonNegativeOrDefault(
                            value, DEFAULT_FAST_GLIDE_START_SPEED_RATIO),
                    settings -> settings.fastGlideStartSpeedRatio)
            .documentation("Ratio of Movement.MaxGlideSpeed that starts the sustained fast-glide trail. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("FastGlideStopSpeedRatio", Codec.DOUBLE),
                    (settings, value) -> settings.fastGlideStopSpeedRatio = nonNegativeOrDefault(
                            value, DEFAULT_FAST_GLIDE_STOP_SPEED_RATIO),
                    settings -> settings.fastGlideStopSpeedRatio)
            .documentation("Lower ratio of Movement.MaxGlideSpeed that stops the sustained trail, providing hysteresis near the start threshold. Inheritance: missing nested key inherits parent value.")
            .add()
            .build();

    boolean enabled = true;
    String launchRootInteraction = "";
    String flapRootInteraction = "";
    String boostRootInteraction = "";
    String fastGlideRootInteraction = "";
    double fastGlideStartSpeedRatio = DEFAULT_FAST_GLIDE_START_SPEED_RATIO;
    double fastGlideStopSpeedRatio = DEFAULT_FAST_GLIDE_STOP_SPEED_RATIO;

    void inheritMissingFrom(@Nonnull AvatarFlightTrailSettings parent,
                            @Nonnull Set<String> explicitNestedKeys) {
        if (!explicitNestedKeys.contains("Enabled")) enabled = parent.enabled;
        if (!explicitNestedKeys.contains("LaunchRootInteraction")) {
            launchRootInteraction = parent.launchRootInteraction;
        }
        if (!explicitNestedKeys.contains("FlapRootInteraction")) {
            flapRootInteraction = parent.flapRootInteraction;
        }
        if (!explicitNestedKeys.contains("BoostRootInteraction")) {
            boostRootInteraction = parent.boostRootInteraction;
        }
        if (!explicitNestedKeys.contains("FastGlideRootInteraction")) {
            fastGlideRootInteraction = parent.fastGlideRootInteraction;
        }
        if (!explicitNestedKeys.contains("FastGlideStartSpeedRatio")) {
            fastGlideStartSpeedRatio = parent.fastGlideStartSpeedRatio;
        }
        if (!explicitNestedKeys.contains("FastGlideStopSpeedRatio")) {
            fastGlideStopSpeedRatio = parent.fastGlideStopSpeedRatio;
        }
    }

    public boolean isEnabled() { return enabled; }
    @Nonnull public String getLaunchRootInteraction() { return blankOrTrim(launchRootInteraction); }
    @Nonnull public String getFlapRootInteraction() { return blankOrTrim(flapRootInteraction); }
    @Nonnull public String getBoostRootInteraction() { return blankOrTrim(boostRootInteraction); }
    @Nonnull public String getFastGlideRootInteraction() { return blankOrTrim(fastGlideRootInteraction); }
    public double getFastGlideStartSpeedRatio() {
        return nonNegativeOrDefault(fastGlideStartSpeedRatio, DEFAULT_FAST_GLIDE_START_SPEED_RATIO);
    }
    public double getFastGlideStopSpeedRatio() {
        return Math.min(
                getFastGlideStartSpeedRatio(),
                nonNegativeOrDefault(fastGlideStopSpeedRatio, DEFAULT_FAST_GLIDE_STOP_SPEED_RATIO)
        );
    }

    @Nonnull
    private static String blankOrTrim(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    private static double nonNegativeOrDefault(@Nullable Double value, double fallback) {
        return value != null && Double.isFinite(value) && value >= 0.0 ? value : fallback;
    }
}
