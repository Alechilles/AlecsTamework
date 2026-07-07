package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Pitch-load curve tuning for avatar flight.
 */
public final class AvatarFlightCurveSettings {
    private static final double DEFAULT_DIVE_LOAD_RAMP_SECONDS = 1.6;
    private static final double DEFAULT_DIVE_LOAD_DECAY_SECONDS = 0.6;
    private static final double DEFAULT_DIVE_PITCH_EXPONENT = 1.55;
    private static final double DEFAULT_CLIMB_LOAD_RAMP_SECONDS = 1.1;
    private static final double DEFAULT_CLIMB_LOAD_DECAY_SECONDS = 0.6;
    private static final double DEFAULT_CLIMB_PITCH_EXPONENT = 1.35;
    private static final double DEFAULT_CLIMB_SPEED_ELIGIBILITY_EXPONENT = 0.5;
    private static final double DEFAULT_BOOSTED_SPEED_DECAY = 2.0;

    public static final BuilderCodec<AvatarFlightCurveSettings> CODEC = BuilderCodec.builder(
            AvatarFlightCurveSettings.class,
            AvatarFlightCurveSettings::new
    )
            .<Double>append(new KeyedCodec<>("DiveLoadRampSeconds", Codec.DOUBLE),
                    (settings, value) -> settings.diveLoadRampSeconds =
                            positiveOrDefault(value, DEFAULT_DIVE_LOAD_RAMP_SECONDS),
                    settings -> settings.diveLoadRampSeconds)
            .documentation("Seconds for dive pitch load to ramp in. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("DiveLoadDecaySeconds", Codec.DOUBLE),
                    (settings, value) -> settings.diveLoadDecaySeconds =
                            positiveOrDefault(value, DEFAULT_DIVE_LOAD_DECAY_SECONDS),
                    settings -> settings.diveLoadDecaySeconds)
            .documentation("Seconds for dive pitch load to decay. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("DivePitchExponent", Codec.DOUBLE),
                    (settings, value) -> settings.divePitchExponent =
                            positiveOrDefault(value, DEFAULT_DIVE_PITCH_EXPONENT),
                    settings -> settings.divePitchExponent)
            .documentation("Exponent applied to normalized dive pitch. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("ClimbLoadRampSeconds", Codec.DOUBLE),
                    (settings, value) -> settings.climbLoadRampSeconds =
                            positiveOrDefault(value, DEFAULT_CLIMB_LOAD_RAMP_SECONDS),
                    settings -> settings.climbLoadRampSeconds)
            .documentation("Seconds for climb pitch load to ramp in. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("ClimbLoadDecaySeconds", Codec.DOUBLE),
                    (settings, value) -> settings.climbLoadDecaySeconds =
                            positiveOrDefault(value, DEFAULT_CLIMB_LOAD_DECAY_SECONDS),
                    settings -> settings.climbLoadDecaySeconds)
            .documentation("Seconds for climb pitch load to decay. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("ClimbPitchExponent", Codec.DOUBLE),
                    (settings, value) -> settings.climbPitchExponent =
                            positiveOrDefault(value, DEFAULT_CLIMB_PITCH_EXPONENT),
                    settings -> settings.climbPitchExponent)
            .documentation("Exponent applied to normalized climb pitch. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("ClimbSpeedEligibilityExponent", Codec.DOUBLE),
                    (settings, value) -> settings.climbSpeedEligibilityExponent =
                            nonNegativeOrDefault(value, DEFAULT_CLIMB_SPEED_ELIGIBILITY_EXPONENT),
                    settings -> settings.climbSpeedEligibilityExponent)
            .documentation("Exponent applied to speed eligibility for climb load. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("BoostedSpeedDecay", Codec.DOUBLE),
                    (settings, value) -> settings.boostedSpeedDecay =
                            nonNegativeOrDefault(value, DEFAULT_BOOSTED_SPEED_DECAY),
                    settings -> settings.boostedSpeedDecay)
            .documentation("Speed decay used after boosted flight. Inheritance: missing nested key inherits parent value.")
            .add()
            .build();

