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
import com.hypixel.hytale.common.util.ArrayUtil;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Role-scoped tuning profile for Tamework's clean-slate mounted glide controller.
 *
 * <p>Stored under {@code Server/Tamework/Mounts/Glide}.
 */
public final class TwMountedGlideConfig implements
        JsonAssetWithMap<String, DefaultAssetMap<String, TwMountedGlideConfig>>,
        TwParentFallbackAsset<TwMountedGlideConfig> {
    private static final double DEFAULT_BASE_SPEED = 10.0;
    private static final double DEFAULT_MIN_SPEED = 4.0;
    private static final double DEFAULT_MAX_SPEED = 28.0;
    private static final double DEFAULT_PASSIVE_SINK_RATE = 0.8;
    private static final double DEFAULT_PITCH_DOWN_ACCELERATION = 8.0;
    private static final double DEFAULT_PITCH_DOWN_SINK = 3.0;
    private static final double DEFAULT_PITCH_UP_LIFT_CONVERSION = 7.0;
    private static final double DEFAULT_PITCH_UP_SPEED_DRAIN = 9.0;
    private static final double DEFAULT_STALL_THRESHOLD = 5.0;
    private static final double DEFAULT_STALL_SINK = 4.5;
    private static final double DEFAULT_FLAP_COOLDOWN_SECONDS = 0.85;
    private static final double DEFAULT_UPWARD_BOOST_STRENGTH = 6.0;
    private static final double DEFAULT_FORWARD_BOOST_STRENGTH = 7.0;
    private static final double DEFAULT_BOOST_DURATION_SECONDS = 0.25;
    private static final double DEFAULT_INPUT_GRACE_SECONDS = 0.15;
    private static final double DEFAULT_AIRBRAKE_SPEED_DECAY = 6.0;
    private static final double DEFAULT_AIRBRAKE_SINK_MULTIPLIER = 1.75;
    private static final double DEFAULT_AIRBRAKE_CONTROL_MULTIPLIER = 1.2;
    private static final double DEFAULT_MAX_PITCH_DEGREES = 55.0;
    private static final double DEFAULT_MAX_VERTICAL_SPEED = 12.0;
    private static final int DEFAULT_COLLISION_RECOVERY_TICKS = 10;
    private static final double DEFAULT_STALE_INPUT_TIMEOUT_SECONDS = 0.5;

    private static final BuilderCodec<EligibilitySettings> ELIGIBILITY_CODEC = BuilderCodec.builder(
            EligibilitySettings.class,
            EligibilitySettings::new
    )
            .<Boolean>append(
                    new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (settings, value) -> settings.enabled = value == null || value,
                    settings -> settings.enabled
            )
            .documentation("Enables this mounted glide profile.")
            .add()
            .<String[]>append(
                    new KeyedCodec<>("RequiredRoleParams", Codec.STRING_ARRAY),
                    (settings, value) -> settings.requiredRoleParams =
                            value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
                    settings -> settings.requiredRoleParams
            )
            .documentation("Role params that must be truthy before this glide profile may be used. Explicit array replaces parent value.")
            .add()
            .build();

    private static final BuilderCodec<InputSettings> INPUT_CODEC = BuilderCodec.builder(
            InputSettings.class,
            InputSettings::new
    )
            .<Boolean>append(
                    new KeyedCodec<>("HeldJumpAutoFlap", Codec.BOOLEAN),
                    (settings, value) -> settings.heldJumpAutoFlap = value == null || value,
                    settings -> settings.heldJumpAutoFlap
            )
            .documentation("If true, jump input can queue one flap request; mounted jump packets may latch, so repeat flaps require a fresh release/press.")
            .add()
            .<String>append(
                    new KeyedCodec<>("SprintFlapMode", Codec.STRING),
                    (settings, value) -> settings.sprintFlapMode = stringOrDefault(value, "FORWARD_BOOST"),
                    settings -> settings.sprintFlapMode
            )
            .documentation("Sprint behavior during a flap. V1 supports FORWARD_BOOST.")
            .add()
            .<String>append(
                    new KeyedCodec<>("CrouchMode", Codec.STRING),
                    (settings, value) -> settings.crouchMode = stringOrDefault(value, "AIRBRAKE"),
                    settings -> settings.crouchMode
            )
            .documentation("Crouch behavior while gliding. V1 supports AIRBRAKE.")
            .add()
            .build();

    private static final BuilderCodec<GlideSettings> GLIDE_CODEC = BuilderCodec.builder(
            GlideSettings.class,
            GlideSettings::new
    )
            .<Double>append(new KeyedCodec<>("BaseSpeed", Codec.DOUBLE),
                    (settings, value) -> settings.baseSpeed = positiveOrDefault(value, DEFAULT_BASE_SPEED),
                    settings -> settings.baseSpeed)
            .documentation("Starting and neutral glide speed.")
            .add()
            .<Double>append(new KeyedCodec<>("MinSpeed", Codec.DOUBLE),
                    (settings, value) -> settings.minSpeed = positiveOrDefault(value, DEFAULT_MIN_SPEED),
                    settings -> settings.minSpeed)
            .documentation("Minimum stored glide speed.")
            .add()
            .<Double>append(new KeyedCodec<>("MaxSpeed", Codec.DOUBLE),
                    (settings, value) -> settings.maxSpeed = positiveOrDefault(value, DEFAULT_MAX_SPEED),
                    settings -> settings.maxSpeed)
            .documentation("Maximum stored glide speed.")
            .add()
            .<Double>append(new KeyedCodec<>("PassiveSinkRate", Codec.DOUBLE),
                    (settings, value) -> settings.passiveSinkRate = nonNegativeOrDefault(value, DEFAULT_PASSIVE_SINK_RATE),
                    settings -> settings.passiveSinkRate)
            .documentation("Downward velocity applied during neutral glide.")
            .add()
            .<Double>append(new KeyedCodec<>("PitchDownAcceleration", Codec.DOUBLE),
                    (settings, value) -> settings.pitchDownAcceleration =
                            nonNegativeOrDefault(value, DEFAULT_PITCH_DOWN_ACCELERATION),
                    settings -> settings.pitchDownAcceleration)
            .documentation("Stored speed gained per second from pitching down.")
            .add()
            .<Double>append(new KeyedCodec<>("PitchDownSink", Codec.DOUBLE),
                    (settings, value) -> settings.pitchDownSink = nonNegativeOrDefault(value, DEFAULT_PITCH_DOWN_SINK),
                    settings -> settings.pitchDownSink)
            .documentation("Additional sink per second from pitching down.")
            .add()
            .<Double>append(new KeyedCodec<>("PitchUpLiftConversion", Codec.DOUBLE),
                    (settings, value) -> settings.pitchUpLiftConversion =
                            nonNegativeOrDefault(value, DEFAULT_PITCH_UP_LIFT_CONVERSION),
                    settings -> settings.pitchUpLiftConversion)
            .documentation("Lift generated by pitching up while enough speed remains.")
            .add()
            .<Double>append(new KeyedCodec<>("PitchUpSpeedDrain", Codec.DOUBLE),
                    (settings, value) -> settings.pitchUpSpeedDrain =
                            nonNegativeOrDefault(value, DEFAULT_PITCH_UP_SPEED_DRAIN),
                    settings -> settings.pitchUpSpeedDrain)
            .documentation("Stored speed spent per second while pitching up.")
            .add()
            .<Double>append(new KeyedCodec<>("StallThreshold", Codec.DOUBLE),
                    (settings, value) -> settings.stallThreshold = positiveOrDefault(value, DEFAULT_STALL_THRESHOLD),
                    settings -> settings.stallThreshold)
            .documentation("Speed below which steep pitch-up input stalls.")
            .add()
            .<Double>append(new KeyedCodec<>("StallSink", Codec.DOUBLE),
                    (settings, value) -> settings.stallSink = nonNegativeOrDefault(value, DEFAULT_STALL_SINK),
                    settings -> settings.stallSink)
            .documentation("Additional downward velocity applied during a stall.")
            .add()
            .build();

    private static final BuilderCodec<FlapSettings> FLAP_CODEC = BuilderCodec.builder(
            FlapSettings.class,
            FlapSettings::new
    )
            .<Double>append(new KeyedCodec<>("CooldownSeconds", Codec.DOUBLE),
                    (settings, value) -> settings.cooldownSeconds =
                            positiveOrDefault(value, DEFAULT_FLAP_COOLDOWN_SECONDS),
                    settings -> settings.cooldownSeconds)
            .documentation("Seconds between discrete flap impulses.")
            .add()
            .<Double>append(new KeyedCodec<>("UpwardBoostStrength", Codec.DOUBLE),
                    (settings, value) -> settings.upwardBoostStrength =
                            nonNegativeOrDefault(value, DEFAULT_UPWARD_BOOST_STRENGTH),
                    settings -> settings.upwardBoostStrength)
            .documentation("Vertical impulse from a normal jump flap.")
            .add()
            .<Double>append(new KeyedCodec<>("ForwardBoostStrength", Codec.DOUBLE),
                    (settings, value) -> settings.forwardBoostStrength =
                            nonNegativeOrDefault(value, DEFAULT_FORWARD_BOOST_STRENGTH),
                    settings -> settings.forwardBoostStrength)
            .documentation("Stored-speed impulse from a sprint flap.")
            .add()
            .<Double>append(new KeyedCodec<>("BoostDurationSeconds", Codec.DOUBLE),
                    (settings, value) -> settings.boostDurationSeconds =
                            nonNegativeOrDefault(value, DEFAULT_BOOST_DURATION_SECONDS),
                    settings -> settings.boostDurationSeconds)
            .documentation("Presentation/physics window for a flap boost.")
            .add()
            .<Double>append(new KeyedCodec<>("InputGraceSeconds", Codec.DOUBLE),
                    (settings, value) -> settings.inputGraceSeconds =
                            nonNegativeOrDefault(value, DEFAULT_INPUT_GRACE_SECONDS),
                    settings -> settings.inputGraceSeconds)
            .documentation("Reserved input grace for packet jitter around flap requests.")
            .add()
            .build();

    private static final BuilderCodec<AirbrakeSettings> AIRBRAKE_CODEC = BuilderCodec.builder(
            AirbrakeSettings.class,
            AirbrakeSettings::new
    )
            .<Double>append(new KeyedCodec<>("SpeedDecay", Codec.DOUBLE),
                    (settings, value) -> settings.speedDecay = nonNegativeOrDefault(value, DEFAULT_AIRBRAKE_SPEED_DECAY),
                    settings -> settings.speedDecay)
            .documentation("Stored speed lost per second while crouch airbrake is held.")
            .add()
            .<Double>append(new KeyedCodec<>("SinkMultiplier", Codec.DOUBLE),
                    (settings, value) -> settings.sinkMultiplier =
                            positiveOrDefault(value, DEFAULT_AIRBRAKE_SINK_MULTIPLIER),
                    settings -> settings.sinkMultiplier)
            .documentation("Multiplier applied to passive sink while airbraking.")
            .add()
            .<Double>append(new KeyedCodec<>("ControlMultiplier", Codec.DOUBLE),
                    (settings, value) -> settings.controlMultiplier =
                            positiveOrDefault(value, DEFAULT_AIRBRAKE_CONTROL_MULTIPLIER),
                    settings -> settings.controlMultiplier)
            .documentation("Control multiplier exposed to steering while airbraking.")
            .add()
            .build();

    private static final BuilderCodec<SafetySettings> SAFETY_CODEC = BuilderCodec.builder(
            SafetySettings.class,
            SafetySettings::new
    )
            .<Double>append(new KeyedCodec<>("StaleInputTimeoutSeconds", Codec.DOUBLE),
                    (settings, value) -> settings.staleInputTimeoutSeconds =
                            positiveOrDefault(value, DEFAULT_STALE_INPUT_TIMEOUT_SECONDS),
                    settings -> settings.staleInputTimeoutSeconds)
            .documentation("Seconds before a stale input snapshot is ignored.")
            .add()
            .<Double>append(new KeyedCodec<>("MaxPitchDegrees", Codec.DOUBLE),
                    (settings, value) -> settings.maxPitchDegrees =
                            positiveOrDefault(value, DEFAULT_MAX_PITCH_DEGREES),
                    settings -> settings.maxPitchDegrees)
            .documentation("Absolute pitch clamp used by glide physics.")
            .add()
            .<Double>append(new KeyedCodec<>("MaxVerticalSpeed", Codec.DOUBLE),
                    (settings, value) -> settings.maxVerticalSpeed =
                            positiveOrDefault(value, DEFAULT_MAX_VERTICAL_SPEED),
                    settings -> settings.maxVerticalSpeed)
            .documentation("Absolute vertical velocity clamp.")
            .add()
            .<Integer>append(new KeyedCodec<>("CollisionRecoveryTicks", Codec.INTEGER),
                    (settings, value) -> settings.collisionRecoveryTicks =
                            value == null ? DEFAULT_COLLISION_RECOVERY_TICKS : Math.max(0, value),
                    settings -> settings.collisionRecoveryTicks)
            .documentation("Ticks of horizontal suppression after airborne collision.")
            .add()
            .build();

    private static final BuilderCodec<PresentationSettings> PRESENTATION_CODEC = BuilderCodec.builder(
            PresentationSettings.class,
            PresentationSettings::new
    )
            .<String>append(new KeyedCodec<>("GlideAnimation", Codec.STRING),
                    (settings, value) -> settings.glideAnimation = stringOrDefault(value, "Fly"),
                    settings -> settings.glideAnimation)
            .documentation("Movement animation used during normal glide.")
            .add()
            .<String>append(new KeyedCodec<>("FlapAnimation", Codec.STRING),
                    (settings, value) -> settings.flapAnimation = stringOrDefault(value, "FlyFast"),
                    settings -> settings.flapAnimation)
            .documentation("Movement animation used during flap boosts.")
            .add()
            .<String>append(new KeyedCodec<>("AirbrakeAnimation", Codec.STRING),
                    (settings, value) -> settings.airbrakeAnimation = stringOrDefault(value, "FlyIdle"),
                    settings -> settings.airbrakeAnimation)
            .documentation("Movement animation used during airbrake.")
            .add()
            .build();

    public static final AssetBuilderCodec<String, TwMountedGlideConfig> CODEC = AssetBuilderCodec.builder(
            TwMountedGlideConfig.class,
            TwMountedGlideConfig::new,
            Codec.STRING,
            (asset, id) -> asset.id = id,
            asset -> asset.id,
            (asset, data) -> asset.data = data,
            asset -> asset.data
    )
            .documentation("Role-scoped mounted glide controller tuning.")
            .<Boolean>append(new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (asset, value) -> asset.enabled = value == null || value,
                    asset -> asset.enabled)
            .documentation("Turns this mounted glide profile on or off.")
            .add()
            .<Integer>append(new KeyedCodec<>("Priority", Codec.INTEGER),
                    (asset, value) -> asset.priority = value == null ? 0 : value,
                    asset -> asset.priority)
            .documentation("Priority used when multiple glide configs apply; higher values take precedence.")
            .add()
            .<String[]>append(new KeyedCodec<>("RoleIds", Codec.STRING_ARRAY),
                    (asset, value) -> asset.roleIds = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
                    asset -> asset.roleIds)
            .documentation("NPC role IDs this config applies to. Inheritance: omitted value inherits from parent; explicit array replaces parent value.")
            .add()
            .<EligibilitySettings>append(new KeyedCodec<>("Eligibility", ELIGIBILITY_CODEC),
                    (asset, value) -> asset.eligibility = value == null ? new EligibilitySettings() : value,
                    asset -> asset.eligibility)
            .documentation("Glide eligibility settings. Inheritance: omitted section inherits; explicit nested keys override missing nested keys.")
            .add()
            .<InputSettings>append(new KeyedCodec<>("Input", INPUT_CODEC),
                    (asset, value) -> asset.input = value == null ? new InputSettings() : value,
                    asset -> asset.input)
            .documentation("Rider input interpretation. Inheritance: omitted section inherits; explicit nested keys override missing nested keys.")
            .add()
            .<GlideSettings>append(new KeyedCodec<>("Glide", GLIDE_CODEC),
                    (asset, value) -> asset.glide = value == null ? new GlideSettings() : value,
                    asset -> asset.glide)
            .documentation("Pitch-weighted glide physics. Inheritance: omitted section inherits; explicit nested keys override missing nested keys.")
            .add()
            .<FlapSettings>append(new KeyedCodec<>("Flap", FLAP_CODEC),
                    (asset, value) -> asset.flap = value == null ? new FlapSettings() : value,
                    asset -> asset.flap)
            .documentation("Cooldown-gated flap impulses. Inheritance: omitted section inherits; explicit nested keys override missing nested keys.")
            .add()
            .<AirbrakeSettings>append(new KeyedCodec<>("Airbrake", AIRBRAKE_CODEC),
                    (asset, value) -> asset.airbrake = value == null ? new AirbrakeSettings() : value,
                    asset -> asset.airbrake)
            .documentation("Crouch airbrake behavior. Inheritance: omitted section inherits; explicit nested keys override missing nested keys.")
            .add()
            .<SafetySettings>append(new KeyedCodec<>("Safety", SAFETY_CODEC),
                    (asset, value) -> asset.safety = value == null ? new SafetySettings() : value,
                    asset -> asset.safety)
            .documentation("Glide safety clamps. Inheritance: omitted section inherits; explicit nested keys override missing nested keys.")
            .add()
            .<PresentationSettings>append(new KeyedCodec<>("Presentation", PRESENTATION_CODEC),
                    (asset, value) -> asset.presentation = value == null ? new PresentationSettings() : value,
                    asset -> asset.presentation)
            .documentation("Animation labels. Inheritance: omitted section inherits; explicit nested keys override missing nested keys.")
            .add()
            .build();

    private static AssetStore<String, TwMountedGlideConfig, DefaultAssetMap<String, TwMountedGlideConfig>> ASSET_STORE;
    private static final Object INHERITANCE_CACHE_LOCK = new Object();
    private static volatile boolean INHERITANCE_CACHE_DIRTY = true;
    private static final Object ROLE_CACHE_LOCK = new Object();
    private static volatile boolean ROLE_CACHE_DIRTY = true;
    private static volatile Map<String, TwMountedGlideConfig> ROLE_CACHE = Map.of();

    private AssetExtraInfo.Data data;
    private String id;
    private boolean enabled = true;
    private int priority;
    private String[] roleIds = ArrayUtil.EMPTY_STRING_ARRAY;
    private EligibilitySettings eligibility = new EligibilitySettings();
    private InputSettings input = new InputSettings();
    private GlideSettings glide = new GlideSettings();
    private FlapSettings flap = new FlapSettings();
    private AirbrakeSettings airbrake = new AirbrakeSettings();
    private SafetySettings safety = new SafetySettings();
    private PresentationSettings presentation = new PresentationSettings();

    public static AssetStore<String, TwMountedGlideConfig, DefaultAssetMap<String, TwMountedGlideConfig>> getAssetStore() {
        if (ASSET_STORE == null) {
            ASSET_STORE = AssetRegistry.getAssetStore(TwMountedGlideConfig.class);
        }
        return ASSET_STORE;
    }

    @Nullable
    public static DefaultAssetMap<String, TwMountedGlideConfig> getAssetMap() {
        AssetStore<String, TwMountedGlideConfig, DefaultAssetMap<String, TwMountedGlideConfig>> store = getAssetStore();
        if (store == null) {
            return null;
        }
        DefaultAssetMap<String, TwMountedGlideConfig> assetMap =
                (DefaultAssetMap<String, TwMountedGlideConfig>) store.getAssetMap();
        ensureInheritanceFallbackApplied(assetMap);
        return assetMap;
    }

    public static void clearRoleCache() {
        INHERITANCE_CACHE_DIRTY = true;
        ROLE_CACHE_DIRTY = true;
    }

    @Nullable
    public static TwMountedGlideConfig resolveForRole(@Nullable String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        DefaultAssetMap<String, TwMountedGlideConfig> assetMap = getAssetMap();
        if (assetMap == null) {
            return null;
        }
        Map<String, TwMountedGlideConfig> cache = ROLE_CACHE;
        if (ROLE_CACHE_DIRTY || cache == null) {
            synchronized (ROLE_CACHE_LOCK) {
                if (ROLE_CACHE_DIRTY || ROLE_CACHE == null) {
                    ROLE_CACHE = buildRoleCache(assetMap);
                    ROLE_CACHE_DIRTY = false;
                }
                cache = ROLE_CACHE;
            }
        }
        return cache.get(normalizeRole(roleId));
    }

    @Nonnull
    private static Map<String, TwMountedGlideConfig> buildRoleCache(
            @Nullable DefaultAssetMap<String, TwMountedGlideConfig> assetMap) {
        Map<String, TwMountedGlideConfig> cache = new HashMap<>();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return cache;
        }
        for (TwMountedGlideConfig candidate : assetMap.getAssetMap().values()) {
            if (candidate == null || !candidate.isEnabled()) {
                continue;
            }
            for (String roleId : candidate.getRoleIds()) {
                if (roleId == null || roleId.isBlank()) {
                    continue;
                }
                String key = normalizeRole(roleId);
                TwMountedGlideConfig existing = cache.get(key);
                if (existing == null || candidate.getPriority() > existing.getPriority()) {
                    cache.put(key, candidate);
                }
            }
        }
        return cache;
    }

    private static void ensureInheritanceFallbackApplied(
            @Nullable DefaultAssetMap<String, TwMountedGlideConfig> assetMap) {
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
    public void inheritMissingTopLevelFrom(@Nonnull TwMountedGlideConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys) {
        inheritMissingTopLevelFrom(parent, explicitTopLevelKeys, null);
    }

    @Override
    public void inheritMissingTopLevelFrom(@Nonnull TwMountedGlideConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys,
                                           @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        if (!explicitTopLevelKeys.contains("Enabled")) enabled = parent.enabled;
        if (!explicitTopLevelKeys.contains("Priority")) priority = parent.priority;
        if (!explicitTopLevelKeys.contains("RoleIds")) roleIds = parent.roleIds;
        if (!explicitTopLevelKeys.contains("Eligibility")) eligibility = parent.eligibility;
        else inheritEligibility(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Eligibility"));
        if (!explicitTopLevelKeys.contains("Input")) input = parent.input;
        else inheritInput(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Input"));
        if (!explicitTopLevelKeys.contains("Glide")) glide = parent.glide;
        else inheritGlide(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Glide"));
        if (!explicitTopLevelKeys.contains("Flap")) flap = parent.flap;
        else inheritFlap(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Flap"));
        if (!explicitTopLevelKeys.contains("Airbrake")) airbrake = parent.airbrake;
        else inheritAirbrake(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Airbrake"));
        if (!explicitTopLevelKeys.contains("Safety")) safety = parent.safety;
        else inheritSafety(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Safety"));
        if (!explicitTopLevelKeys.contains("Presentation")) presentation = parent.presentation;
        else inheritPresentation(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Presentation"));
    }

    private void inheritEligibility(@Nonnull TwMountedGlideConfig parent, @Nullable Set<String> keys) {
        if (keys == null) return;
        if (eligibility == null) eligibility = parent.eligibility;
        else if (parent.eligibility != null) {
            if (!keys.contains("Enabled")) eligibility.enabled = parent.eligibility.enabled;
            if (!keys.contains("RequiredRoleParams")) eligibility.requiredRoleParams = parent.eligibility.requiredRoleParams;
        }
    }

    private void inheritInput(@Nonnull TwMountedGlideConfig parent, @Nullable Set<String> keys) {
        if (keys == null) return;
        if (input == null) input = parent.input;
        else if (parent.input != null) {
            if (!keys.contains("HeldJumpAutoFlap")) input.heldJumpAutoFlap = parent.input.heldJumpAutoFlap;
            if (!keys.contains("SprintFlapMode")) input.sprintFlapMode = parent.input.sprintFlapMode;
            if (!keys.contains("CrouchMode")) input.crouchMode = parent.input.crouchMode;
        }
    }

    private void inheritGlide(@Nonnull TwMountedGlideConfig parent, @Nullable Set<String> keys) {
        if (keys == null) return;
        if (glide == null) glide = parent.glide;
        else if (parent.glide != null) {
            if (!keys.contains("BaseSpeed")) glide.baseSpeed = parent.glide.baseSpeed;
            if (!keys.contains("MinSpeed")) glide.minSpeed = parent.glide.minSpeed;
            if (!keys.contains("MaxSpeed")) glide.maxSpeed = parent.glide.maxSpeed;
            if (!keys.contains("PassiveSinkRate")) glide.passiveSinkRate = parent.glide.passiveSinkRate;
            if (!keys.contains("PitchDownAcceleration")) glide.pitchDownAcceleration = parent.glide.pitchDownAcceleration;
            if (!keys.contains("PitchDownSink")) glide.pitchDownSink = parent.glide.pitchDownSink;
            if (!keys.contains("PitchUpLiftConversion")) glide.pitchUpLiftConversion = parent.glide.pitchUpLiftConversion;
            if (!keys.contains("PitchUpSpeedDrain")) glide.pitchUpSpeedDrain = parent.glide.pitchUpSpeedDrain;
            if (!keys.contains("StallThreshold")) glide.stallThreshold = parent.glide.stallThreshold;
            if (!keys.contains("StallSink")) glide.stallSink = parent.glide.stallSink;
        }
    }

    private void inheritFlap(@Nonnull TwMountedGlideConfig parent, @Nullable Set<String> keys) {
        if (keys == null) return;
        if (flap == null) flap = parent.flap;
        else if (parent.flap != null) {
            if (!keys.contains("CooldownSeconds")) flap.cooldownSeconds = parent.flap.cooldownSeconds;
            if (!keys.contains("UpwardBoostStrength")) flap.upwardBoostStrength = parent.flap.upwardBoostStrength;
            if (!keys.contains("ForwardBoostStrength")) flap.forwardBoostStrength = parent.flap.forwardBoostStrength;
            if (!keys.contains("BoostDurationSeconds")) flap.boostDurationSeconds = parent.flap.boostDurationSeconds;
            if (!keys.contains("InputGraceSeconds")) flap.inputGraceSeconds = parent.flap.inputGraceSeconds;
        }
    }

    private void inheritAirbrake(@Nonnull TwMountedGlideConfig parent, @Nullable Set<String> keys) {
        if (keys == null) return;
        if (airbrake == null) airbrake = parent.airbrake;
        else if (parent.airbrake != null) {
            if (!keys.contains("SpeedDecay")) airbrake.speedDecay = parent.airbrake.speedDecay;
            if (!keys.contains("SinkMultiplier")) airbrake.sinkMultiplier = parent.airbrake.sinkMultiplier;
            if (!keys.contains("ControlMultiplier")) airbrake.controlMultiplier = parent.airbrake.controlMultiplier;
        }
    }

    private void inheritSafety(@Nonnull TwMountedGlideConfig parent, @Nullable Set<String> keys) {
        if (keys == null) return;
        if (safety == null) safety = parent.safety;
        else if (parent.safety != null) {
            if (!keys.contains("StaleInputTimeoutSeconds")) safety.staleInputTimeoutSeconds = parent.safety.staleInputTimeoutSeconds;
            if (!keys.contains("MaxPitchDegrees")) safety.maxPitchDegrees = parent.safety.maxPitchDegrees;
            if (!keys.contains("MaxVerticalSpeed")) safety.maxVerticalSpeed = parent.safety.maxVerticalSpeed;
            if (!keys.contains("CollisionRecoveryTicks")) safety.collisionRecoveryTicks = parent.safety.collisionRecoveryTicks;
        }
    }

    private void inheritPresentation(@Nonnull TwMountedGlideConfig parent, @Nullable Set<String> keys) {
        if (keys == null) return;
        if (presentation == null) presentation = parent.presentation;
        else if (parent.presentation != null) {
            if (!keys.contains("GlideAnimation")) presentation.glideAnimation = parent.presentation.glideAnimation;
            if (!keys.contains("FlapAnimation")) presentation.flapAnimation = parent.presentation.flapAnimation;
            if (!keys.contains("AirbrakeAnimation")) presentation.airbrakeAnimation = parent.presentation.airbrakeAnimation;
        }
    }

    @Nullable
    private static Set<String> nestedKeysForTopLevel(@Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel,
                                                     @Nonnull String topLevelKey) {
        return explicitNestedKeysByTopLevel == null ? null : explicitNestedKeysByTopLevel.get(topLevelKey);
    }

    public String getId() {
        return id;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getPriority() {
        return priority;
    }

    public String[] getRoleIds() {
        return roleIds == null ? ArrayUtil.EMPTY_STRING_ARRAY : roleIds;
    }

    public EligibilitySettings getEligibility() {
        return eligibility == null ? new EligibilitySettings() : eligibility;
    }

    public InputSettings getInput() {
        return input == null ? new InputSettings() : input;
    }

    public GlideSettings getGlide() {
        return glide == null ? new GlideSettings() : glide;
    }

    public FlapSettings getFlap() {
        return flap == null ? new FlapSettings() : flap;
    }

    public AirbrakeSettings getAirbrake() {
        return airbrake == null ? new AirbrakeSettings() : airbrake;
    }

    public SafetySettings getSafety() {
        return safety == null ? new SafetySettings() : safety;
    }

    public PresentationSettings getPresentation() {
        return presentation == null ? new PresentationSettings() : presentation;
    }

    @Nonnull
    private static String normalizeRole(@Nonnull String roleId) {
        return roleId.trim().toLowerCase(Locale.ROOT);
    }

    private static String stringOrDefault(@Nullable String value, @Nonnull String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static double positiveOrDefault(@Nullable Double value, double fallback) {
        return value != null && value > 0.0 ? value : fallback;
    }

    private static double nonNegativeOrDefault(@Nullable Double value, double fallback) {
        return value != null && value >= 0.0 ? value : fallback;
    }

    public static final class EligibilitySettings {
        private boolean enabled = true;
        private String[] requiredRoleParams = ArrayUtil.EMPTY_STRING_ARRAY;

        public boolean isEnabled() {
            return enabled;
        }

        public String[] getRequiredRoleParams() {
            return requiredRoleParams == null ? ArrayUtil.EMPTY_STRING_ARRAY : requiredRoleParams;
        }
    }

    public static final class InputSettings {
        private boolean heldJumpAutoFlap = true;
        private String sprintFlapMode = "FORWARD_BOOST";
        private String crouchMode = "AIRBRAKE";

        public boolean isHeldJumpAutoFlap() {
            return heldJumpAutoFlap;
        }

        public String getSprintFlapMode() {
            return sprintFlapMode;
        }

        public String getCrouchMode() {
            return crouchMode;
        }
    }

    public static final class GlideSettings {
        private double baseSpeed = DEFAULT_BASE_SPEED;
        private double minSpeed = DEFAULT_MIN_SPEED;
        private double maxSpeed = DEFAULT_MAX_SPEED;
        private double passiveSinkRate = DEFAULT_PASSIVE_SINK_RATE;
        private double pitchDownAcceleration = DEFAULT_PITCH_DOWN_ACCELERATION;
        private double pitchDownSink = DEFAULT_PITCH_DOWN_SINK;
        private double pitchUpLiftConversion = DEFAULT_PITCH_UP_LIFT_CONVERSION;
        private double pitchUpSpeedDrain = DEFAULT_PITCH_UP_SPEED_DRAIN;
        private double stallThreshold = DEFAULT_STALL_THRESHOLD;
        private double stallSink = DEFAULT_STALL_SINK;

        public double getBaseSpeed() { return baseSpeed; }
        public double getMinSpeed() { return minSpeed; }
        public double getMaxSpeed() { return maxSpeed; }
        public double getPassiveSinkRate() { return passiveSinkRate; }
        public double getPitchDownAcceleration() { return pitchDownAcceleration; }
        public double getPitchDownSink() { return pitchDownSink; }
        public double getPitchUpLiftConversion() { return pitchUpLiftConversion; }
        public double getPitchUpSpeedDrain() { return pitchUpSpeedDrain; }
        public double getStallThreshold() { return stallThreshold; }
        public double getStallSink() { return stallSink; }
    }

    public static final class FlapSettings {
        private double cooldownSeconds = DEFAULT_FLAP_COOLDOWN_SECONDS;
        private double upwardBoostStrength = DEFAULT_UPWARD_BOOST_STRENGTH;
        private double forwardBoostStrength = DEFAULT_FORWARD_BOOST_STRENGTH;
        private double boostDurationSeconds = DEFAULT_BOOST_DURATION_SECONDS;
        private double inputGraceSeconds = DEFAULT_INPUT_GRACE_SECONDS;

        public double getCooldownSeconds() { return cooldownSeconds; }
        public double getUpwardBoostStrength() { return upwardBoostStrength; }
        public double getForwardBoostStrength() { return forwardBoostStrength; }
        public double getBoostDurationSeconds() { return boostDurationSeconds; }
        public double getInputGraceSeconds() { return inputGraceSeconds; }
    }

    public static final class AirbrakeSettings {
        private double speedDecay = DEFAULT_AIRBRAKE_SPEED_DECAY;
        private double sinkMultiplier = DEFAULT_AIRBRAKE_SINK_MULTIPLIER;
        private double controlMultiplier = DEFAULT_AIRBRAKE_CONTROL_MULTIPLIER;

        public double getSpeedDecay() { return speedDecay; }
        public double getSinkMultiplier() { return sinkMultiplier; }
        public double getControlMultiplier() { return controlMultiplier; }
    }

    public static final class SafetySettings {
        private double staleInputTimeoutSeconds = DEFAULT_STALE_INPUT_TIMEOUT_SECONDS;
        private double maxPitchDegrees = DEFAULT_MAX_PITCH_DEGREES;
        private double maxVerticalSpeed = DEFAULT_MAX_VERTICAL_SPEED;
        private int collisionRecoveryTicks = DEFAULT_COLLISION_RECOVERY_TICKS;

        public double getStaleInputTimeoutSeconds() { return staleInputTimeoutSeconds; }
        public double getMaxPitchDegrees() { return maxPitchDegrees; }
        public double getMaxVerticalSpeed() { return maxVerticalSpeed; }
        public int getCollisionRecoveryTicks() { return collisionRecoveryTicks; }
    }

    public static final class PresentationSettings {
        private String glideAnimation = "Fly";
        private String flapAnimation = "FlyFast";
        private String airbrakeAnimation = "FlyIdle";

        public String getGlideAnimation() { return glideAnimation; }
        public String getFlapAnimation() { return flapAnimation; }
        public String getAirbrakeAnimation() { return airbrakeAnimation; }
    }
}
