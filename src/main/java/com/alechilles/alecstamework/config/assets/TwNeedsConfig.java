package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.persistence.TameworkSettingsStore;
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
 * Asset-backed hunger/thirst needs configuration for role-scoped companion progression.
 * Stored under Server/Tamework/Needs.
 */
public final class TwNeedsConfig implements JsonAssetWithMap<String, DefaultAssetMap<String, TwNeedsConfig>>,
        TwParentFallbackAsset<TwNeedsConfig> {
    private static final BuilderCodec<ValueSettings> VALUE_CODEC = BuilderCodec.builder(
            ValueSettings.class,
            ValueSettings::new
    )
        .<Double>append(
            new KeyedCodec<>("HungerDefault", Codec.DOUBLE),
            (settings, value) -> settings.hungerDefault = value,
            settings -> settings.hungerDefault
        )
        .documentation("Sets the hunger meter default value.")
        .add()
        .<Double>append(
            new KeyedCodec<>("HungerMin", Codec.DOUBLE),
            (settings, value) -> settings.hungerMin = value,
            settings -> settings.hungerMin
        )
        .documentation("Sets the hunger meter min value.")
        .add()
        .<Double>append(
            new KeyedCodec<>("HungerMax", Codec.DOUBLE),
            (settings, value) -> settings.hungerMax = value,
            settings -> settings.hungerMax
        )
        .documentation("Sets the hunger meter max value.")
        .add()
        .<Double>append(
            new KeyedCodec<>("ThirstDefault", Codec.DOUBLE),
            (settings, value) -> settings.thirstDefault = value,
            settings -> settings.thirstDefault
        )
        .documentation("Sets the thirst meter default value.")
        .add()
        .<Double>append(
            new KeyedCodec<>("ThirstMin", Codec.DOUBLE),
            (settings, value) -> settings.thirstMin = value,
            settings -> settings.thirstMin
        )
        .documentation("Sets the thirst meter min value.")
        .add()
        .<Double>append(
            new KeyedCodec<>("ThirstMax", Codec.DOUBLE),
            (settings, value) -> settings.thirstMax = value,
            settings -> settings.thirstMax
        )
        .documentation("Sets the thirst meter max value.")
        .add()
        .build();

    private static final BuilderCodec<DecaySettings> DECAY_CODEC = BuilderCodec.builder(
            DecaySettings.class,
            DecaySettings::new
    )
        .<Double>append(
            new KeyedCodec<>("HungerPerMinute", Codec.DOUBLE),
            (settings, value) -> settings.hungerPerMinute = value,
            settings -> settings.hungerPerMinute
        )
        .documentation("Hunger drain applied per minute.")
        .add()
        .<Double>append(
            new KeyedCodec<>("ThirstPerMinute", Codec.DOUBLE),
            (settings, value) -> settings.thirstPerMinute = value,
            settings -> settings.thirstPerMinute
        )
        .documentation("Thirst drain applied per minute.")
        .add()
        .build();

    private static final BuilderCodec<HappinessImpactSettings> HAPPINESS_IMPACT_CODEC = BuilderCodec.builder(
            HappinessImpactSettings.class,
            HappinessImpactSettings::new
    )
        .<Double>append(
            new KeyedCodec<>("HungerPenaltyAtMin", Codec.DOUBLE),
            (settings, value) -> settings.hungerPenaltyAtMin = value,
            settings -> settings.hungerPenaltyAtMin
        )
        .documentation("Penalty applied when hunger reaches minimum.")
        .add()
        .<Double>append(
            new KeyedCodec<>("ThirstPenaltyAtMin", Codec.DOUBLE),
            (settings, value) -> settings.thirstPenaltyAtMin = value,
            settings -> settings.thirstPenaltyAtMin
        )
        .documentation("Penalty applied when thirst reaches minimum.")
        .add()
        .<Double>append(
            new KeyedCodec<>("PenaltyCurvePower", Codec.DOUBLE),
            (settings, value) -> settings.penaltyCurvePower = value,
            settings -> settings.penaltyCurvePower
        )
        .documentation("Curve power used to scale low-needs penalties.")
        .add()
        .build();

    private static final BuilderCodec<PassiveRefillSettings> PASSIVE_REFILL_CODEC = BuilderCodec.builder(
            PassiveRefillSettings.class,
            PassiveRefillSettings::new
    )
        .<Integer>append(
            new KeyedCodec<>("SweepIntervalSeconds", Codec.INTEGER),
            (settings, value) -> settings.sweepIntervalSeconds = value,
            settings -> settings.sweepIntervalSeconds
        )
        .documentation("How often this system runs, in seconds.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("NearbyContainerFeedEnabled", Codec.BOOLEAN),
            (settings, value) -> settings.nearbyContainerFeedEnabled = value,
            settings -> settings.nearbyContainerFeedEnabled
        )
        .documentation("Enables automatic feeding from nearby containers.")
        .add()
        .<Double>append(
            new KeyedCodec<>("ContainerSearchRadius", Codec.DOUBLE),
            (settings, value) -> settings.containerSearchRadius = value,
            settings -> settings.containerSearchRadius
        )
        .documentation("Horizontal search radius in blocks for feed containers.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("ContainerVerticalScanRadius", Codec.INTEGER),
            (settings, value) -> settings.containerVerticalScanRadius = value,
            settings -> settings.containerVerticalScanRadius
        )
        .documentation("Vertical scan radius in blocks for feed containers.")
        .add()
        .<Double>append(
            new KeyedCodec<>("ContainerConsumeRadius", Codec.DOUBLE),
            (settings, value) -> settings.containerConsumeRadius = value,
            settings -> settings.containerConsumeRadius
        )
        .documentation("Distance in blocks where NPC may consume from a container.")
        .add()
        .<String[]>append(
            new KeyedCodec<>("ContainerFoodItemIds", Codec.STRING_ARRAY),
            (settings, value) -> settings.containerFoodItemIds = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            settings -> settings.containerFoodItemIds
        )
        .documentation("Item IDs treated as valid container food.")
        .add()
        .<Double>append(
            new KeyedCodec<>("HungerGainPerConsumedItem", Codec.DOUBLE),
            (settings, value) -> settings.hungerGainPerConsumedItem = value,
            settings -> settings.hungerGainPerConsumedItem
        )
        .documentation("Hunger restored per consumed container item.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("MaxContainerItemsConsumedPerSweep", Codec.INTEGER),
            (settings, value) -> settings.maxContainerItemsConsumedPerSweep = value,
            settings -> settings.maxContainerItemsConsumedPerSweep
        )
        .documentation("Maximum items consumed per sweep from nearby containers.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("NearbyWaterDrinkEnabled", Codec.BOOLEAN),
            (settings, value) -> settings.nearbyWaterDrinkEnabled = value,
            settings -> settings.nearbyWaterDrinkEnabled
        )
        .documentation("Enables automatic drinking from nearby water sources.")
        .add()
        .<Double>append(
            new KeyedCodec<>("WaterSearchRadius", Codec.DOUBLE),
            (settings, value) -> settings.waterSearchRadius = value,
            settings -> settings.waterSearchRadius
        )
        .documentation("Horizontal search radius in blocks for water sources.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("WaterVerticalScanRadius", Codec.INTEGER),
            (settings, value) -> settings.waterVerticalScanRadius = value,
            settings -> settings.waterVerticalScanRadius
        )
        .documentation("Vertical scan radius in blocks for water sources.")
        .add()
        .<Double>append(
            new KeyedCodec<>("WaterConsumeRadius", Codec.DOUBLE),
            (settings, value) -> settings.waterConsumeRadius = value,
            settings -> settings.waterConsumeRadius
        )
        .documentation("Distance in blocks where NPC may drink from water.")
        .add()
        .<Double>append(
            new KeyedCodec<>("ThirstGainPerSweepNearWater", Codec.DOUBLE),
            (settings, value) -> settings.thirstGainPerSweepNearWater = value,
            settings -> settings.thirstGainPerSweepNearWater
        )
        .documentation("Thirst restored each sweep while near valid water.")
        .add()
        .build();

    private static final BuilderCodec<ManualRefillSettings> MANUAL_REFILL_CODEC = BuilderCodec.builder(
            ManualRefillSettings.class,
            ManualRefillSettings::new
    )
        .<Double>append(
            new KeyedCodec<>("HungerGainOnFeedInteraction", Codec.DOUBLE),
            (settings, value) -> settings.hungerGainOnFeedInteraction = value,
            settings -> settings.hungerGainOnFeedInteraction
        )
        .documentation("Hunger restored when feed interaction succeeds.")
        .add()
        .<Double>append(
            new KeyedCodec<>("ThirstGainOnWaterBucket", Codec.DOUBLE),
            (settings, value) -> settings.thirstGainOnWaterBucket = value,
            settings -> settings.thirstGainOnWaterBucket
        )
        .documentation("Thirst restored when a water-bucket interaction succeeds.")
        .add()
        .<String[]>append(
            new KeyedCodec<>("WaterBucketItemIds", Codec.STRING_ARRAY),
            (settings, value) -> settings.waterBucketItemIds = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            settings -> settings.waterBucketItemIds
        )
        .documentation("Item IDs treated as valid water-bucket items.")
        .add()
        .build();

    private static final BuilderCodec<TimingSettings> TIMING_CODEC = BuilderCodec.builder(
            TimingSettings.class,
            TimingSettings::new
    )
        .<String>append(
            new KeyedCodec<>("Basis", Codec.STRING),
            (settings, value) -> settings.timerBasis = TimerBasis.fromConfigValue(value),
            settings -> settings.getTimerBasis().toConfigValue()
        )
        .documentation("Chooses which time basis this system uses.")
        .add()
        .build();

    private static final BuilderCodec<TickPolicySettings> TICK_POLICY_CODEC = BuilderCodec.builder(
            TickPolicySettings.class,
            TickPolicySettings::new
    )
        .<String>append(
            new KeyedCodec<>("Mode", Codec.STRING),
            (settings, value) -> settings.mode = TickPolicyMode.fromConfigValue(value),
            settings -> settings.getMode().toConfigValue()
        )
        .documentation("Selects how this system should operate.")
        .add()
        .<Double>append(
            new KeyedCodec<>("OwnerOfflineGraceHours", Codec.DOUBLE),
            (settings, value) -> settings.ownerOfflineGraceHours = value,
            settings -> settings.ownerOfflineGraceHours
        )
        .documentation("Hours to wait before offline-owner decay rules start.")
        .add()
        .<Double>append(
            new KeyedCodec<>("OwnerOfflineDecayMultiplier", Codec.DOUBLE),
            (settings, value) -> settings.ownerOfflineDecayMultiplier = value,
            settings -> settings.ownerOfflineDecayMultiplier
        )
        .documentation("Needs decay multiplier applied while owner is offline past grace period.")
        .add()
        .build();

    private static final BuilderCodec<DamageSettings> DAMAGE_CODEC = BuilderCodec.builder(
            DamageSettings.class,
            DamageSettings::new
    )
        .<Boolean>append(
            new KeyedCodec<>("Enabled", Codec.BOOLEAN),
            (settings, value) -> settings.enabled = value != null && value,
            settings -> settings.enabled
        )
        .documentation("Turns this section on or off.")
        .add()
        .<String>append(
            new KeyedCodec<>("Model", Codec.STRING),
            (settings, value) -> settings.model = DamageModel.fromConfigValue(value),
            settings -> settings.getModel().toConfigValue()
        )
        .documentation("Selects the model used to process this behavior. MIN_ONLY_PERCENT treats per-minute damage "
                + "values as percentages of max health; MIN_ONLY_FLAT treats them as flat damage amounts.")
        .add()
        .<String>append(
            new KeyedCodec<>("DualNeedRule", Codec.STRING),
            (settings, value) -> settings.dualNeedRule = DualNeedRule.fromConfigValue(value),
            settings -> settings.getDualNeedRule().toConfigValue()
        )
        .documentation("Rule used when both hunger and thirst penalties apply at the same time.")
        .add()
        .<Double>append(
            new KeyedCodec<>("StarvationDamagePerMinute", Codec.DOUBLE),
            (settings, value) -> settings.starvationDamagePerMinute = value,
            settings -> settings.starvationDamagePerMinute
        )
        .documentation("Damage per minute applied while starving. In MIN_ONLY_PERCENT, this is percent of max health "
                + "per minute.")
        .add()
        .<Double>append(
            new KeyedCodec<>("DehydrationDamagePerMinute", Codec.DOUBLE),
            (settings, value) -> settings.dehydrationDamagePerMinute = value,
            settings -> settings.dehydrationDamagePerMinute
        )
        .documentation("Damage per minute applied while dehydrated. In MIN_ONLY_PERCENT, this is percent of max "
                + "health per minute.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("Lethal", Codec.BOOLEAN),
            (settings, value) -> settings.lethal = value == null || value,
            settings -> settings.lethal
        )
        .documentation("If true, starvation/dehydration damage can kill the NPC.")
        .add()
        .build();

    public static final AssetBuilderCodec<String, TwNeedsConfig> CODEC = AssetBuilderCodec.builder(
            TwNeedsConfig.class,
            TwNeedsConfig::new,
            Codec.STRING,
            (asset, id) -> asset.id = id,
            asset -> asset.id,
            (asset, data) -> asset.data = data,
            asset -> asset.data
    )
        .documentation("Hunger/thirst needs configuration for Alec's Tamework companions.")
        .<Boolean>append(
            new KeyedCodec<>("Enabled", Codec.BOOLEAN),
            (asset, value) -> asset.enabled = value == null || value,
            asset -> asset.enabled
        )
        .documentation("Turns this section on or off.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("Priority", Codec.INTEGER),
            (asset, value) -> asset.priority = value == null ? 0 : value,
            asset -> asset.priority
        )
        .documentation("Priority used when multiple configs apply; higher values take precedence.")
        .add()
        .<String[]>append(
            new KeyedCodec<>("RoleIds", Codec.STRING_ARRAY),
            (asset, value) -> asset.roleIds = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            asset -> asset.roleIds
        )
        .documentation("NPC role IDs this config applies to. Inheritance: omitted value inherits from parent; explicit "
                + "array replaces parent value (no merge).")
        .add()
        .<ValueSettings>append(
            new KeyedCodec<>("Values", VALUE_CODEC),
            (asset, value) -> asset.values = value == null ? new ValueSettings() : value,
            asset -> asset.values
        )
        .documentation("Needs value bounds/defaults. Inheritance: omitted section inherits from parent; when present, "
                + "only explicitly defined nested fields override parent.")
        .add()
        .<DecaySettings>append(
            new KeyedCodec<>("Decay", DECAY_CODEC),
            (asset, value) -> asset.decay = value == null ? new DecaySettings() : value,
            asset -> asset.decay
        )
        .documentation("Per-minute needs decay settings. Inheritance: omitted section inherits from parent; when "
                + "present, only explicitly defined nested fields override parent.")
        .add()
        .<HappinessImpactSettings>append(
            new KeyedCodec<>("HappinessImpact", HAPPINESS_IMPACT_CODEC),
            (asset, value) -> asset.happinessImpact = value == null ? new HappinessImpactSettings() : value,
            asset -> asset.happinessImpact
        )
        .documentation("Need-to-happiness impact settings. Inheritance: omitted section inherits from parent; when "
                + "present, only explicitly defined nested fields override parent.")
        .add()
        .<PassiveRefillSettings>append(
            new KeyedCodec<>("PassiveRefill", PASSIVE_REFILL_CODEC),
            (asset, value) -> asset.passiveRefill = value == null ? new PassiveRefillSettings() : value,
            asset -> asset.passiveRefill
        )
        .documentation("Passive refill settings. Inheritance: omitted section inherits from parent; when present, only "
                + "explicitly defined nested fields override parent.")
        .add()
        .<ManualRefillSettings>append(
            new KeyedCodec<>("ManualRefill", MANUAL_REFILL_CODEC),
            (asset, value) -> asset.manualRefill = value == null ? new ManualRefillSettings() : value,
            asset -> asset.manualRefill
        )
        .documentation("Manual refill settings. Inheritance: omitted section inherits from parent; when present, only "
                + "explicitly defined nested fields override parent.")
        .add()
        .<TimingSettings>append(
            new KeyedCodec<>("Timing", TIMING_CODEC),
            (asset, value) -> asset.timing = value == null ? new TimingSettings() : value,
            asset -> asset.timing
        )
        .documentation("Needs timer basis settings. Inheritance: omitted section inherits from parent; when present, "
                + "only explicitly defined nested fields override parent.")
        .add()
        .<TickPolicySettings>append(
            new KeyedCodec<>("TickPolicy", TICK_POLICY_CODEC),
            (asset, value) -> asset.tickPolicy = value == null ? new TickPolicySettings() : value,
            asset -> asset.tickPolicy
        )
        .documentation("Needs ticking policy settings. Inheritance: omitted section inherits from parent; when present, "
                + "only explicitly defined nested fields override parent.")
        .add()
        .<DamageSettings>append(
            new KeyedCodec<>("Damage", DAMAGE_CODEC),
            (asset, value) -> asset.damage = value == null ? new DamageSettings() : value,
            asset -> asset.damage
        )
        .documentation("Needs damage settings. Inheritance: omitted section inherits from parent; when present, only "
                + "explicitly defined nested fields override parent.")
        .add()
        .build();

    private static AssetStore<String, TwNeedsConfig, DefaultAssetMap<String, TwNeedsConfig>> ASSET_STORE;
    private static final Object INHERITANCE_CACHE_LOCK = new Object();
    private static volatile boolean INHERITANCE_CACHE_DIRTY = true;
    private static final Object ROLE_CACHE_LOCK = new Object();
    private static volatile boolean ROLE_CACHE_DIRTY = true;
    private static volatile Map<String, TwNeedsConfig> ROLE_CACHE = Map.of();

    private AssetExtraInfo.Data data;
    private String id;
    private boolean enabled = true;
    private int priority;
    private String[] roleIds = ArrayUtil.EMPTY_STRING_ARRAY;
    private ValueSettings values = new ValueSettings();
    private DecaySettings decay = new DecaySettings();
    private HappinessImpactSettings happinessImpact = new HappinessImpactSettings();
    private PassiveRefillSettings passiveRefill = new PassiveRefillSettings();
    private ManualRefillSettings manualRefill = new ManualRefillSettings();
    private TimingSettings timing = new TimingSettings();
    private TickPolicySettings tickPolicy = new TickPolicySettings();
    private DamageSettings damage = new DamageSettings();

    public static AssetStore<String, TwNeedsConfig, DefaultAssetMap<String, TwNeedsConfig>> getAssetStore() {
        if (ASSET_STORE == null) {
            ASSET_STORE = AssetRegistry.getAssetStore(TwNeedsConfig.class);
        }
        return ASSET_STORE;
    }

    @Nullable
    public static DefaultAssetMap<String, TwNeedsConfig> getAssetMap() {
        AssetStore<String, TwNeedsConfig, DefaultAssetMap<String, TwNeedsConfig>> store = getAssetStore();
        if (store == null) {
            return null;
        }
        DefaultAssetMap<String, TwNeedsConfig> assetMap = (DefaultAssetMap<String, TwNeedsConfig>) store.getAssetMap();
        ensureInheritanceFallbackApplied(assetMap);
        return assetMap;
    }

    public static void clearRoleCache() {
        INHERITANCE_CACHE_DIRTY = true;
        ROLE_CACHE_DIRTY = true;
    }

    @Nullable
    public static TwNeedsConfig resolveForRole(@Nullable String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        DefaultAssetMap<String, TwNeedsConfig> assetMap = getAssetMap();
        if (assetMap == null) {
            return null;
        }
        Map<String, TwNeedsConfig> cache = ROLE_CACHE;
        if (ROLE_CACHE_DIRTY || cache == null) {
            synchronized (ROLE_CACHE_LOCK) {
                if (ROLE_CACHE_DIRTY || ROLE_CACHE == null) {
                    ROLE_CACHE = buildRoleCache(assetMap);
                    ROLE_CACHE_DIRTY = false;
                }
                cache = ROLE_CACHE;
            }
        }
        return cache.get(roleId.trim().toLowerCase(Locale.ROOT));
    }

    @Nullable
    public static TwNeedsConfig resolveById(@Nullable String configId) {
        if (configId == null || configId.isBlank()) {
            return null;
        }
        DefaultAssetMap<String, TwNeedsConfig> assetMap = getAssetMap();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return null;
        }
        Map<String, TwNeedsConfig> map = assetMap.getAssetMap();
        TwNeedsConfig direct = map.get(configId);
        if (direct != null) {
            return direct;
        }
        String normalized = configId.trim();
        for (TwNeedsConfig candidate : map.values()) {
            if (candidate == null || candidate.getId() == null) {
                continue;
            }
            if (candidate.getId().equalsIgnoreCase(normalized)) {
                return candidate;
            }
        }
        return null;
    }

    private static Map<String, TwNeedsConfig> buildRoleCache(@Nullable DefaultAssetMap<String, TwNeedsConfig> assetMap) {
        Map<String, TwNeedsConfig> cache = new HashMap<>();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return cache;
        }
        for (TwNeedsConfig candidate : assetMap.getAssetMap().values()) {
            if (candidate == null || !candidate.isEnabled()) {
                continue;
            }
            String[] candidateRoles = candidate.getRoleIds();
            if (candidateRoles == null || candidateRoles.length == 0) {
                continue;
            }
            for (String roleId : candidateRoles) {
                if (roleId == null || roleId.isBlank()) {
                    continue;
                }
                String normalizedRole = roleId.trim().toLowerCase(Locale.ROOT);
                TwNeedsConfig existing = cache.get(normalizedRole);
                if (shouldReplaceCandidate(candidate, existing)) {
                    cache.put(normalizedRole, candidate);
                }
            }
        }
        return cache;
    }

    private static boolean shouldReplaceCandidate(@Nullable TwNeedsConfig candidate,
                                                  @Nullable TwNeedsConfig existing) {
        if (candidate == null) {
            return false;
        }
        if (existing == null) {
            return true;
        }
        int candidatePriority = candidate.getPriority();
        int existingPriority = existing.getPriority();
        if (candidatePriority != existingPriority) {
            return candidatePriority > existingPriority;
        }
        return compareIds(candidate.getId(), existing.getId()) < 0;
    }

    private static int compareIds(@Nullable String left, @Nullable String right) {
        String safeLeft = left == null ? "" : left;
        String safeRight = right == null ? "" : right;
        return safeLeft.compareToIgnoreCase(safeRight);
    }

    private static void ensureInheritanceFallbackApplied(@Nullable DefaultAssetMap<String, TwNeedsConfig> assetMap) {
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
    public void inheritMissingTopLevelFrom(@Nonnull TwNeedsConfig parent, @Nonnull Set<String> explicitTopLevelKeys) {
        inheritMissingTopLevelFrom(parent, explicitTopLevelKeys, null);
    }

    @Override
    public void inheritMissingTopLevelFrom(@Nonnull TwNeedsConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys,
                                           @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        if (!explicitTopLevelKeys.contains("Enabled")) enabled = parent.enabled;
        if (!explicitTopLevelKeys.contains("Priority")) priority = parent.priority;
        if (!explicitTopLevelKeys.contains("RoleIds")) roleIds = parent.roleIds;
        if (!explicitTopLevelKeys.contains("Values")) {
            values = parent.values;
        } else {
            inheritValuesSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Values"));
        }
        if (!explicitTopLevelKeys.contains("Decay")) {
            decay = parent.decay;
        } else {
            inheritDecaySection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Decay"));
        }
        if (!explicitTopLevelKeys.contains("HappinessImpact")) {
            happinessImpact = parent.happinessImpact;
        } else {
            inheritHappinessImpactSection(
                    parent,
                    nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "HappinessImpact")
            );
        }
        if (!explicitTopLevelKeys.contains("PassiveRefill")) {
            passiveRefill = parent.passiveRefill;
        } else {
            inheritPassiveRefillSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "PassiveRefill"));
        }
        if (!explicitTopLevelKeys.contains("ManualRefill")) {
            manualRefill = parent.manualRefill;
        } else {
            inheritManualRefillSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "ManualRefill"));
        }
        if (!explicitTopLevelKeys.contains("Timing")) {
            timing = parent.timing;
        } else {
            inheritTimingSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Timing"));
        }
        if (!explicitTopLevelKeys.contains("TickPolicy")) {
            tickPolicy = parent.tickPolicy;
        } else {
            inheritTickPolicySection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "TickPolicy"));
        }
        if (!explicitTopLevelKeys.contains("Damage")) {
            damage = parent.damage;
        } else {
            inheritDamageSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Damage"));
        }
    }

    private void inheritValuesSection(@Nonnull TwNeedsConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (values == null) {
            values = parent.values;
            return;
        }
        if (parent.values == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("HungerDefault")) values.hungerDefault = parent.values.hungerDefault;
        if (!nestedExplicitKeys.contains("HungerMin")) values.hungerMin = parent.values.hungerMin;
        if (!nestedExplicitKeys.contains("HungerMax")) values.hungerMax = parent.values.hungerMax;
        if (!nestedExplicitKeys.contains("ThirstDefault")) values.thirstDefault = parent.values.thirstDefault;
        if (!nestedExplicitKeys.contains("ThirstMin")) values.thirstMin = parent.values.thirstMin;
        if (!nestedExplicitKeys.contains("ThirstMax")) values.thirstMax = parent.values.thirstMax;
    }

    private void inheritDecaySection(@Nonnull TwNeedsConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (decay == null) {
            decay = parent.decay;
            return;
        }
        if (parent.decay == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("HungerPerMinute")) {
            decay.hungerPerMinute = parent.decay.hungerPerMinute;
        }
        if (!nestedExplicitKeys.contains("ThirstPerMinute")) {
            decay.thirstPerMinute = parent.decay.thirstPerMinute;
        }
    }

    private void inheritHappinessImpactSection(@Nonnull TwNeedsConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (happinessImpact == null) {
            happinessImpact = parent.happinessImpact;
            return;
        }
        if (parent.happinessImpact == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("HungerPenaltyAtMin")) {
            happinessImpact.hungerPenaltyAtMin = parent.happinessImpact.hungerPenaltyAtMin;
        }
        if (!nestedExplicitKeys.contains("ThirstPenaltyAtMin")) {
            happinessImpact.thirstPenaltyAtMin = parent.happinessImpact.thirstPenaltyAtMin;
        }
        if (!nestedExplicitKeys.contains("PenaltyCurvePower")) {
            happinessImpact.penaltyCurvePower = parent.happinessImpact.penaltyCurvePower;
        }
    }

    private void inheritPassiveRefillSection(@Nonnull TwNeedsConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (passiveRefill == null) {
            passiveRefill = parent.passiveRefill;
            return;
        }
        if (parent.passiveRefill == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("SweepIntervalSeconds")) {
            passiveRefill.sweepIntervalSeconds = parent.passiveRefill.sweepIntervalSeconds;
        }
        if (!nestedExplicitKeys.contains("NearbyContainerFeedEnabled")) {
            passiveRefill.nearbyContainerFeedEnabled = parent.passiveRefill.nearbyContainerFeedEnabled;
        }
        if (!nestedExplicitKeys.contains("ContainerSearchRadius")) {
            passiveRefill.containerSearchRadius = parent.passiveRefill.containerSearchRadius;
        }
        if (!nestedExplicitKeys.contains("ContainerVerticalScanRadius")) {
            passiveRefill.containerVerticalScanRadius = parent.passiveRefill.containerVerticalScanRadius;
        }
        if (!nestedExplicitKeys.contains("ContainerConsumeRadius")) {
            passiveRefill.containerConsumeRadius = parent.passiveRefill.containerConsumeRadius;
        }
        if (!nestedExplicitKeys.contains("ContainerFoodItemIds")) {
            passiveRefill.containerFoodItemIds = parent.passiveRefill.containerFoodItemIds;
        }
        if (!nestedExplicitKeys.contains("HungerGainPerConsumedItem")) {
            passiveRefill.hungerGainPerConsumedItem = parent.passiveRefill.hungerGainPerConsumedItem;
        }
        if (!nestedExplicitKeys.contains("MaxContainerItemsConsumedPerSweep")) {
            passiveRefill.maxContainerItemsConsumedPerSweep = parent.passiveRefill.maxContainerItemsConsumedPerSweep;
        }
        if (!nestedExplicitKeys.contains("NearbyWaterDrinkEnabled")) {
            passiveRefill.nearbyWaterDrinkEnabled = parent.passiveRefill.nearbyWaterDrinkEnabled;
        }
        if (!nestedExplicitKeys.contains("WaterSearchRadius")) {
            passiveRefill.waterSearchRadius = parent.passiveRefill.waterSearchRadius;
        }
        if (!nestedExplicitKeys.contains("WaterVerticalScanRadius")) {
            passiveRefill.waterVerticalScanRadius = parent.passiveRefill.waterVerticalScanRadius;
        }
        if (!nestedExplicitKeys.contains("WaterConsumeRadius")) {
            passiveRefill.waterConsumeRadius = parent.passiveRefill.waterConsumeRadius;
        }
        if (!nestedExplicitKeys.contains("ThirstGainPerSweepNearWater")) {
            passiveRefill.thirstGainPerSweepNearWater = parent.passiveRefill.thirstGainPerSweepNearWater;
        }
    }

    private void inheritManualRefillSection(@Nonnull TwNeedsConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (manualRefill == null) {
            manualRefill = parent.manualRefill;
            return;
        }
        if (parent.manualRefill == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("HungerGainOnFeedInteraction")) {
            manualRefill.hungerGainOnFeedInteraction = parent.manualRefill.hungerGainOnFeedInteraction;
        }
        if (!nestedExplicitKeys.contains("ThirstGainOnWaterBucket")) {
            manualRefill.thirstGainOnWaterBucket = parent.manualRefill.thirstGainOnWaterBucket;
        }
        if (!nestedExplicitKeys.contains("WaterBucketItemIds")) {
            manualRefill.waterBucketItemIds = parent.manualRefill.waterBucketItemIds;
        }
    }

    private void inheritTimingSection(@Nonnull TwNeedsConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (timing == null) {
            timing = parent.timing;
            return;
        }
        if (parent.timing == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("Basis")) timing.timerBasis = parent.timing.timerBasis;
    }

    private void inheritTickPolicySection(@Nonnull TwNeedsConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (tickPolicy == null) {
            tickPolicy = parent.tickPolicy;
            return;
        }
        if (parent.tickPolicy == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("Mode")) tickPolicy.mode = parent.tickPolicy.mode;
        if (!nestedExplicitKeys.contains("OwnerOfflineGraceHours")) {
            tickPolicy.ownerOfflineGraceHours = parent.tickPolicy.ownerOfflineGraceHours;
        }
        if (!nestedExplicitKeys.contains("OwnerOfflineDecayMultiplier")) {
            tickPolicy.ownerOfflineDecayMultiplier = parent.tickPolicy.ownerOfflineDecayMultiplier;
        }
    }

    private void inheritDamageSection(@Nonnull TwNeedsConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (damage == null) {
            damage = parent.damage;
            return;
        }
        if (parent.damage == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("Enabled")) damage.enabled = parent.damage.enabled;
        if (!nestedExplicitKeys.contains("Model")) damage.model = parent.damage.model;
        if (!nestedExplicitKeys.contains("DualNeedRule")) damage.dualNeedRule = parent.damage.dualNeedRule;
        if (!nestedExplicitKeys.contains("StarvationDamagePerMinute")) {
            damage.starvationDamagePerMinute = parent.damage.starvationDamagePerMinute;
        }
        if (!nestedExplicitKeys.contains("DehydrationDamagePerMinute")) {
            damage.dehydrationDamagePerMinute = parent.damage.dehydrationDamagePerMinute;
        }
        if (!nestedExplicitKeys.contains("Lethal")) damage.lethal = parent.damage.lethal;
    }

    @Nullable
    private static Set<String> nestedKeysForTopLevel(@Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel,
                                                     @Nonnull String topLevelKey) {
        if (explicitNestedKeysByTopLevel == null) {
            return null;
        }
        return explicitNestedKeysByTopLevel.get(topLevelKey);
    }

    protected TwNeedsConfig() {
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

    public ValueSettings getValues() {
        return values == null ? new ValueSettings() : values;
    }

    public DecaySettings getDecay() {
        return decay == null ? new DecaySettings() : decay;
    }

    public HappinessImpactSettings getHappinessImpact() {
        return happinessImpact == null ? new HappinessImpactSettings() : happinessImpact;
    }

    public PassiveRefillSettings getPassiveRefill() {
        return passiveRefill == null ? new PassiveRefillSettings() : passiveRefill;
    }

    public ManualRefillSettings getManualRefill() {
        return manualRefill == null ? new ManualRefillSettings() : manualRefill;
    }

    public TimingSettings getTiming() {
        return timing == null ? new TimingSettings() : timing;
    }

    public TickPolicySettings getTickPolicy() {
        TickPolicySettings base = tickPolicy == null ? new TickPolicySettings() : tickPolicy;
        TameworkSettingsStore.GlobalOverrides overrides = resolveRuntimeOverrides();
        if (overrides == null) {
            return base;
        }
        TickPolicySettings merged = base.copy();
        if (overrides.needsTickPolicyMode() != null) {
            merged.mode = TickPolicyMode.fromConfigValue(overrides.needsTickPolicyMode());
        }
        if (overrides.needsOwnerOfflineGraceHours() != null) {
            merged.ownerOfflineGraceHours = overrides.needsOwnerOfflineGraceHours();
        }
        if (overrides.needsOwnerOfflineDecayMultiplier() != null) {
            merged.ownerOfflineDecayMultiplier = overrides.needsOwnerOfflineDecayMultiplier();
        }
        return merged;
    }

    public DamageSettings getDamage() {
        DamageSettings base = damage == null ? new DamageSettings() : damage;
        TameworkSettingsStore.GlobalOverrides overrides = resolveRuntimeOverrides();
        if (overrides == null) {
            return base;
        }
        DamageSettings merged = base.copy();
        if (overrides.needsDamageEnabled() != null) {
            merged.enabled = overrides.needsDamageEnabled();
        }
        if (overrides.needsDamageModel() != null) {
            merged.model = DamageModel.fromConfigValue(overrides.needsDamageModel());
        }
        if (overrides.needsDamageDualNeedRule() != null) {
            merged.dualNeedRule = DualNeedRule.fromConfigValue(overrides.needsDamageDualNeedRule());
        }
        if (overrides.needsStarvationDamagePerMinute() != null) {
            merged.starvationDamagePerMinute = overrides.needsStarvationDamagePerMinute();
        }
        if (overrides.needsDehydrationDamagePerMinute() != null) {
            merged.dehydrationDamagePerMinute = overrides.needsDehydrationDamagePerMinute();
        }
        if (overrides.needsDamageLethal() != null) {
            merged.lethal = overrides.needsDamageLethal();
        }
        return merged;
    }

    @Nullable
    private static TameworkSettingsStore.GlobalOverrides resolveRuntimeOverrides() {
        return TameworkSettingsStore.loadRuntimeGlobalOverrides();
    }

    /** Bounds and default values for hunger and thirst state. */
    public static final class ValueSettings {
        private double hungerDefault = 100.0;
        private double hungerMin = 0.0;
        private double hungerMax = 100.0;
        private double thirstDefault = 100.0;
        private double thirstMin = 0.0;
        private double thirstMax = 100.0;

        public double getHungerDefault() {
            return clamp(hungerDefault, getHungerMin(), getHungerMax());
        }

        public double getHungerMin() {
            return sanitizeMin(hungerMin);
        }

        public double getHungerMax() {
            return sanitizeMax(hungerMax, getHungerMin(), 100.0);
        }

        public double getThirstDefault() {
            return clamp(thirstDefault, getThirstMin(), getThirstMax());
        }

        public double getThirstMin() {
            return sanitizeMin(thirstMin);
        }

        public double getThirstMax() {
            return sanitizeMax(thirstMax, getThirstMin(), 100.0);
        }
    }

    /** Per-minute decay rates for hunger and thirst. */
    public static final class DecaySettings {
        private double hungerPerMinute = 2.0;
        private double thirstPerMinute = 3.0;

        public double getHungerPerMinute() {
            return sanitizeNonNegative(hungerPerMinute, 0.0);
        }

        public double getThirstPerMinute() {
            return sanitizeNonNegative(thirstPerMinute, 0.0);
        }
    }

    /** Happiness penalty curve generated from current hunger/thirst deficits. */
    public static final class HappinessImpactSettings {
        private double hungerPenaltyAtMin = 20.0;
        private double thirstPenaltyAtMin = 30.0;
        private double penaltyCurvePower = 1.0;

        public double getHungerPenaltyAtMin() {
            return sanitizeNonNegative(hungerPenaltyAtMin, 0.0);
        }

        public double getThirstPenaltyAtMin() {
            return sanitizeNonNegative(thirstPenaltyAtMin, 0.0);
        }

        public double getPenaltyCurvePower() {
            if (!Double.isFinite(penaltyCurvePower) || penaltyCurvePower <= 0.0) {
                return 1.0;
            }
            return penaltyCurvePower;
        }
    }

    /** Passive world-driven refill knobs (containers and nearby water). */
    public static final class PassiveRefillSettings {
        private int sweepIntervalSeconds = 15;
        private boolean nearbyContainerFeedEnabled = true;
        private double containerSearchRadius = 6.0;
        private int containerVerticalScanRadius = 2;
        private double containerConsumeRadius = 1.5;
        private String[] containerFoodItemIds = ArrayUtil.EMPTY_STRING_ARRAY;
        private double hungerGainPerConsumedItem = 25.0;
        private int maxContainerItemsConsumedPerSweep = 1;
        private boolean nearbyWaterDrinkEnabled = true;
        private double waterSearchRadius = 4.0;
        private int waterVerticalScanRadius = 1;
        private double waterConsumeRadius = 1.5;
        private double thirstGainPerSweepNearWater = 20.0;

        public int getSweepIntervalSeconds() {
            return Math.max(1, sweepIntervalSeconds);
        }

        public boolean isNearbyContainerFeedEnabled() {
            return nearbyContainerFeedEnabled;
        }

        public double getContainerSearchRadius() {
            return sanitizeNonNegative(containerSearchRadius, 6.0);
        }

        public int getContainerVerticalScanRadius() {
            return Math.max(0, containerVerticalScanRadius);
        }

        public double getContainerConsumeRadius() {
            return sanitizeNonNegative(containerConsumeRadius, 1.5);
        }

        public String[] getContainerFoodItemIds() {
            return containerFoodItemIds == null ? ArrayUtil.EMPTY_STRING_ARRAY : containerFoodItemIds;
        }

        public double getHungerGainPerConsumedItem() {
            return sanitizeNonNegative(hungerGainPerConsumedItem, 0.0);
        }

        public int getMaxContainerItemsConsumedPerSweep() {
            return Math.max(0, maxContainerItemsConsumedPerSweep);
        }

        public boolean isNearbyWaterDrinkEnabled() {
            return nearbyWaterDrinkEnabled;
        }

        public double getWaterSearchRadius() {
            return sanitizeNonNegative(waterSearchRadius, 4.0);
        }

        public int getWaterVerticalScanRadius() {
            return Math.max(0, waterVerticalScanRadius);
        }

        public double getWaterConsumeRadius() {
            return sanitizeNonNegative(waterConsumeRadius, 1.5);
        }

        public double getThirstGainPerSweepNearWater() {
            return sanitizeNonNegative(thirstGainPerSweepNearWater, 0.0);
        }
    }

    /** Player-driven refill knobs (feed interaction and water bucket use). */
    public static final class ManualRefillSettings {
        private double hungerGainOnFeedInteraction = 20.0;
        private double thirstGainOnWaterBucket = 30.0;
        private String[] waterBucketItemIds = ArrayUtil.EMPTY_STRING_ARRAY;

        public double getHungerGainOnFeedInteraction() {
            return sanitizeNonNegative(hungerGainOnFeedInteraction, 0.0);
        }

        public double getThirstGainOnWaterBucket() {
            return sanitizeNonNegative(thirstGainOnWaterBucket, 0.0);
        }

        public String[] getWaterBucketItemIds() {
            return waterBucketItemIds == null ? ArrayUtil.EMPTY_STRING_ARRAY : waterBucketItemIds;
        }
    }

    /** Controls which time basis is used for decay and passive refill intervals. */
    public static final class TimingSettings {
        private TimerBasis timerBasis = TimerBasis.REAL_TIME;

        public TimerBasis getTimerBasis() {
            return timerBasis == null ? TimerBasis.REAL_TIME : timerBasis;
        }
    }

    /** Owner-presence policy controlling how elapsed time contributes to needs progression. */
    public static final class TickPolicySettings {
        private TickPolicyMode mode = TickPolicyMode.OWNER_ONLINE_GRACE_THEN_DECAY;
        private double ownerOfflineGraceHours = 72.0;
        private double ownerOfflineDecayMultiplier = 1.0;

        public TickPolicyMode getMode() {
            return mode == null ? TickPolicyMode.OWNER_ONLINE_GRACE_THEN_DECAY : mode;
        }

        public double getOwnerOfflineGraceHours() {
            return sanitizeNonNegative(ownerOfflineGraceHours, 72.0);
        }

        public double getOwnerOfflineDecayMultiplier() {
            return sanitizeNonNegative(ownerOfflineDecayMultiplier, 1.0);
        }

        private TickPolicySettings copy() {
            TickPolicySettings copy = new TickPolicySettings();
            copy.mode = mode;
            copy.ownerOfflineGraceHours = ownerOfflineGraceHours;
            copy.ownerOfflineDecayMultiplier = ownerOfflineDecayMultiplier;
            return copy;
        }
    }

    /** Time-based hunger/thirst damage settings. */
    public static final class DamageSettings {
        private boolean enabled;
        private DamageModel model = DamageModel.MIN_ONLY_PERCENT;
        private DualNeedRule dualNeedRule = DualNeedRule.USE_HIGHER_ONLY;
        private double starvationDamagePerMinute = 2.0;
        private double dehydrationDamagePerMinute = 3.0;
        private boolean lethal = true;

        public boolean isEnabled() {
            return enabled;
        }

        public DamageModel getModel() {
            return model == null ? DamageModel.MIN_ONLY_PERCENT : model;
        }

        public DualNeedRule getDualNeedRule() {
            return dualNeedRule == null ? DualNeedRule.USE_HIGHER_ONLY : dualNeedRule;
        }

        public double getStarvationDamagePerMinute() {
            return sanitizeNonNegative(starvationDamagePerMinute, 2.0);
        }

        public double getDehydrationDamagePerMinute() {
            return sanitizeNonNegative(dehydrationDamagePerMinute, 3.0);
        }

        public boolean isLethal() {
            return lethal;
        }

        private DamageSettings copy() {
            DamageSettings copy = new DamageSettings();
            copy.enabled = enabled;
            copy.model = model;
            copy.dualNeedRule = dualNeedRule;
            copy.starvationDamagePerMinute = starvationDamagePerMinute;
            copy.dehydrationDamagePerMinute = dehydrationDamagePerMinute;
            copy.lethal = lethal;
            return copy;
        }
    }

    /** Duration basis for needs decay/refill timing. */
    public enum TimerBasis {
        REAL_TIME,
        WORLD_TIME_SCALED;

        public static TimerBasis fromConfigValue(@Nullable String value) {
            if (value == null || value.isBlank()) {
                return REAL_TIME;
            }
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            for (TimerBasis basis : values()) {
                if (basis.name().equals(normalized)) {
                    return basis;
                }
            }
            return REAL_TIME;
        }

        public String toConfigValue() {
            return name();
        }
    }

    /** Policy mode for owner-presence needs progression gating. */
    public enum TickPolicyMode {
        ANY_LOADED_PLAYER,
        OWNER_ONLINE_GRACE_THEN_DECAY;

        public static TickPolicyMode fromConfigValue(@Nullable String value) {
            if (value == null || value.isBlank()) {
                return OWNER_ONLINE_GRACE_THEN_DECAY;
            }
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            for (TickPolicyMode mode : values()) {
                if (mode.name().equals(normalized)) {
                    return mode;
                }
            }
            return OWNER_ONLINE_GRACE_THEN_DECAY;
        }

        public String toConfigValue() {
            return name();
        }
    }

    /** Damage application model when needs are depleted. */
    public enum DamageModel {
        MIN_ONLY_PERCENT,
        MIN_ONLY_FLAT;

        public static DamageModel fromConfigValue(@Nullable String value) {
            if (value == null || value.isBlank()) {
                return MIN_ONLY_PERCENT;
            }
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            for (DamageModel model : values()) {
                if (model.name().equals(normalized)) {
                    return model;
                }
            }
            return MIN_ONLY_PERCENT;
        }

        public String toConfigValue() {
            return name();
        }
    }

    /** Rule used when hunger and thirst both qualify for damage at the same tick. */
    public enum DualNeedRule {
        USE_HIGHER_ONLY,
        SUM_BOTH;

        public static DualNeedRule fromConfigValue(@Nullable String value) {
            if (value == null || value.isBlank()) {
                return USE_HIGHER_ONLY;
            }
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            for (DualNeedRule rule : values()) {
                if (rule.name().equals(normalized)) {
                    return rule;
                }
            }
            return USE_HIGHER_ONLY;
        }

        public String toConfigValue() {
            return name();
        }
    }

    private static double sanitizeMin(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return value;
    }

    private static double sanitizeMax(double value, double min, double fallback) {
        double safeValue = Double.isFinite(value) ? value : fallback;
        if (safeValue < min) {
            safeValue = min;
        }
        return safeValue;
    }

    private static double sanitizeNonNegative(double value, double fallback) {
        if (!Double.isFinite(value) || value < 0.0) {
            return fallback;
        }
        return value;
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}


