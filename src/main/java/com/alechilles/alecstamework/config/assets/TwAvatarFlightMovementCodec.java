package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Defines the movement section of an avatar-flight config. */
final class TwAvatarFlightMovementCodec {
    private TwAvatarFlightMovementCodec() {
    }

    @Nonnull
    static BuilderCodec<TwAvatarFlightConfig.MovementSettings> create() {
        return BuilderCodec.builder(
                        TwAvatarFlightConfig.MovementSettings.class,
                        TwAvatarFlightConfig.MovementSettings::new
                )
                .<Double>append(new KeyedCodec<>("GroundedMoveSpeed", Codec.DOUBLE),
                        (settings, value) -> settings.groundedMoveSpeed = positiveOrDefault(value, 8.0),
                        settings -> settings.groundedMoveSpeed)
                .documentation("Native grounded base movement speed while avatar flight is active. "
                        + "The default matches Hytale's vanilla Mount movement config. "
                        + "Inheritance: missing nested key inherits parent value.")
                .add()
                .<Double>append(new KeyedCodec<>("MaxForwardSpeed", Codec.DOUBLE),
                        (settings, value) -> settings.maxForwardSpeed = positiveOrDefault(value, 14.0),
                        settings -> settings.maxForwardSpeed)
                .documentation("Normal forward flight speed while W intent is active. Inheritance: missing nested key inherits parent value.")
                .add()
                .<Double>append(new KeyedCodec<>("MaxGlideSpeed", Codec.DOUBLE),
                        (settings, value) -> settings.maxGlideSpeed = positiveOrDefault(value, 15.0),
                        settings -> settings.maxGlideSpeed)
                .documentation("Maximum horizontal speed reachable without an active boost. Inheritance: missing nested key inherits parent value.")
                .add()
                .<Double>append(new KeyedCodec<>("NeutralGlideSpeed", Codec.DOUBLE),
                        (settings, value) -> settings.neutralGlideSpeed = nonNegativeOrDefault(value, 6.0),
                        settings -> settings.neutralGlideSpeed)
                .documentation("Reference neutral cruise speed used by glide metrics and climb eligibility; level forward glide decays below this without spending Vigour. Inheritance: missing nested key inherits parent value.")
                .add()
                .<Double>append(new KeyedCodec<>("NeutralGlideAcceleration", Codec.DOUBLE),
                        (settings, value) -> settings.neutralGlideAcceleration = nonNegativeOrDefault(value, 4.0),
                        settings -> settings.neutralGlideAcceleration)
                .documentation("Low-speed acceleration toward the GlideStartKickSpeed floor during level forward glide. Inheritance: missing nested key inherits parent value.")
                .add()
                .<Double>append(new KeyedCodec<>("NeutralGlideDeceleration", Codec.DOUBLE),
                        (settings, value) -> settings.neutralGlideDeceleration = nonNegativeOrDefault(value, 0.15),
                        settings -> settings.neutralGlideDeceleration)
                .documentation("Speed decay toward the GlideStartKickSpeed floor during level forward glide. Inheritance: missing nested key inherits parent value.")
                .add()
                .<Double>append(new KeyedCodec<>("GlideStartKickSpeed", Codec.DOUBLE),
                        (settings, value) -> settings.glideStartKickSpeed = nonNegativeOrDefault(value, 1.5),
                        settings -> settings.glideStartKickSpeed)
                .documentation("Small forward speed seed applied when forward glide starts from hover or stall. Inheritance: missing nested key inherits parent value.")
                .add()
                .<Double>append(new KeyedCodec<>("ForwardAcceleration", Codec.DOUBLE),
                        (settings, value) -> settings.forwardAcceleration = nonNegativeOrDefault(value, 18.0),
                        settings -> settings.forwardAcceleration)
                .documentation("Legacy forward acceleration setting; neutral level glide uses NeutralGlideAcceleration and NeutralGlideDeceleration. Inheritance: missing nested key inherits parent value.")
                .add()
                .<Double>append(new KeyedCodec<>("MaxBackwardSpeed", Codec.DOUBLE),
                        (settings, value) -> settings.maxBackwardSpeed = positiveOrDefault(value, 3.0),
                        settings -> settings.maxBackwardSpeed)
                .documentation("Maximum reverse hover speed. Inheritance: missing nested key inherits parent value.")
                .add()
                .<Double>append(new KeyedCodec<>("BackwardAcceleration", Codec.DOUBLE),
                        (settings, value) -> settings.backwardAcceleration = nonNegativeOrDefault(value, 8.0),
                        settings -> settings.backwardAcceleration)
                .documentation("Reverse acceleration once S is no longer braking forward speed. Inheritance: missing nested key inherits parent value.")
                .add()
                .<Double>append(new KeyedCodec<>("AirbrakeDeceleration", Codec.DOUBLE),
                        (settings, value) -> settings.airbrakeDeceleration = nonNegativeOrDefault(value, 18.0),
                        settings -> settings.airbrakeDeceleration)
                .documentation("Forward-speed loss per second while S is braking. Inheritance: missing nested key inherits parent value.")
                .add()
                .<Double>append(new KeyedCodec<>("HoverHorizontalDamping", Codec.DOUBLE),
                        (settings, value) -> settings.hoverHorizontalDamping = nonNegativeOrDefault(value, 10.0),
                        settings -> settings.hoverHorizontalDamping)
                .documentation("Horizontal speed damping while airborne with no forward/back intent. Inheritance: missing nested key inherits parent value.")
                .add()
                .<Double>append(new KeyedCodec<>("HoverVerticalDamping", Codec.DOUBLE),
                        (settings, value) -> settings.hoverVerticalDamping = nonNegativeOrDefault(value, 8.0),
                        settings -> settings.hoverVerticalDamping)
                .documentation("Vertical speed damping while hovering. Inheritance: missing nested key inherits parent value.")
                .add()
                .<Double>append(new KeyedCodec<>("GlideSinkSpeed", Codec.DOUBLE),
                        (settings, value) -> settings.glideSinkSpeed = nonNegativeOrDefault(value, 1.0),
                        settings -> settings.glideSinkSpeed)
                .documentation("Target downward speed for unpowered forward glide. Inheritance: missing nested key inherits parent value.")
                .add()
                .<Double>append(new KeyedCodec<>("GlideSinkAcceleration", Codec.DOUBLE),
                        (settings, value) -> settings.glideSinkAcceleration = nonNegativeOrDefault(value, 2.0),
                        settings -> settings.glideSinkAcceleration)
                .documentation("Rate at which unpowered forward glide approaches GlideSinkSpeed. Inheritance: missing nested key inherits parent value.")
                .add()
                .<Double>append(new KeyedCodec<>("StallSpeedThreshold", Codec.DOUBLE),
                        (settings, value) -> settings.stallSpeedThreshold = nonNegativeOrDefault(value, 8.0),
                        settings -> settings.stallSpeedThreshold)
                .documentation("Horizontal speed where low-speed glide begins blending toward StallSinkSpeed. Inheritance: missing nested key inherits parent value.")
                .add()
                .<Double>append(new KeyedCodec<>("StallSinkSpeed", Codec.DOUBLE),
                        (settings, value) -> settings.stallSinkSpeed = nonNegativeOrDefault(value, 5.0),
                        settings -> settings.stallSinkSpeed)
                .documentation("Target downward speed when forward glide has nearly stalled. Inheritance: missing nested key inherits parent value.")
                .add()
                .<Double>append(new KeyedCodec<>("DescendSpeed", Codec.DOUBLE),
                        (settings, value) -> settings.descendSpeed = positiveOrDefault(value, 7.0),
                        settings -> settings.descendSpeed)
                .documentation("Direct downward speed while crouch is held. Inheritance: missing nested key inherits parent value.")
                .add()
                .<Double>append(new KeyedCodec<>("MaxFallSpeed", Codec.DOUBLE),
                        (settings, value) -> settings.maxFallSpeed = positiveOrDefault(value, 14.0),
                        settings -> settings.maxFallSpeed)
                .documentation("Downward velocity clamp. Inheritance: missing nested key inherits parent value.")
                .add()
                .<Double>append(new KeyedCodec<>("PitchUpLiftScale", Codec.DOUBLE),
                        (settings, value) -> settings.pitchUpLiftScale = nonNegativeOrDefault(value, 5.0),
                        settings -> settings.pitchUpLiftScale)
                .documentation("Lift generated by pitching up while moving forward. Inheritance: missing nested key inherits parent value.")
                .add()
                .<Double>append(new KeyedCodec<>("PitchUpSpeedCost", Codec.DOUBLE),
                        (settings, value) -> settings.pitchUpSpeedCost = nonNegativeOrDefault(value, 3.0),
                        settings -> settings.pitchUpSpeedCost)
                .documentation("Forward speed spent by pitching up. Inheritance: missing nested key inherits parent value.")
                .add()
                .<Double>append(new KeyedCodec<>("PitchDownDiveScale", Codec.DOUBLE),
                        (settings, value) -> settings.pitchDownDiveScale = nonNegativeOrDefault(value, 5.0),
                        settings -> settings.pitchDownDiveScale)
                .documentation("Downward speed generated by pitching down. Inheritance: missing nested key inherits parent value.")
                .add()
                .<Double>append(new KeyedCodec<>("PitchDownSpeedGain", Codec.DOUBLE),
                        (settings, value) -> settings.pitchDownSpeedGain = nonNegativeOrDefault(value, 3.0),
                        settings -> settings.pitchDownSpeedGain)
                .documentation("Forward speed gained from pitching down. Inheritance: missing nested key inherits parent value.")
                .add()
                .build();
    }

    private static double positiveOrDefault(@Nullable Double value, double fallback) {
        return value != null && Double.isFinite(value) && value > 0.0 ? value : fallback;
    }

    private static double nonNegativeOrDefault(@Nullable Double value, double fallback) {
        return value != null && Double.isFinite(value) && value >= 0.0 ? value : fallback;
    }
}