    private double diveLoadRampSeconds = DEFAULT_DIVE_LOAD_RAMP_SECONDS;
    private double diveLoadDecaySeconds = DEFAULT_DIVE_LOAD_DECAY_SECONDS;
    private double divePitchExponent = DEFAULT_DIVE_PITCH_EXPONENT;
    private double climbLoadRampSeconds = DEFAULT_CLIMB_LOAD_RAMP_SECONDS;
    private double climbLoadDecaySeconds = DEFAULT_CLIMB_LOAD_DECAY_SECONDS;
    private double climbPitchExponent = DEFAULT_CLIMB_PITCH_EXPONENT;
    private double climbSpeedEligibilityExponent = DEFAULT_CLIMB_SPEED_ELIGIBILITY_EXPONENT;
    private double boostedSpeedDecay = DEFAULT_BOOSTED_SPEED_DECAY;

    public void inheritMissingFrom(@Nonnull AvatarFlightCurveSettings parent, @Nullable Set<String> keys) {
        if (keys == null) {
            return;
        }
        if (!keys.contains("DiveLoadRampSeconds")) diveLoadRampSeconds = parent.diveLoadRampSeconds;
        if (!keys.contains("DiveLoadDecaySeconds")) diveLoadDecaySeconds = parent.diveLoadDecaySeconds;
        if (!keys.contains("DivePitchExponent")) divePitchExponent = parent.divePitchExponent;
        if (!keys.contains("ClimbLoadRampSeconds")) climbLoadRampSeconds = parent.climbLoadRampSeconds;
        if (!keys.contains("ClimbLoadDecaySeconds")) climbLoadDecaySeconds = parent.climbLoadDecaySeconds;
        if (!keys.contains("ClimbPitchExponent")) climbPitchExponent = parent.climbPitchExponent;
        if (!keys.contains("ClimbSpeedEligibilityExponent")) {
            climbSpeedEligibilityExponent = parent.climbSpeedEligibilityExponent;
        }
        if (!keys.contains("BoostedSpeedDecay")) boostedSpeedDecay = parent.boostedSpeedDecay;
    }

    public double getDiveLoadRampSeconds() {
        return positiveOrDefault(diveLoadRampSeconds, DEFAULT_DIVE_LOAD_RAMP_SECONDS);
    }

    public double getDiveLoadDecaySeconds() {
        return positiveOrDefault(diveLoadDecaySeconds, DEFAULT_DIVE_LOAD_DECAY_SECONDS);
    }

    public double getDivePitchExponent() {
        return positiveOrDefault(divePitchExponent, DEFAULT_DIVE_PITCH_EXPONENT);
    }

    public double getClimbLoadRampSeconds() {
        return positiveOrDefault(climbLoadRampSeconds, DEFAULT_CLIMB_LOAD_RAMP_SECONDS);
    }

    public double getClimbLoadDecaySeconds() {
        return positiveOrDefault(climbLoadDecaySeconds, DEFAULT_CLIMB_LOAD_DECAY_SECONDS);
    }

    public double getClimbPitchExponent() {
        return positiveOrDefault(climbPitchExponent, DEFAULT_CLIMB_PITCH_EXPONENT);
    }

    public double getClimbSpeedEligibilityExponent() {
        return nonNegativeOrDefault(climbSpeedEligibilityExponent, DEFAULT_CLIMB_SPEED_ELIGIBILITY_EXPONENT);
    }

    public double getBoostedSpeedDecay() {
        return nonNegativeOrDefault(boostedSpeedDecay, DEFAULT_BOOSTED_SPEED_DECAY);
    }

    private static double positiveOrDefault(@Nullable Double value, double fallback) {
        return value != null && Double.isFinite(value) && value > 0.0 ? value : fallback;
    }

    private static double nonNegativeOrDefault(@Nullable Double value, double fallback) {
        return value != null && Double.isFinite(value) && value >= 0.0 ? value : fallback;
    }
}
