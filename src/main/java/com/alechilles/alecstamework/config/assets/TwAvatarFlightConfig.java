package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Asset-backed tuning for transformed-player avatar flight.
 *
 * <p>Stored under {@code Server/Tamework/AvatarFlight}.
 */
public final class TwAvatarFlightConfig implements
        JsonAssetWithMap<String, DefaultAssetMap<String, TwAvatarFlightConfig>>,
        TwParentFallbackAsset<TwAvatarFlightConfig> {
    private static final String DEFAULT_MODEL_ID = "NordicDrake";
    private static final String DEFAULT_IDLE_ANIMATION = "FlyIdle";
    private static final String DEFAULT_FLIGHT_ANIMATION = "Fly";
    private static final String DEFAULT_FAST_FLIGHT_ANIMATION = "FlyFast";

    private static final BuilderCodec<ModelSettings> MODEL_CODEC = BuilderCodec.builder(
            ModelSettings.class,
            ModelSettings::new
    )
            .<Boolean>append(new KeyedCodec<>("ApplyModel", Codec.BOOLEAN),
                    (settings, value) -> settings.applyModel = value != null && value,
                    settings -> settings.applyModel)
            .documentation("Whether to replace the player's ModelComponent while avatar flight is active. Disabled by default because non-player models can crash the current client during movement. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("ModelId", Codec.STRING),
                    (settings, value) -> settings.modelId = stringOrDefault(value, DEFAULT_MODEL_ID),
                    settings -> settings.modelId)
            .documentation("Model used when ApplyModel is enabled. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("Scale", Codec.DOUBLE),
                    (settings, value) -> settings.scale = positiveOrDefault(value, 1.0),
                    settings -> settings.scale)
            .documentation("Requested model scale before model-asset min/max clamping. Inheritance: missing nested key inherits parent value.")
            .add()
            .build();

    private static final BuilderCodec<InputSettings> INPUT_CODEC = BuilderCodec.builder(
            InputSettings.class,
            InputSettings::new
    )
            .<Double>append(new KeyedCodec<>("IntentTimeoutMs", Codec.DOUBLE),
                    (settings, value) -> settings.intentTimeoutMs = nonNegativeOrDefault(value, 250.0),
                    settings -> settings.intentTimeoutMs)
            .documentation("Milliseconds before packet-derived movement intent decays to neutral. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("ForwardDeadzone", Codec.DOUBLE),
                    (settings, value) -> settings.forwardDeadzone = clamp01(value, 0.25),
                    settings -> settings.forwardDeadzone)
            .documentation("Absolute forward-axis threshold for W/S intent. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("StrafeDeadzone", Codec.DOUBLE),
                    (settings, value) -> settings.strafeDeadzone = clamp01(value, 0.25),
                    settings -> settings.strafeDeadzone)
            .documentation("Absolute strafe-axis threshold for future A/D tuning. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("AirborneJumpActivationDelayMs", Codec.DOUBLE),
                    (settings, value) -> settings.airborneJumpActivationDelayMs = nonNegativeOrDefault(value, 250.0),
                    settings -> settings.airborneJumpActivationDelayMs)
            .documentation("Milliseconds after leaving ground before a fresh jump press can explicitly enter avatar flight. This prevents normal jump presses from leaking into flight activation. Inheritance: missing nested key inherits parent value.")
            .add()
            .build();

    private static final BuilderCodec<MovementSettings> MOVEMENT_CODEC = BuilderCodec.builder(
            MovementSettings.class,
            MovementSettings::new
    )
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

    private static final BuilderCodec<JumpSettings> JUMP_CODEC = BuilderCodec.builder(
            JumpSettings.class,
            JumpSettings::new
    )
            .<Double>append(new KeyedCodec<>("UpwardImpulse", Codec.DOUBLE),
                    (settings, value) -> settings.upwardImpulse = nonNegativeOrDefault(value, 7.0),
                    settings -> settings.upwardImpulse)
            .documentation("Vertical impulse applied by jump/flap. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("CooldownSeconds", Codec.DOUBLE),
                    (settings, value) -> settings.cooldownSeconds = positiveOrDefault(value, 0.75),
                    settings -> settings.cooldownSeconds)
            .documentation("Seconds between jump/flap impulses, including held jump repeats. Inheritance: missing nested key inherits parent value.")
            .add()
            .build();

    private static final BuilderCodec<BoostSettings> BOOST_CODEC = BuilderCodec.builder(
            BoostSettings.class,
            BoostSettings::new
    )
            .<Double>append(new KeyedCodec<>("ForwardImpulse", Codec.DOUBLE),
                    (settings, value) -> settings.forwardImpulse = nonNegativeOrDefault(value, 7.0),
                    settings -> settings.forwardImpulse)
            .documentation("Forward velocity impulse applied by sprint/shift. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("CooldownSeconds", Codec.DOUBLE),
                    (settings, value) -> settings.cooldownSeconds = positiveOrDefault(value, 1.0),
                    settings -> settings.cooldownSeconds)
            .documentation("Seconds between sprint/shift boosts. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("DurationSeconds", Codec.DOUBLE),
                    (settings, value) -> settings.durationSeconds = positiveOrDefault(value, 0.45),
                    settings -> settings.durationSeconds)
            .documentation("Seconds a detected sprint/shift pulse remains an active forward boost. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Boolean>append(new KeyedCodec<>("Directional", Codec.BOOLEAN),
                    (settings, value) -> settings.directional = value == null || value,
                    settings -> settings.directional)
            .documentation("Whether boost impulse follows look pitch instead of applying only forward speed. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("UpwardPitchLiftMultiplier", Codec.DOUBLE),
                    (settings, value) -> settings.upwardPitchLiftMultiplier = nonNegativeOrDefault(value, 0.45),
                    settings -> settings.upwardPitchLiftMultiplier)
            .documentation("Multiplier applied to upward directional boost lift before cap. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("UpwardPitchLiftCap", Codec.DOUBLE),
                    (settings, value) -> settings.upwardPitchLiftCap = nonNegativeOrDefault(value, 3.0),
                    settings -> settings.upwardPitchLiftCap)
            .documentation("Maximum upward vertical impulse from directional boost. Inheritance: missing nested key inherits parent value.")
            .add()
            .build();

    private static final BuilderCodec<VigourSettings> VIGOUR_CODEC = BuilderCodec.builder(
            VigourSettings.class,
            VigourSettings::new
    )
            .<Boolean>append(new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (settings, value) -> settings.enabled = value == null || value,
                    settings -> settings.enabled)
            .documentation("Whether avatar flight vigour spending and recharge are enabled. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("MaxCharges", Codec.DOUBLE),
                    (settings, value) -> settings.maxCharges = nonNegativeOrDefault(value, 6.0),
                    settings -> settings.maxCharges)
            .documentation("Maximum vigour charges available for avatar flight actions. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("UpwardFlapCost", Codec.DOUBLE),
                    (settings, value) -> settings.upwardFlapCost = nonNegativeOrDefault(value, 1.0),
                    settings -> settings.upwardFlapCost)
            .documentation("Vigour charges spent by an upward flap. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("ForwardBoostCost", Codec.DOUBLE),
                    (settings, value) -> settings.forwardBoostCost = nonNegativeOrDefault(value, 1.0),
                    settings -> settings.forwardBoostCost)
            .documentation("Vigour charges spent by a forward boost. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("GroundedRechargeSecondsPerCharge", Codec.DOUBLE),
                    (settings, value) -> settings.groundedRechargeSecondsPerCharge = positiveOrDefault(value, 4.0),
                    settings -> settings.groundedRechargeSecondsPerCharge)
            .documentation("Grounded recharge seconds required for each vigour charge. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("FastFlightRechargeSecondsPerCharge", Codec.DOUBLE),
                    (settings, value) -> settings.fastFlightRechargeSecondsPerCharge = positiveOrDefault(value, 8.0),
                    settings -> settings.fastFlightRechargeSecondsPerCharge)
            .documentation("Fast-flight recharge seconds required for each vigour charge while above the configured speed ratio. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("FastFlightRechargeSpeedRatio", Codec.DOUBLE),
                    (settings, value) -> settings.fastFlightRechargeSpeedRatio = clamp01(value, 0.80),
                    settings -> settings.fastFlightRechargeSpeedRatio)
            .documentation("Normalized sustainable glide-speed ratio that activates the fast-flight animation and airborne vigour recharge. Clamped to 0..1. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("RechargeDelayAfterSpendSeconds", Codec.DOUBLE),
                    (settings, value) -> settings.rechargeDelayAfterSpendSeconds = nonNegativeOrDefault(value, 0.75),
                    settings -> settings.rechargeDelayAfterSpendSeconds)
            .documentation("Recharge delay after spending vigour. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Boolean>append(new KeyedCodec<>("HudEnabled", Codec.BOOLEAN),
                    (settings, value) -> settings.hudEnabled = value == null || value,
                    settings -> settings.hudEnabled)
            .documentation("Whether avatar flight sends vigour HUD updates. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("HudResendIntervalMs", Codec.DOUBLE),
                    (settings, value) -> settings.hudResendIntervalMs = positiveOrDefault(value, 100.0),
                    settings -> settings.hudResendIntervalMs)
            .documentation("Minimum milliseconds between repeated vigour HUD updates. Inheritance: missing nested key inherits parent value.")
            .add()
            .build();

    private static final BuilderCodec<AnimationSettings> ANIMATION_CODEC = BuilderCodec.builder(
            AnimationSettings.class,
            AnimationSettings::new
    )
            .<String>append(new KeyedCodec<>("IdleAnimation", Codec.STRING),
                    (settings, value) -> settings.idleAnimation = stringOrDefault(value, DEFAULT_IDLE_ANIMATION),
                    settings -> settings.idleAnimation)
            .documentation("Movement-slot animation used while hovering or horizontally idle. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("FlightAnimation", Codec.STRING),
                    (settings, value) -> settings.flightAnimation = stringOrDefault(value, DEFAULT_FLIGHT_ANIMATION),
                    settings -> settings.flightAnimation)
            .documentation("Movement-slot animation used during normal avatar flight. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("FastFlightAnimation", Codec.STRING),
                    (settings, value) -> settings.fastFlightAnimation = stringOrDefault(value, DEFAULT_FAST_FLIGHT_ANIMATION),
                    settings -> settings.fastFlightAnimation)
            .documentation("Movement-slot animation used while the forward boost/fast-flight state is active. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("ResendIntervalMs", Codec.DOUBLE),
                    (settings, value) -> settings.resendIntervalMs = positiveOrDefault(value, 250.0),
                    settings -> settings.resendIntervalMs)
            .documentation("Minimum milliseconds between forced movement-animation packets while the same animation remains active. Set to 0 to send only when the animation changes, which preserves model-authored animation sound timing. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Boolean>append(new KeyedCodec<>("SuppressNonMovementAnimations", Codec.BOOLEAN),
                    (settings, value) -> settings.suppressNonMovementAnimations = value == null || value,
                    settings -> settings.suppressNonMovementAnimations)
            .documentation("Whether avatar flight periodically clears non-movement player animation slots that are authored for the player rig. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Boolean>append(new KeyedCodec<>("SuppressActionAnimation", Codec.BOOLEAN),
                    (settings, value) -> settings.suppressActionAnimation = value == null || value,
                    settings -> settings.suppressActionAnimation)
            .documentation("Whether avatar flight clears the Action animation slot, commonly used by item/combat animations. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Boolean>append(new KeyedCodec<>("SuppressStatusAnimation", Codec.BOOLEAN),
                    (settings, value) -> settings.suppressStatusAnimation = value == null || value,
                    settings -> settings.suppressStatusAnimation)
            .documentation("Whether avatar flight clears the Status animation slot while transformed. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Boolean>append(new KeyedCodec<>("SuppressEmoteAnimation", Codec.BOOLEAN),
                    (settings, value) -> settings.suppressEmoteAnimation = value == null || value,
                    settings -> settings.suppressEmoteAnimation)
            .documentation("Whether avatar flight clears player emotes while transformed. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Boolean>append(new KeyedCodec<>("SuppressFaceAnimation", Codec.BOOLEAN),
                    (settings, value) -> settings.suppressFaceAnimation = value != null && value,
                    settings -> settings.suppressFaceAnimation)
            .documentation("Whether avatar flight clears the Face animation slot while transformed. Disabled by default. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("SuppressionIntervalMs", Codec.DOUBLE),
                    (settings, value) -> settings.suppressionIntervalMs = positiveOrDefault(value, 100.0),
                    settings -> settings.suppressionIntervalMs)
            .documentation("Minimum milliseconds between non-movement animation suppression packets. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Boolean>append(new KeyedCodec<>("PoseAnimationsEnabled", Codec.BOOLEAN),
                    (settings, value) -> settings.poseAnimationsEnabled = value != null && value,
                    settings -> settings.poseAnimationsEnabled)
            .documentation("Whether avatar flight drives optional self-visible pitch/bank pose animations through non-movement animation slots. Disabled by default. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("PitchPoseSlot", Codec.STRING),
                    (settings, value) -> settings.pitchPoseSlot = stringOrDefault(value, "Status"),
                    settings -> settings.pitchPoseSlot)
            .documentation("Animation slot used for pitch-up/down pose animations. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("RollPoseSlot", Codec.STRING),
                    (settings, value) -> settings.rollPoseSlot = stringOrDefault(value, "Emote"),
                    settings -> settings.rollPoseSlot)
            .documentation("Animation slot used for bank-left/right pose animations. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("PitchUpPoseAnimation", Codec.STRING),
                    (settings, value) -> settings.pitchUpPoseAnimation = blankOrTrim(value),
                    settings -> settings.pitchUpPoseAnimation)
            .documentation("Optional animation id played on PitchPoseSlot when look pitch is above the pitch threshold. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("PitchDownPoseAnimation", Codec.STRING),
                    (settings, value) -> settings.pitchDownPoseAnimation = blankOrTrim(value),
                    settings -> settings.pitchDownPoseAnimation)
            .documentation("Optional animation id played on PitchPoseSlot when look pitch is below the negative pitch threshold. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("BankLeftPoseAnimation", Codec.STRING),
                    (settings, value) -> settings.bankLeftPoseAnimation = blankOrTrim(value),
                    settings -> settings.bankLeftPoseAnimation)
            .documentation("Optional animation id played on RollPoseSlot while the controller requests a left bank. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("BankRightPoseAnimation", Codec.STRING),
                    (settings, value) -> settings.bankRightPoseAnimation = blankOrTrim(value),
                    settings -> settings.bankRightPoseAnimation)
            .documentation("Optional animation id played on RollPoseSlot while the controller requests a right bank. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("PitchUpBankLeftPoseAnimation", Codec.STRING),
                    (settings, value) -> settings.pitchUpBankLeftPoseAnimation = blankOrTrim(value),
                    settings -> settings.pitchUpBankLeftPoseAnimation)
            .documentation("Optional single-slot pose animation used when pitch-up and left-bank are both active. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("PitchUpBankRightPoseAnimation", Codec.STRING),
                    (settings, value) -> settings.pitchUpBankRightPoseAnimation = blankOrTrim(value),
                    settings -> settings.pitchUpBankRightPoseAnimation)
            .documentation("Optional single-slot pose animation used when pitch-up and right-bank are both active. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("PitchDownBankLeftPoseAnimation", Codec.STRING),
                    (settings, value) -> settings.pitchDownBankLeftPoseAnimation = blankOrTrim(value),
                    settings -> settings.pitchDownBankLeftPoseAnimation)
            .documentation("Optional single-slot pose animation used when pitch-down and left-bank are both active. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("PitchDownBankRightPoseAnimation", Codec.STRING),
                    (settings, value) -> settings.pitchDownBankRightPoseAnimation = blankOrTrim(value),
                    settings -> settings.pitchDownBankRightPoseAnimation)
            .documentation("Optional single-slot pose animation used when pitch-down and right-bank are both active. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("PitchPoseThresholdDegrees", Codec.DOUBLE),
                    (settings, value) -> settings.pitchPoseThresholdDegrees = positiveOrDefault(value, 8.0),
                    settings -> settings.pitchPoseThresholdDegrees)
            .documentation("Absolute look-pitch degrees required before pitch pose animation changes from neutral. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("RollPoseThresholdDegrees", Codec.DOUBLE),
                    (settings, value) -> settings.rollPoseThresholdDegrees = positiveOrDefault(value, 5.0),
                    settings -> settings.rollPoseThresholdDegrees)
            .documentation("Absolute bank degrees required before roll pose animation changes from neutral. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("PoseResendIntervalMs", Codec.DOUBLE),
                    (settings, value) -> settings.poseResendIntervalMs = positiveOrDefault(value, 250.0),
                    settings -> settings.poseResendIntervalMs)
            .documentation("Minimum milliseconds between forced pose-animation packets while the same pose remains active. Inheritance: missing nested key inherits parent value.")
            .add()
            .build();

    private static final BuilderCodec<RiderVisualSettings> RIDER_VISUAL_CODEC = BuilderCodec.builder(
            RiderVisualSettings.class,
            RiderVisualSettings::new
    )
            .<Boolean>append(new KeyedCodec<>("HideOwnerEquipment", Codec.BOOLEAN),
                    (settings, value) -> settings.hideOwnerEquipment = value != null && value,
                    settings -> settings.hideOwnerEquipment)
            .documentation("Whether avatar flight sends equipment packets that hide the transformed player's equipped visuals. Disabled by default because local-player equipment packets can crash the current client. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Boolean>append(new KeyedCodec<>("HideOwnerArmor", Codec.BOOLEAN),
                    (settings, value) -> settings.hideOwnerArmor = value != null && value,
                    settings -> settings.hideOwnerArmor)
            .documentation("Whether hidden owner equipment also blanks armor slots. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Boolean>append(new KeyedCodec<>("HideOwnerHands", Codec.BOOLEAN),
                    (settings, value) -> settings.hideOwnerHands = value != null && value,
                    settings -> settings.hideOwnerHands)
            .documentation("Whether hidden owner equipment blanks right-hand and left-hand item visuals. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Boolean>append(new KeyedCodec<>("ShowRider", Codec.BOOLEAN),
                    (settings, value) -> settings.showRider = value != null && value,
                    settings -> settings.showRider)
            .documentation("Whether avatar flight spawns a visual-only copy of the player's saved model as a rider. Disabled by default while the native mounted visual-rider path is unstable. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("SeatOffsetX", Codec.DOUBLE),
                    (settings, value) -> settings.seatOffsetX = finiteOrDefault(value, 0.0),
                    settings -> settings.seatOffsetX)
            .documentation("Fake rider attachment offset X relative to the transformed player entity. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("SeatOffsetY", Codec.DOUBLE),
                    (settings, value) -> settings.seatOffsetY = finiteOrDefault(value, 1.35),
                    settings -> settings.seatOffsetY)
            .documentation("Fake rider attachment offset Y relative to the transformed player entity. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("SeatOffsetZ", Codec.DOUBLE),
                    (settings, value) -> settings.seatOffsetZ = finiteOrDefault(value, -0.25),
                    settings -> settings.seatOffsetZ)
            .documentation("Fake rider attachment offset Z relative to the transformed player entity. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("EquipmentResendIntervalMs", Codec.DOUBLE),
                    (settings, value) -> settings.equipmentResendIntervalMs = positiveOrDefault(value, 250.0),
                    settings -> settings.equipmentResendIntervalMs)
            .documentation("Minimum milliseconds between repeated fake-rider equipment packets when the signature is unchanged. Inheritance: missing nested key inherits parent value.")
            .add()
            .build();

    private static final BuilderCodec<DebugSettings> DEBUG_CODEC = BuilderCodec.builder(
            DebugSettings.class,
            DebugSettings::new
    )
            .<Boolean>append(new KeyedCodec<>("LogControllerTicks", Codec.BOOLEAN),
                    (settings, value) -> settings.logControllerTicks = value != null && value,
                    settings -> settings.logControllerTicks)
            .documentation("Verbose throttled controller diagnostics. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Boolean>append(new KeyedCodec<>("LogInputTransitions", Codec.BOOLEAN),
                    (settings, value) -> settings.logInputTransitions = value != null && value,
                    settings -> settings.logInputTransitions)
            .documentation("Logs meaningful input state transitions. Inheritance: missing nested key inherits parent value.")
            .add()
            .build();

    public static final AssetBuilderCodec<String, TwAvatarFlightConfig> CODEC = AssetBuilderCodec.builder(
            TwAvatarFlightConfig.class,
            TwAvatarFlightConfig::new,
            Codec.STRING,
            (asset, id) -> asset.id = id,
            asset -> asset.id,
            (asset, data) -> asset.data = data,
            asset -> asset.data
    )
            .documentation("Transformed-player avatar flight tuning.")
            .<Boolean>append(new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (asset, value) -> asset.enabled = value == null || value,
                    asset -> asset.enabled)
            .documentation("Enables this avatar-flight profile.")
            .add()
            .<Integer>append(new KeyedCodec<>("Priority", Codec.INTEGER),
                    (asset, value) -> asset.priority = value == null ? 0 : value,
                    asset -> asset.priority)
            .documentation("Priority used when no explicit config id is requested; higher values win.")
            .add()
            .<ModelSettings>append(new KeyedCodec<>("Model", MODEL_CODEC),
                    (asset, value) -> asset.model = value == null ? new ModelSettings() : value,
                    asset -> asset.model)
            .documentation("Avatar model settings. Inheritance: omitted section inherits; explicit nested keys override missing nested keys.")
            .add()
            .<InputSettings>append(new KeyedCodec<>("Input", INPUT_CODEC),
                    (asset, value) -> asset.input = value == null ? new InputSettings() : value,
                    asset -> asset.input)
            .documentation("Packet-derived input interpretation. Inheritance: omitted section inherits; explicit nested keys override missing nested keys.")
            .add()
            .<MovementSettings>append(new KeyedCodec<>("Movement", MOVEMENT_CODEC),
                    (asset, value) -> asset.movement = value == null ? new MovementSettings() : value,
                    asset -> asset.movement)
            .documentation("Avatar flight movement values. Inheritance: omitted section inherits; explicit nested keys override missing nested keys.")
            .add()
            .<AvatarFlightCurveSettings>append(new KeyedCodec<>("Curve", AvatarFlightCurveSettings.CODEC),
                    (asset, value) -> asset.curve = value == null ? new AvatarFlightCurveSettings() : value,
                    asset -> asset.curve)
            .documentation("Avatar flight pitch-load curve values. Inheritance: omitted section inherits; explicit nested keys override missing nested keys.")
            .add()
            .<JumpSettings>append(new KeyedCodec<>("Jump", JUMP_CODEC),
                    (asset, value) -> asset.jump = value == null ? new JumpSettings() : value,
                    asset -> asset.jump)
            .documentation("Cooldown-gated upward jump/flap values. Inheritance: omitted section inherits; explicit nested keys override missing nested keys.")
            .add()
            .<BoostSettings>append(new KeyedCodec<>("Boost", BOOST_CODEC),
                    (asset, value) -> asset.boost = value == null ? new BoostSettings() : value,
                    asset -> asset.boost)
            .documentation("Sprint/shift forward boost values. Inheritance: omitted section inherits; explicit nested keys override missing nested keys.")
            .add()
            .<AvatarFlightLaunchSettings>append(new KeyedCodec<>("Launch", AvatarFlightLaunchSettings.CODEC),
                    (asset, value) -> asset.launch = value == null ? new AvatarFlightLaunchSettings() : value,
                    asset -> asset.launch)
            .documentation("Charged avatar launch input and impulse values. Inheritance: omitted section inherits; explicit nested keys override missing nested keys.")
            .add()
            .<AvatarFlightVfxSettings>append(new KeyedCodec<>("Vfx", AvatarFlightVfxSettings.CODEC),
                    (asset, value) -> asset.vfx = value == null ? new AvatarFlightVfxSettings() : value,
                    asset -> asset.vfx)
            .documentation("Avatar-flight particle presentation. Inheritance: omitted section inherits; explicit nested keys override missing nested keys.")
            .add()
            .<AvatarFlightAudioSettings>append(new KeyedCodec<>("Audio", AvatarFlightAudioSettings.CODEC),
                    (asset, value) -> asset.audio = value == null ? new AvatarFlightAudioSettings() : value,
                    asset -> asset.audio)
            .documentation("Avatar-flight launch audio presentation. Inheritance: omitted section inherits; explicit nested keys override missing nested keys.")
            .add()
            .<AvatarFlightTrailSettings>append(new KeyedCodec<>("Trails", AvatarFlightTrailSettings.CODEC),
                    (asset, value) -> asset.trails = value == null ? new AvatarFlightTrailSettings() : value,
                    asset -> asset.trails)
            .documentation("Interaction-authored avatar-flight model trails. Inheritance: omitted section inherits; explicit nested keys override missing nested keys.")
            .add()
            .<VigourSettings>append(new KeyedCodec<>("Vigour", VIGOUR_CODEC),
                    (asset, value) -> asset.vigour = value == null ? new VigourSettings() : value,
                    asset -> asset.vigour)
            .documentation("Vigour charge costs, recharge rates, and HUD values. Inheritance: omitted section inherits; explicit nested keys override missing nested keys.")
            .add()
            .<AnimationSettings>append(new KeyedCodec<>("Animation", ANIMATION_CODEC),
                    (asset, value) -> asset.animation = value == null ? new AnimationSettings() : value,
                    asset -> asset.animation)
            .documentation("Transformed-player movement animation names. Inheritance: omitted section inherits; explicit nested keys override missing nested keys.")
            .add()
            .<AvatarFlightAbilityAnimationSettings>append(new KeyedCodec<>("AbilityAnimation", AvatarFlightAbilityAnimationSettings.CODEC),
                    (asset, value) -> asset.abilityAnimation = value == null ? new AvatarFlightAbilityAnimationSettings() : value,
                    asset -> asset.abilityAnimation)
            .documentation("One-shot transformed-model ability animations. Inheritance: omitted section inherits; explicit nested keys override missing nested keys.")
            .add()
            .<RiderVisualSettings>append(new KeyedCodec<>("RiderVisual", RIDER_VISUAL_CODEC),
                    (asset, value) -> asset.riderVisual = value == null ? new RiderVisualSettings() : value,
                    asset -> asset.riderVisual)
            .documentation("Avatar-flight rider visual and transformed-owner equipment visibility settings. Inheritance: omitted section inherits; explicit nested keys override missing nested keys.")
            .add()
            .<DebugSettings>append(new KeyedCodec<>("Debug", DEBUG_CODEC),
                    (asset, value) -> asset.debug = value == null ? new DebugSettings() : value,
                    asset -> asset.debug)
            .documentation("Avatar-flight diagnostics. Inheritance: omitted section inherits; explicit nested keys override missing nested keys.")
            .add()
            .build();

    private AssetExtraInfo.Data data;
    private String id;
    boolean enabled = true;
    int priority;
    ModelSettings model = new ModelSettings();
    InputSettings input = new InputSettings();
    MovementSettings movement = new MovementSettings();
    AvatarFlightCurveSettings curve = new AvatarFlightCurveSettings();
    JumpSettings jump = new JumpSettings();
    BoostSettings boost = new BoostSettings();
    AvatarFlightLaunchSettings launch = new AvatarFlightLaunchSettings();
    AvatarFlightVfxSettings vfx = new AvatarFlightVfxSettings();
    AvatarFlightAudioSettings audio = new AvatarFlightAudioSettings();
    AvatarFlightTrailSettings trails = new AvatarFlightTrailSettings();
    VigourSettings vigour = new VigourSettings();
    AnimationSettings animation = new AnimationSettings();
    AvatarFlightAbilityAnimationSettings abilityAnimation = new AvatarFlightAbilityAnimationSettings();
    RiderVisualSettings riderVisual = new RiderVisualSettings();
    DebugSettings debug = new DebugSettings();

    protected TwAvatarFlightConfig() {
    }

    @Nullable
    public static AssetStore<String, TwAvatarFlightConfig, DefaultAssetMap<String, TwAvatarFlightConfig>> getAssetStore() {
        return TwAvatarFlightConfigRegistry.getAssetStore();
    }

    @Nullable
    public static DefaultAssetMap<String, TwAvatarFlightConfig> getAssetMap() {
        return TwAvatarFlightConfigRegistry.getAssetMap();
    }

    public static void clearCache() {
        TwAvatarFlightConfigRegistry.clearCache();
    }

    @Nonnull
    public static TwAvatarFlightConfig resolveActive() {
        return TwAvatarFlightConfigRegistry.resolveActive();
    }

    @Nonnull
    public static TwAvatarFlightConfig resolve(@Nullable String configId) {
        return TwAvatarFlightConfigRegistry.resolve(configId);
    }

    @Nonnull
    public static TwAvatarFlightConfig defaultConfig() {
        return new TwAvatarFlightConfig();
    }

    @Override
    @Nullable
    public String getParentIdForFallback() {
        if (data == null || data.getParentKey() == null) {
            return null;
        }
        String parentId = data.getParentKey().toString();
        return parentId == null || parentId.isBlank() ? null : parentId;
    }

    @Override
    public void inheritMissingTopLevelFrom(@Nonnull TwAvatarFlightConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys) {
        inheritMissingTopLevelFrom(parent, explicitTopLevelKeys, null);
    }

    @Override
    public void inheritMissingTopLevelFrom(@Nonnull TwAvatarFlightConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys,
                                           @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        TwAvatarFlightConfigInheritance.inheritMissingFrom(
                this,
                parent,
                explicitTopLevelKeys,
                explicitNestedKeysByTopLevel
        );
    }

    public String getId() { return id; }
    public boolean isEnabled() { return enabled; }
    public int getPriority() { return priority; }
    public ModelSettings getModel() { return model == null ? new ModelSettings() : model; }
    public InputSettings getInput() { return input == null ? new InputSettings() : input; }
    public MovementSettings getMovement() { return movement == null ? new MovementSettings() : movement; }
    public AvatarFlightCurveSettings getCurve() { return curve == null ? new AvatarFlightCurveSettings() : curve; }
    public JumpSettings getJump() { return jump == null ? new JumpSettings() : jump; }
    public BoostSettings getBoost() { return boost == null ? new BoostSettings() : boost; }
    public AvatarFlightLaunchSettings getLaunch() { return launch == null ? new AvatarFlightLaunchSettings() : launch; }
    public AvatarFlightVfxSettings getVfx() { return vfx == null ? new AvatarFlightVfxSettings() : vfx; }
    public AvatarFlightAudioSettings getAudio() { return audio == null ? new AvatarFlightAudioSettings() : audio; }
    public AvatarFlightTrailSettings getTrails() {
        return trails == null ? new AvatarFlightTrailSettings() : trails;
    }
    public VigourSettings getVigour() { return vigour == null ? new VigourSettings() : vigour; }
    public AnimationSettings getAnimation() { return animation == null ? new AnimationSettings() : animation; }
    public AvatarFlightAbilityAnimationSettings getAbilityAnimation() {
        return abilityAnimation == null ? new AvatarFlightAbilityAnimationSettings() : abilityAnimation;
    }
    public RiderVisualSettings getRiderVisual() { return riderVisual == null ? new RiderVisualSettings() : riderVisual; }
    public DebugSettings getDebug() { return debug == null ? new DebugSettings() : debug; }

    private static String stringOrDefault(@Nullable String value, @Nonnull String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    @Nonnull
    private static String blankOrTrim(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    private static double positiveOrDefault(@Nullable Double value, double fallback) {
        return value != null && Double.isFinite(value) && value > 0.0 ? value : fallback;
    }

    private static double nonNegativeOrDefault(@Nullable Double value, double fallback) {
        return value != null && Double.isFinite(value) && value >= 0.0 ? value : fallback;
    }

    private static double finiteOrDefault(@Nullable Double value, double fallback) {
        return value != null && Double.isFinite(value) ? value : fallback;
    }

    private static double clamp01(@Nullable Double value, double fallback) {
        double resolved = value != null && Double.isFinite(value) ? value : fallback;
        return Math.max(0.0, Math.min(1.0, resolved));
    }

    public static final class ModelSettings {
        boolean applyModel;
        String modelId = DEFAULT_MODEL_ID;
        double scale = 1.0;

        public boolean isApplyModel() { return applyModel; }
        public String getModelId() { return modelId; }
        public double getScale() { return scale; }
    }

    public static final class InputSettings {
        double intentTimeoutMs = 750.0;
        double forwardDeadzone = 0.25;
        double strafeDeadzone = 0.25;
        double airborneJumpActivationDelayMs = 250.0;

        public long getIntentTimeoutMs() { return Math.round(intentTimeoutMs); }
        public double getForwardDeadzone() { return forwardDeadzone; }
        public double getStrafeDeadzone() { return strafeDeadzone; }
        public long getAirborneJumpActivationDelayMs() { return Math.round(Math.max(0.0, airborneJumpActivationDelayMs)); }
    }

    public static final class MovementSettings {
        double maxForwardSpeed = 14.0;
        double maxGlideSpeed = 15.0;
        double neutralGlideSpeed = 6.0;
        double neutralGlideAcceleration = 4.0;
        double neutralGlideDeceleration = 0.15;
        double glideStartKickSpeed = 1.5;
        double forwardAcceleration = 18.0;
        double maxBackwardSpeed = 3.0;
        double backwardAcceleration = 8.0;
        double airbrakeDeceleration = 18.0;
        double hoverHorizontalDamping = 10.0;
        double hoverVerticalDamping = 8.0;
        double glideSinkSpeed = 1.0;
        double glideSinkAcceleration = 2.0;
        double stallSpeedThreshold = 8.0;
        double stallSinkSpeed = 5.0;
        double descendSpeed = 7.0;
        double maxFallSpeed = 14.0;
        double pitchUpLiftScale = 5.0;
        double pitchUpSpeedCost = 3.0;
        double pitchDownDiveScale = 5.0;
        double pitchDownSpeedGain = 3.0;

        public double getMaxForwardSpeed() { return maxForwardSpeed; }
        public double getMaxGlideSpeed() { return Math.max(maxForwardSpeed, maxGlideSpeed); }
        public double getNeutralGlideSpeed() { return Math.max(0.0, Math.min(getMaxGlideSpeed(), neutralGlideSpeed)); }
        public double getNeutralGlideAcceleration() { return Math.max(0.0, neutralGlideAcceleration); }
        public double getNeutralGlideDeceleration() { return Math.max(0.0, neutralGlideDeceleration); }
        public double getGlideStartKickSpeed() {
            return Math.max(0.0, Math.min(getNeutralGlideSpeed(), glideStartKickSpeed));
        }
        public double getForwardAcceleration() { return Math.max(0.0, forwardAcceleration); }
        public double getMaxBackwardSpeed() { return maxBackwardSpeed; }
        public double getBackwardAcceleration() { return backwardAcceleration; }
        public double getAirbrakeDeceleration() { return airbrakeDeceleration; }
        public double getHoverHorizontalDamping() { return hoverHorizontalDamping; }
        public double getHoverVerticalDamping() { return hoverVerticalDamping; }
        public double getGlideSinkSpeed() { return Math.max(0.0, glideSinkSpeed); }
        public double getGlideSinkAcceleration() { return Math.max(0.0, glideSinkAcceleration); }
        public double getStallSpeedThreshold() { return Math.max(0.0, stallSpeedThreshold); }
        public double getStallSinkSpeed() { return Math.max(getGlideSinkSpeed(), stallSinkSpeed); }
        public double getDescendSpeed() { return descendSpeed; }
        public double getMaxFallSpeed() { return maxFallSpeed; }
        public double getPitchUpLiftScale() { return pitchUpLiftScale; }
        public double getPitchUpSpeedCost() { return pitchUpSpeedCost; }
        public double getPitchDownDiveScale() { return pitchDownDiveScale; }
        public double getPitchDownSpeedGain() { return pitchDownSpeedGain; }
    }

    public static final class JumpSettings {
        double upwardImpulse = 7.0;
        double cooldownSeconds = 0.75;

        public double getUpwardImpulse() { return upwardImpulse; }
        public double getCooldownSeconds() { return cooldownSeconds; }
    }

    public static final class BoostSettings {
        double forwardImpulse = 7.0;
        double cooldownSeconds = 1.0;
        double durationSeconds = 0.45;
        boolean directional = true;
        double upwardPitchLiftMultiplier = 0.45;
        double upwardPitchLiftCap = 3.0;

        public double getForwardImpulse() { return forwardImpulse; }
        public double getCooldownSeconds() { return cooldownSeconds; }
        public double getDurationSeconds() { return durationSeconds; }
        public boolean isDirectional() { return directional; }
        public double getUpwardPitchLiftMultiplier() { return Math.max(0.0, upwardPitchLiftMultiplier); }
        public double getUpwardPitchLiftCap() { return Math.max(0.0, upwardPitchLiftCap); }
    }

    /** Vigour charge, recharge, and HUD tuning for avatar flight. */
    public static final class VigourSettings {
        boolean enabled = true;
        double maxCharges = 6.0;
        double upwardFlapCost = 1.0;
        double forwardBoostCost = 1.0;
        double groundedRechargeSecondsPerCharge = 4.0;
        double fastFlightRechargeSecondsPerCharge = 8.0;
        double fastFlightRechargeSpeedRatio = 0.80;
        double rechargeDelayAfterSpendSeconds = 0.75;
        boolean hudEnabled = true;
        double hudResendIntervalMs = 100.0;

        public boolean isEnabled() { return enabled; }
        public double getMaxCharges() { return maxCharges; }
        public double getUpwardFlapCost() { return upwardFlapCost; }
        public double getForwardBoostCost() { return forwardBoostCost; }
        public double getGroundedRechargeSecondsPerCharge() { return groundedRechargeSecondsPerCharge; }
        public double getFastFlightRechargeSecondsPerCharge() { return fastFlightRechargeSecondsPerCharge; }
        public double getFastFlightRechargeSpeedRatio() {
            return Math.max(0.0, Math.min(1.0, fastFlightRechargeSpeedRatio));
        }
        public double getRechargeDelayAfterSpendSeconds() { return rechargeDelayAfterSpendSeconds; }
        public boolean isHudEnabled() { return hudEnabled; }
        public long getHudResendIntervalMs() { return Math.round(Math.max(1.0, hudResendIntervalMs)); }
    }

    public static final class RiderVisualSettings {
        boolean hideOwnerEquipment;
        boolean hideOwnerArmor;
        boolean hideOwnerHands;
        boolean showRider;
        double seatOffsetX;
        double seatOffsetY = 1.35;
        double seatOffsetZ = -0.25;
        double equipmentResendIntervalMs = 250.0;

        public boolean isHideOwnerEquipment() { return hideOwnerEquipment; }
        public boolean isHideOwnerArmor() { return hideOwnerArmor; }
        public boolean isHideOwnerHands() { return hideOwnerHands; }
        public boolean isShowRider() { return showRider; }
        public double getSeatOffsetX() { return seatOffsetX; }
        public double getSeatOffsetY() { return seatOffsetY; }
        public double getSeatOffsetZ() { return seatOffsetZ; }
        public long getEquipmentResendIntervalMs() {
            return Math.round(Math.max(1.0, equipmentResendIntervalMs));
        }
    }

    public static final class AnimationSettings {
        String idleAnimation = DEFAULT_IDLE_ANIMATION;
        String flightAnimation = DEFAULT_FLIGHT_ANIMATION;
        String fastFlightAnimation = DEFAULT_FAST_FLIGHT_ANIMATION;
        double resendIntervalMs = 250.0;
        boolean suppressNonMovementAnimations = true;
        boolean suppressActionAnimation = true;
        boolean suppressStatusAnimation = true;
        boolean suppressEmoteAnimation = true;
        boolean suppressFaceAnimation;
        double suppressionIntervalMs = 100.0;
        boolean poseAnimationsEnabled;
        String pitchPoseSlot = "Status";
        String rollPoseSlot = "Emote";
        String pitchUpPoseAnimation = "";
        String pitchDownPoseAnimation = "";
        String bankLeftPoseAnimation = "";
        String bankRightPoseAnimation = "";
        String pitchUpBankLeftPoseAnimation = "";
        String pitchUpBankRightPoseAnimation = "";
        String pitchDownBankLeftPoseAnimation = "";
        String pitchDownBankRightPoseAnimation = "";
        double pitchPoseThresholdDegrees = 8.0;
        double rollPoseThresholdDegrees = 5.0;
        double poseResendIntervalMs = 250.0;

        public String getIdleAnimation() { return stringOrDefault(idleAnimation, DEFAULT_IDLE_ANIMATION); }
        public String getFlightAnimation() { return stringOrDefault(flightAnimation, DEFAULT_FLIGHT_ANIMATION); }
        public String getFastFlightAnimation() {
            return stringOrDefault(fastFlightAnimation, DEFAULT_FAST_FLIGHT_ANIMATION);
        }
        public long getResendIntervalMs() { return Math.round(Math.max(0.0, resendIntervalMs)); }
        public boolean isSuppressNonMovementAnimations() { return suppressNonMovementAnimations; }
        public boolean isSuppressActionAnimation() { return suppressActionAnimation; }
        public boolean isSuppressStatusAnimation() { return suppressStatusAnimation; }
        public boolean isSuppressEmoteAnimation() { return suppressEmoteAnimation; }
        public boolean isSuppressFaceAnimation() { return suppressFaceAnimation; }
        public long getSuppressionIntervalMs() { return Math.round(Math.max(1.0, suppressionIntervalMs)); }
        public boolean isPoseAnimationsEnabled() { return poseAnimationsEnabled; }
        public String getPitchPoseSlot() { return stringOrDefault(pitchPoseSlot, "Status"); }
        public String getRollPoseSlot() { return stringOrDefault(rollPoseSlot, "Emote"); }
        public String getPitchUpPoseAnimation() { return blankOrTrim(pitchUpPoseAnimation); }
        public String getPitchDownPoseAnimation() { return blankOrTrim(pitchDownPoseAnimation); }
        public String getBankLeftPoseAnimation() { return blankOrTrim(bankLeftPoseAnimation); }
        public String getBankRightPoseAnimation() { return blankOrTrim(bankRightPoseAnimation); }
        public String getPitchUpBankLeftPoseAnimation() { return blankOrTrim(pitchUpBankLeftPoseAnimation); }
        public String getPitchUpBankRightPoseAnimation() { return blankOrTrim(pitchUpBankRightPoseAnimation); }
        public String getPitchDownBankLeftPoseAnimation() { return blankOrTrim(pitchDownBankLeftPoseAnimation); }
        public String getPitchDownBankRightPoseAnimation() { return blankOrTrim(pitchDownBankRightPoseAnimation); }
        public double getPitchPoseThresholdDegrees() { return Math.max(0.1, pitchPoseThresholdDegrees); }
        public double getRollPoseThresholdDegrees() { return Math.max(0.1, rollPoseThresholdDegrees); }
        public long getPoseResendIntervalMs() { return Math.round(Math.max(1.0, poseResendIntervalMs)); }

        public String animationFor(boolean horizontalIdle, boolean fastFlight) {
            if (horizontalIdle) {
                return getIdleAnimation();
            }
            return fastFlight ? getFastFlightAnimation() : getFlightAnimation();
        }

        @Nonnull
        public String pitchPoseAnimationFor(double pitchDegrees) {
            if (!isPoseAnimationsEnabled()) {
                return "";
            }
            if (pitchDegrees > getPitchPoseThresholdDegrees()) {
                return getPitchUpPoseAnimation();
            }
            if (pitchDegrees < -getPitchPoseThresholdDegrees()) {
                return getPitchDownPoseAnimation();
            }
            return "";
        }

        @Nonnull
        public String rollPoseAnimationFor(double rollDegrees) {
            if (!isPoseAnimationsEnabled()) {
                return "";
            }
            if (rollDegrees > getRollPoseThresholdDegrees()) {
                return getBankRightPoseAnimation();
            }
            if (rollDegrees < -getRollPoseThresholdDegrees()) {
                return getBankLeftPoseAnimation();
            }
            return "";
        }

        @Nonnull
        public String sharedPoseAnimationFor(double pitchDegrees, double rollDegrees) {
            String pitchAnimation = pitchPoseAnimationFor(pitchDegrees);
            String rollAnimation = rollPoseAnimationFor(rollDegrees);
            if (!pitchAnimation.isBlank() && !rollAnimation.isBlank()) {
                String combinedAnimation = combinedPoseAnimationFor(pitchDegrees, rollDegrees);
                if (!combinedAnimation.isBlank()) {
                    return combinedAnimation;
                }
            }
            return !rollAnimation.isBlank() ? rollAnimation : pitchAnimation;
        }

        @Nonnull
        private String combinedPoseAnimationFor(double pitchDegrees, double rollDegrees) {
            boolean pitchUp = pitchDegrees > getPitchPoseThresholdDegrees();
            boolean pitchDown = pitchDegrees < -getPitchPoseThresholdDegrees();
            boolean bankRight = rollDegrees > getRollPoseThresholdDegrees();
            boolean bankLeft = rollDegrees < -getRollPoseThresholdDegrees();
            if (pitchUp && bankLeft) {
                return getPitchUpBankLeftPoseAnimation();
            }
            if (pitchUp && bankRight) {
                return getPitchUpBankRightPoseAnimation();
            }
            if (pitchDown && bankLeft) {
                return getPitchDownBankLeftPoseAnimation();
            }
            if (pitchDown && bankRight) {
                return getPitchDownBankRightPoseAnimation();
            }
            return "";
        }
    }

    public static final class DebugSettings {
        boolean logControllerTicks;
        boolean logInputTransitions;

        public boolean isLogControllerTicks() { return logControllerTicks; }
        public boolean isLogInputTransitions() { return logInputTransitions; }
    }
}
