package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Configures one-shot transformed-model animations for accepted avatar-flight abilities.
 */
public final class AvatarFlightAbilityAnimationSettings {
    public static final double DEFAULT_UPWARD_BOOST_DURATION_MS = 650.0;
    public static final double DEFAULT_FORWARD_BOOST_DURATION_MS = 700.0;
    public static final double DEFAULT_AIRBRAKE_DURATION_MS = 500.0;
    private static final double MIN_DURATION_MS = 50.0;
    private static final double MAX_DURATION_MS = 5000.0;

    public static final BuilderCodec<AvatarFlightAbilityAnimationSettings> CODEC = BuilderCodec.builder(
            AvatarFlightAbilityAnimationSettings.class,
            AvatarFlightAbilityAnimationSettings::new
    )
            .<Boolean>append(new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (settings, value) -> settings.enabled = value == null || value,
                    settings -> settings.enabled)
            .documentation("Whether accepted avatar-flight abilities play configured one-shot animations. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("UpwardBoostAnimation", Codec.STRING),
                    (settings, value) -> settings.upwardBoostAnimation = blankOrTrim(value),
                    settings -> settings.upwardBoostAnimation)
            .documentation("Action-slot animation played after an accepted upward boost. Blank disables the cue. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("UpwardBoostDurationMs", Codec.DOUBLE),
                    (settings, value) -> settings.upwardBoostDurationMs = durationOrDefault(value, DEFAULT_UPWARD_BOOST_DURATION_MS),
                    settings -> settings.upwardBoostDurationMs)
            .documentation("Milliseconds that Action-slot suppression yields to an upward-boost clip. This does not scale the clip. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("ForwardBoostAnimation", Codec.STRING),
                    (settings, value) -> settings.forwardBoostAnimation = blankOrTrim(value),
                    settings -> settings.forwardBoostAnimation)
            .documentation("Action-slot animation played after an accepted forward boost. Blank disables the cue. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("ForwardBoostDurationMs", Codec.DOUBLE),
                    (settings, value) -> settings.forwardBoostDurationMs = durationOrDefault(value, DEFAULT_FORWARD_BOOST_DURATION_MS),
                    settings -> settings.forwardBoostDurationMs)
            .documentation("Milliseconds that Action-slot suppression yields to a forward-boost clip. This does not scale the clip. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("AirbrakeAnimation", Codec.STRING),
                    (settings, value) -> settings.airbrakeAnimation = blankOrTrim(value),
                    settings -> settings.airbrakeAnimation)
            .documentation("Action-slot animation played once when airbrake becomes active. Blank disables the cue. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("AirbrakeDurationMs", Codec.DOUBLE),
                    (settings, value) -> settings.airbrakeDurationMs = durationOrDefault(value, DEFAULT_AIRBRAKE_DURATION_MS),
                    settings -> settings.airbrakeDurationMs)
            .documentation("Milliseconds that Action-slot suppression yields to an airbrake clip. This does not scale the clip. Inheritance: missing nested key inherits parent value.")
            .add()
            .build();

    boolean enabled = true;
    String upwardBoostAnimation = "";
    double upwardBoostDurationMs = DEFAULT_UPWARD_BOOST_DURATION_MS;
    String forwardBoostAnimation = "";
    double forwardBoostDurationMs = DEFAULT_FORWARD_BOOST_DURATION_MS;
    String airbrakeAnimation = "";
    double airbrakeDurationMs = DEFAULT_AIRBRAKE_DURATION_MS;

    public void inheritMissingFrom(@Nonnull AvatarFlightAbilityAnimationSettings parent,
                                   @Nullable Set<String> keys) {
        if (keys == null) return;
        if (!keys.contains("Enabled")) enabled = parent.enabled;
        if (!keys.contains("UpwardBoostAnimation")) upwardBoostAnimation = parent.upwardBoostAnimation;
        if (!keys.contains("UpwardBoostDurationMs")) upwardBoostDurationMs = parent.upwardBoostDurationMs;
        if (!keys.contains("ForwardBoostAnimation")) forwardBoostAnimation = parent.forwardBoostAnimation;
        if (!keys.contains("ForwardBoostDurationMs")) forwardBoostDurationMs = parent.forwardBoostDurationMs;
        if (!keys.contains("AirbrakeAnimation")) airbrakeAnimation = parent.airbrakeAnimation;
        if (!keys.contains("AirbrakeDurationMs")) airbrakeDurationMs = parent.airbrakeDurationMs;
    }

    public boolean isEnabled() { return enabled; }
    public String getUpwardBoostAnimation() { return blankOrTrim(upwardBoostAnimation); }
    public long getUpwardBoostDurationMs() {
        return Math.round(durationOrDefault(upwardBoostDurationMs, DEFAULT_UPWARD_BOOST_DURATION_MS));
    }
    public String getForwardBoostAnimation() { return blankOrTrim(forwardBoostAnimation); }
    public long getForwardBoostDurationMs() {
        return Math.round(durationOrDefault(forwardBoostDurationMs, DEFAULT_FORWARD_BOOST_DURATION_MS));
    }
    public String getAirbrakeAnimation() { return blankOrTrim(airbrakeAnimation); }
    public long getAirbrakeDurationMs() {
        return Math.round(durationOrDefault(airbrakeDurationMs, DEFAULT_AIRBRAKE_DURATION_MS));
    }

    @Nonnull
    private static String blankOrTrim(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    private static double durationOrDefault(@Nullable Double value, double fallback) {
        double resolved = value != null && Double.isFinite(value) ? value : fallback;
        return Math.max(MIN_DURATION_MS, Math.min(MAX_DURATION_MS, resolved));
    }
}
