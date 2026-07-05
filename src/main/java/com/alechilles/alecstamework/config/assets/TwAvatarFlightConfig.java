package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.AssetRegistry;
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
                    (settings, value) -> settings.intentTimeoutMs = positiveOrDefault(value, 250.0),
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
            .build();

    private static final BuilderCodec<MovementSettings> MOVEMENT_CODEC = BuilderCodec.builder(
            MovementSettings.class,
            MovementSettings::new
    )
            .<Double>append(new KeyedCodec<>("MaxForwardSpeed", Codec.DOUBLE),
                    (settings, value) -> settings.maxForwardSpeed = positiveOrDefault(value, 14.0),
                    settings -> settings.maxForwardSpeed)
            .documentation("Maximum forward flight speed. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("ForwardAcceleration", Codec.DOUBLE),
                    (settings, value) -> settings.forwardAcceleration = nonNegativeOrDefault(value, 18.0),
                    settings -> settings.forwardAcceleration)
            .documentation("Forward acceleration while W intent is active. Inheritance: missing nested key inherits parent value.")
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
                    (settings, value) -> settings.pitchDownSpeedGain = nonNegativeOrDefault(value, 8.0),
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
            .documentation("Minimum milliseconds between forced movement-animation packets while the same animation remains active. Inheritance: missing nested key inherits parent value.")
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
                    (settings, value) -> settings.hideOwnerEquipment = value == null || value,
                    settings -> settings.hideOwnerEquipment)
            .documentation("Whether avatar flight sends equipment packets that hide the transformed player's equipped visuals. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Boolean>append(new KeyedCodec<>("HideOwnerArmor", Codec.BOOLEAN),
                    (settings, value) -> settings.hideOwnerArmor = value == null || value,
                    settings -> settings.hideOwnerArmor)
            .documentation("Whether hidden owner equipment also blanks armor slots. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Boolean>append(new KeyedCodec<>("HideOwnerHands", Codec.BOOLEAN),
                    (settings, value) -> settings.hideOwnerHands = value == null || value,
                    settings -> settings.hideOwnerHands)
            .documentation("Whether hidden owner equipment blanks right-hand and left-hand item visuals. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Boolean>append(new KeyedCodec<>("ShowRider", Codec.BOOLEAN),
                    (settings, value) -> settings.showRider = value == null || value,
                    settings -> settings.showRider)
            .documentation("Whether avatar flight spawns a visual-only copy of the player's saved model as a rider. Inheritance: missing nested key inherits parent value.")
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
            .<AnimationSettings>append(new KeyedCodec<>("Animation", ANIMATION_CODEC),
                    (asset, value) -> asset.animation = value == null ? new AnimationSettings() : value,
                    asset -> asset.animation)
            .documentation("Transformed-player movement animation names. Inheritance: omitted section inherits; explicit nested keys override missing nested keys.")
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

    private static AssetStore<String, TwAvatarFlightConfig, DefaultAssetMap<String, TwAvatarFlightConfig>> ASSET_STORE;
    private static final Object CACHE_LOCK = new Object();
    private static volatile boolean CACHE_DIRTY = true;
    private static volatile TwAvatarFlightConfig ACTIVE_CONFIG;
    private static final Object INHERITANCE_CACHE_LOCK = new Object();
    private static volatile boolean INHERITANCE_CACHE_DIRTY = true;

    private AssetExtraInfo.Data data;
    private String id;
    private boolean enabled = true;
    private int priority;
    private ModelSettings model = new ModelSettings();
    private InputSettings input = new InputSettings();
    private MovementSettings movement = new MovementSettings();
    private JumpSettings jump = new JumpSettings();
    private BoostSettings boost = new BoostSettings();
    private AnimationSettings animation = new AnimationSettings();
    private RiderVisualSettings riderVisual = new RiderVisualSettings();
    private DebugSettings debug = new DebugSettings();

    protected TwAvatarFlightConfig() {
    }

    @Nullable
    public static AssetStore<String, TwAvatarFlightConfig, DefaultAssetMap<String, TwAvatarFlightConfig>> getAssetStore() {
        if (ASSET_STORE == null) {
            ASSET_STORE = AssetRegistry.getAssetStore(TwAvatarFlightConfig.class);
        }
        return ASSET_STORE;
    }

    @Nullable
    public static DefaultAssetMap<String, TwAvatarFlightConfig> getAssetMap() {
        AssetStore<String, TwAvatarFlightConfig, DefaultAssetMap<String, TwAvatarFlightConfig>> store = getAssetStore();
        if (store == null) {
            return null;
        }
        DefaultAssetMap<String, TwAvatarFlightConfig> assetMap =
                (DefaultAssetMap<String, TwAvatarFlightConfig>) store.getAssetMap();
        ensureInheritanceFallbackApplied(assetMap);
        return assetMap;
    }

    public static void clearCache() {
        CACHE_DIRTY = true;
        INHERITANCE_CACHE_DIRTY = true;
    }

    @Nonnull
    public static TwAvatarFlightConfig resolveActive() {
        DefaultAssetMap<String, TwAvatarFlightConfig> assetMap = getAssetMap();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return defaultConfig();
        }
        TwAvatarFlightConfig cached = ACTIVE_CONFIG;
        if (CACHE_DIRTY || cached == null) {
            synchronized (CACHE_LOCK) {
                if (CACHE_DIRTY || ACTIVE_CONFIG == null) {
                    ACTIVE_CONFIG = selectBest(assetMap.getAssetMap().values());
                    CACHE_DIRTY = false;
                }
                cached = ACTIVE_CONFIG;
            }
        }
        return cached == null ? defaultConfig() : cached;
    }

    @Nonnull
    public static TwAvatarFlightConfig resolve(@Nullable String configId) {
        if (configId == null || configId.isBlank()) {
            return resolveActive();
        }
        DefaultAssetMap<String, TwAvatarFlightConfig> assetMap = getAssetMap();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return defaultConfig();
        }
        TwAvatarFlightConfig direct = assetMap.getAssetMap().get(configId);
        if (direct != null && direct.isEnabled()) {
            return direct;
        }
        for (TwAvatarFlightConfig candidate : assetMap.getAssetMap().values()) {
            if (candidate != null && candidate.isEnabled()
                    && candidate.getId() != null && candidate.getId().equalsIgnoreCase(configId.trim())) {
                return candidate;
            }
        }
        return resolveActive();
    }

    @Nonnull
    public static TwAvatarFlightConfig defaultConfig() {
        return new TwAvatarFlightConfig();
    }

    @Nullable
    private static TwAvatarFlightConfig selectBest(@Nullable Iterable<TwAvatarFlightConfig> candidates) {
        TwAvatarFlightConfig best = null;
        if (candidates == null) {
            return null;
        }
        for (TwAvatarFlightConfig candidate : candidates) {
            if (candidate == null || !candidate.isEnabled()) {
                continue;
            }
            if (best == null || candidate.getPriority() > best.getPriority()
                    || (candidate.getPriority() == best.getPriority()
                    && safe(candidate.getId()).compareToIgnoreCase(safe(best.getId())) < 0)) {
                best = candidate;
            }
        }
        return best;
    }

    private static void ensureInheritanceFallbackApplied(
            @Nullable DefaultAssetMap<String, TwAvatarFlightConfig> assetMap) {
        if (!INHERITANCE_CACHE_DIRTY || assetMap == null || assetMap.getAssetMap() == null) {
            return;
        }
        synchronized (INHERITANCE_CACHE_LOCK) {
            if (!INHERITANCE_CACHE_DIRTY || assetMap.getAssetMap() == null) {
                return;
            }
            TwAssetInheritanceFallback.repairAll(assetMap);
            INHERITANCE_CACHE_DIRTY = false;
        }
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
        if (!explicitTopLevelKeys.contains("Enabled")) enabled = parent.enabled;
        if (!explicitTopLevelKeys.contains("Priority")) priority = parent.priority;
        inheritOrCopyModel(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Model"), explicitTopLevelKeys);
        inheritOrCopyInput(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Input"), explicitTopLevelKeys);
        inheritOrCopyMovement(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Movement"), explicitTopLevelKeys);
        inheritOrCopyJump(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Jump"), explicitTopLevelKeys);
        inheritOrCopyBoost(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Boost"), explicitTopLevelKeys);
        inheritOrCopyAnimation(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Animation"), explicitTopLevelKeys);
        inheritOrCopyRiderVisual(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "RiderVisual"), explicitTopLevelKeys);
        inheritOrCopyDebug(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Debug"), explicitTopLevelKeys);
    }

    private void inheritOrCopyModel(TwAvatarFlightConfig parent, @Nullable Set<String> keys, Set<String> top) {
        if (!top.contains("Model")) model = parent.model;
        else if (keys != null && model != null && parent.model != null) {
            if (!keys.contains("ApplyModel")) model.applyModel = parent.model.applyModel;
            if (!keys.contains("ModelId")) model.modelId = parent.model.modelId;
            if (!keys.contains("Scale")) model.scale = parent.model.scale;
        }
    }

    private void inheritOrCopyInput(TwAvatarFlightConfig parent, @Nullable Set<String> keys, Set<String> top) {
        if (!top.contains("Input")) input = parent.input;
        else if (keys != null && input != null && parent.input != null) {
            if (!keys.contains("IntentTimeoutMs")) input.intentTimeoutMs = parent.input.intentTimeoutMs;
            if (!keys.contains("ForwardDeadzone")) input.forwardDeadzone = parent.input.forwardDeadzone;
            if (!keys.contains("StrafeDeadzone")) input.strafeDeadzone = parent.input.strafeDeadzone;
        }
    }

    private void inheritOrCopyMovement(TwAvatarFlightConfig parent, @Nullable Set<String> keys, Set<String> top) {
        if (!top.contains("Movement")) movement = parent.movement;
        else if (keys != null && movement != null && parent.movement != null) {
            if (!keys.contains("MaxForwardSpeed")) movement.maxForwardSpeed = parent.movement.maxForwardSpeed;
            if (!keys.contains("ForwardAcceleration")) movement.forwardAcceleration = parent.movement.forwardAcceleration;
            if (!keys.contains("MaxBackwardSpeed")) movement.maxBackwardSpeed = parent.movement.maxBackwardSpeed;
            if (!keys.contains("BackwardAcceleration")) movement.backwardAcceleration = parent.movement.backwardAcceleration;
            if (!keys.contains("AirbrakeDeceleration")) movement.airbrakeDeceleration = parent.movement.airbrakeDeceleration;
            if (!keys.contains("HoverHorizontalDamping")) movement.hoverHorizontalDamping = parent.movement.hoverHorizontalDamping;
            if (!keys.contains("HoverVerticalDamping")) movement.hoverVerticalDamping = parent.movement.hoverVerticalDamping;
            if (!keys.contains("DescendSpeed")) movement.descendSpeed = parent.movement.descendSpeed;
            if (!keys.contains("MaxFallSpeed")) movement.maxFallSpeed = parent.movement.maxFallSpeed;
            if (!keys.contains("PitchUpLiftScale")) movement.pitchUpLiftScale = parent.movement.pitchUpLiftScale;
            if (!keys.contains("PitchUpSpeedCost")) movement.pitchUpSpeedCost = parent.movement.pitchUpSpeedCost;
            if (!keys.contains("PitchDownDiveScale")) movement.pitchDownDiveScale = parent.movement.pitchDownDiveScale;
            if (!keys.contains("PitchDownSpeedGain")) movement.pitchDownSpeedGain = parent.movement.pitchDownSpeedGain;
        }
    }

    private void inheritOrCopyJump(TwAvatarFlightConfig parent, @Nullable Set<String> keys, Set<String> top) {
        if (!top.contains("Jump")) jump = parent.jump;
        else if (keys != null && jump != null && parent.jump != null) {
            if (!keys.contains("UpwardImpulse")) jump.upwardImpulse = parent.jump.upwardImpulse;
            if (!keys.contains("CooldownSeconds")) jump.cooldownSeconds = parent.jump.cooldownSeconds;
        }
    }

    private void inheritOrCopyBoost(TwAvatarFlightConfig parent, @Nullable Set<String> keys, Set<String> top) {
        if (!top.contains("Boost")) boost = parent.boost;
        else if (keys != null && boost != null && parent.boost != null) {
            if (!keys.contains("ForwardImpulse")) boost.forwardImpulse = parent.boost.forwardImpulse;
            if (!keys.contains("CooldownSeconds")) boost.cooldownSeconds = parent.boost.cooldownSeconds;
            if (!keys.contains("DurationSeconds")) boost.durationSeconds = parent.boost.durationSeconds;
        }
    }

    private void inheritOrCopyAnimation(TwAvatarFlightConfig parent, @Nullable Set<String> keys, Set<String> top) {
        if (!top.contains("Animation")) animation = parent.animation;
        else if (keys != null && animation != null && parent.animation != null) {
            if (!keys.contains("IdleAnimation")) animation.idleAnimation = parent.animation.idleAnimation;
            if (!keys.contains("FlightAnimation")) animation.flightAnimation = parent.animation.flightAnimation;
            if (!keys.contains("FastFlightAnimation")) {
                animation.fastFlightAnimation = parent.animation.fastFlightAnimation;
            }
            if (!keys.contains("ResendIntervalMs")) animation.resendIntervalMs = parent.animation.resendIntervalMs;
            if (!keys.contains("SuppressNonMovementAnimations")) {
                animation.suppressNonMovementAnimations = parent.animation.suppressNonMovementAnimations;
            }
            if (!keys.contains("SuppressActionAnimation")) {
                animation.suppressActionAnimation = parent.animation.suppressActionAnimation;
            }
            if (!keys.contains("SuppressStatusAnimation")) {
                animation.suppressStatusAnimation = parent.animation.suppressStatusAnimation;
            }
            if (!keys.contains("SuppressEmoteAnimation")) {
                animation.suppressEmoteAnimation = parent.animation.suppressEmoteAnimation;
            }
            if (!keys.contains("SuppressFaceAnimation")) {
                animation.suppressFaceAnimation = parent.animation.suppressFaceAnimation;
            }
            if (!keys.contains("SuppressionIntervalMs")) {
                animation.suppressionIntervalMs = parent.animation.suppressionIntervalMs;
            }
            if (!keys.contains("PoseAnimationsEnabled")) {
                animation.poseAnimationsEnabled = parent.animation.poseAnimationsEnabled;
            }
            if (!keys.contains("PitchPoseSlot")) animation.pitchPoseSlot = parent.animation.pitchPoseSlot;
            if (!keys.contains("RollPoseSlot")) animation.rollPoseSlot = parent.animation.rollPoseSlot;
            if (!keys.contains("PitchUpPoseAnimation")) {
                animation.pitchUpPoseAnimation = parent.animation.pitchUpPoseAnimation;
            }
            if (!keys.contains("PitchDownPoseAnimation")) {
                animation.pitchDownPoseAnimation = parent.animation.pitchDownPoseAnimation;
            }
            if (!keys.contains("BankLeftPoseAnimation")) {
                animation.bankLeftPoseAnimation = parent.animation.bankLeftPoseAnimation;
            }
            if (!keys.contains("BankRightPoseAnimation")) {
                animation.bankRightPoseAnimation = parent.animation.bankRightPoseAnimation;
            }
            if (!keys.contains("PitchUpBankLeftPoseAnimation")) {
                animation.pitchUpBankLeftPoseAnimation = parent.animation.pitchUpBankLeftPoseAnimation;
            }
            if (!keys.contains("PitchUpBankRightPoseAnimation")) {
                animation.pitchUpBankRightPoseAnimation = parent.animation.pitchUpBankRightPoseAnimation;
            }
            if (!keys.contains("PitchDownBankLeftPoseAnimation")) {
                animation.pitchDownBankLeftPoseAnimation = parent.animation.pitchDownBankLeftPoseAnimation;
            }
            if (!keys.contains("PitchDownBankRightPoseAnimation")) {
                animation.pitchDownBankRightPoseAnimation = parent.animation.pitchDownBankRightPoseAnimation;
            }
            if (!keys.contains("PitchPoseThresholdDegrees")) {
                animation.pitchPoseThresholdDegrees = parent.animation.pitchPoseThresholdDegrees;
            }
            if (!keys.contains("RollPoseThresholdDegrees")) {
                animation.rollPoseThresholdDegrees = parent.animation.rollPoseThresholdDegrees;
            }
            if (!keys.contains("PoseResendIntervalMs")) {
                animation.poseResendIntervalMs = parent.animation.poseResendIntervalMs;
            }
        }
    }

    private void inheritOrCopyDebug(TwAvatarFlightConfig parent, @Nullable Set<String> keys, Set<String> top) {
        if (!top.contains("Debug")) debug = parent.debug;
        else if (keys != null && debug != null && parent.debug != null) {
            if (!keys.contains("LogControllerTicks")) debug.logControllerTicks = parent.debug.logControllerTicks;
            if (!keys.contains("LogInputTransitions")) debug.logInputTransitions = parent.debug.logInputTransitions;
        }
    }

    private void inheritOrCopyRiderVisual(TwAvatarFlightConfig parent, @Nullable Set<String> keys, Set<String> top) {
        if (!top.contains("RiderVisual")) riderVisual = parent.riderVisual;
        else if (keys != null && riderVisual != null && parent.riderVisual != null) {
            if (!keys.contains("HideOwnerEquipment")) {
                riderVisual.hideOwnerEquipment = parent.riderVisual.hideOwnerEquipment;
            }
            if (!keys.contains("HideOwnerArmor")) riderVisual.hideOwnerArmor = parent.riderVisual.hideOwnerArmor;
            if (!keys.contains("HideOwnerHands")) riderVisual.hideOwnerHands = parent.riderVisual.hideOwnerHands;
            if (!keys.contains("ShowRider")) riderVisual.showRider = parent.riderVisual.showRider;
            if (!keys.contains("SeatOffsetX")) riderVisual.seatOffsetX = parent.riderVisual.seatOffsetX;
            if (!keys.contains("SeatOffsetY")) riderVisual.seatOffsetY = parent.riderVisual.seatOffsetY;
            if (!keys.contains("SeatOffsetZ")) riderVisual.seatOffsetZ = parent.riderVisual.seatOffsetZ;
            if (!keys.contains("EquipmentResendIntervalMs")) {
                riderVisual.equipmentResendIntervalMs = parent.riderVisual.equipmentResendIntervalMs;
            }
        }
    }

    @Nullable
    private static Set<String> nestedKeysForTopLevel(@Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel,
                                                     @Nonnull String topLevelKey) {
        return explicitNestedKeysByTopLevel == null ? null : explicitNestedKeysByTopLevel.get(topLevelKey);
    }

    public String getId() { return id; }
    public boolean isEnabled() { return enabled; }
    public int getPriority() { return priority; }
    public ModelSettings getModel() { return model == null ? new ModelSettings() : model; }
    public InputSettings getInput() { return input == null ? new InputSettings() : input; }
    public MovementSettings getMovement() { return movement == null ? new MovementSettings() : movement; }
    public JumpSettings getJump() { return jump == null ? new JumpSettings() : jump; }
    public BoostSettings getBoost() { return boost == null ? new BoostSettings() : boost; }
    public AnimationSettings getAnimation() { return animation == null ? new AnimationSettings() : animation; }
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

    private static String safe(@Nullable String value) {
        return value == null ? "" : value;
    }

    public static final class ModelSettings {
        private boolean applyModel;
        private String modelId = DEFAULT_MODEL_ID;
        private double scale = 1.0;

        public boolean isApplyModel() { return applyModel; }
        public String getModelId() { return modelId; }
        public double getScale() { return scale; }
    }

    public static final class InputSettings {
        private double intentTimeoutMs = 750.0;
        private double forwardDeadzone = 0.25;
        private double strafeDeadzone = 0.25;

        public long getIntentTimeoutMs() { return Math.round(intentTimeoutMs); }
        public double getForwardDeadzone() { return forwardDeadzone; }
        public double getStrafeDeadzone() { return strafeDeadzone; }
    }

    public static final class MovementSettings {
        private double maxForwardSpeed = 14.0;
        private double forwardAcceleration = 18.0;
        private double maxBackwardSpeed = 3.0;
        private double backwardAcceleration = 8.0;
        private double airbrakeDeceleration = 18.0;
        private double hoverHorizontalDamping = 10.0;
        private double hoverVerticalDamping = 8.0;
        private double descendSpeed = 7.0;
        private double maxFallSpeed = 14.0;
        private double pitchUpLiftScale = 5.0;
        private double pitchUpSpeedCost = 3.0;
        private double pitchDownDiveScale = 5.0;
        private double pitchDownSpeedGain = 8.0;

        public double getMaxForwardSpeed() { return maxForwardSpeed; }
        public double getForwardAcceleration() { return forwardAcceleration; }
        public double getMaxBackwardSpeed() { return maxBackwardSpeed; }
        public double getBackwardAcceleration() { return backwardAcceleration; }
        public double getAirbrakeDeceleration() { return airbrakeDeceleration; }
        public double getHoverHorizontalDamping() { return hoverHorizontalDamping; }
        public double getHoverVerticalDamping() { return hoverVerticalDamping; }
        public double getDescendSpeed() { return descendSpeed; }
        public double getMaxFallSpeed() { return maxFallSpeed; }
        public double getPitchUpLiftScale() { return pitchUpLiftScale; }
        public double getPitchUpSpeedCost() { return pitchUpSpeedCost; }
        public double getPitchDownDiveScale() { return pitchDownDiveScale; }
        public double getPitchDownSpeedGain() { return pitchDownSpeedGain; }
    }

    public static final class JumpSettings {
        private double upwardImpulse = 7.0;
        private double cooldownSeconds = 0.75;

        public double getUpwardImpulse() { return upwardImpulse; }
        public double getCooldownSeconds() { return cooldownSeconds; }
    }

    public static final class BoostSettings {
        private double forwardImpulse = 7.0;
        private double cooldownSeconds = 1.0;
        private double durationSeconds = 0.45;

        public double getForwardImpulse() { return forwardImpulse; }
        public double getCooldownSeconds() { return cooldownSeconds; }
        public double getDurationSeconds() { return durationSeconds; }
    }

    public static final class RiderVisualSettings {
        private boolean hideOwnerEquipment = true;
        private boolean hideOwnerArmor = true;
        private boolean hideOwnerHands = true;
        private boolean showRider = true;
        private double seatOffsetX;
        private double seatOffsetY = 1.35;
        private double seatOffsetZ = -0.25;
        private double equipmentResendIntervalMs = 250.0;

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
        private String idleAnimation = DEFAULT_IDLE_ANIMATION;
        private String flightAnimation = DEFAULT_FLIGHT_ANIMATION;
        private String fastFlightAnimation = DEFAULT_FAST_FLIGHT_ANIMATION;
        private double resendIntervalMs = 250.0;
        private boolean suppressNonMovementAnimations = true;
        private boolean suppressActionAnimation = true;
        private boolean suppressStatusAnimation = true;
        private boolean suppressEmoteAnimation = true;
        private boolean suppressFaceAnimation;
        private double suppressionIntervalMs = 100.0;
        private boolean poseAnimationsEnabled;
        private String pitchPoseSlot = "Status";
        private String rollPoseSlot = "Emote";
        private String pitchUpPoseAnimation = "";
        private String pitchDownPoseAnimation = "";
        private String bankLeftPoseAnimation = "";
        private String bankRightPoseAnimation = "";
        private String pitchUpBankLeftPoseAnimation = "";
        private String pitchUpBankRightPoseAnimation = "";
        private String pitchDownBankLeftPoseAnimation = "";
        private String pitchDownBankRightPoseAnimation = "";
        private double pitchPoseThresholdDegrees = 8.0;
        private double rollPoseThresholdDegrees = 5.0;
        private double poseResendIntervalMs = 250.0;

        public String getIdleAnimation() { return stringOrDefault(idleAnimation, DEFAULT_IDLE_ANIMATION); }
        public String getFlightAnimation() { return stringOrDefault(flightAnimation, DEFAULT_FLIGHT_ANIMATION); }
        public String getFastFlightAnimation() {
            return stringOrDefault(fastFlightAnimation, DEFAULT_FAST_FLIGHT_ANIMATION);
        }
        public long getResendIntervalMs() { return Math.round(Math.max(1.0, resendIntervalMs)); }
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
        private boolean logControllerTicks;
        private boolean logInputTransitions;

        public boolean isLogControllerTicks() { return logControllerTicks; }
        public boolean isLogInputTransitions() { return logInputTransitions; }
    }
}
