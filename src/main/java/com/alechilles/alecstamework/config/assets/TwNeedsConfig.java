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
        .add()
        .<Double>append(
            new KeyedCodec<>("HungerMin", Codec.DOUBLE),
            (settings, value) -> settings.hungerMin = value,
            settings -> settings.hungerMin
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("HungerMax", Codec.DOUBLE),
            (settings, value) -> settings.hungerMax = value,
            settings -> settings.hungerMax
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("ThirstDefault", Codec.DOUBLE),
            (settings, value) -> settings.thirstDefault = value,
            settings -> settings.thirstDefault
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("ThirstMin", Codec.DOUBLE),
            (settings, value) -> settings.thirstMin = value,
            settings -> settings.thirstMin
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("ThirstMax", Codec.DOUBLE),
            (settings, value) -> settings.thirstMax = value,
            settings -> settings.thirstMax
        )
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
        .add()
        .<Double>append(
            new KeyedCodec<>("ThirstPerMinute", Codec.DOUBLE),
            (settings, value) -> settings.thirstPerMinute = value,
            settings -> settings.thirstPerMinute
        )
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
        .add()
        .<Double>append(
            new KeyedCodec<>("ThirstPenaltyAtMin", Codec.DOUBLE),
            (settings, value) -> settings.thirstPenaltyAtMin = value,
            settings -> settings.thirstPenaltyAtMin
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("PenaltyCurvePower", Codec.DOUBLE),
            (settings, value) -> settings.penaltyCurvePower = value,
            settings -> settings.penaltyCurvePower
        )
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
        .add()
        .<Boolean>append(
            new KeyedCodec<>("NearbyContainerFeedEnabled", Codec.BOOLEAN),
            (settings, value) -> settings.nearbyContainerFeedEnabled = value,
            settings -> settings.nearbyContainerFeedEnabled
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("ContainerSearchRadius", Codec.DOUBLE),
            (settings, value) -> settings.containerSearchRadius = value,
            settings -> settings.containerSearchRadius
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("ContainerConsumeRadius", Codec.DOUBLE),
            (settings, value) -> settings.containerConsumeRadius = value,
            settings -> settings.containerConsumeRadius
        )
        .add()
        .<String[]>append(
            new KeyedCodec<>("ContainerFoodItemIds", Codec.STRING_ARRAY),
            (settings, value) -> settings.containerFoodItemIds = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            settings -> settings.containerFoodItemIds
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("HungerGainPerConsumedItem", Codec.DOUBLE),
            (settings, value) -> settings.hungerGainPerConsumedItem = value,
            settings -> settings.hungerGainPerConsumedItem
        )
        .add()
        .<Integer>append(
            new KeyedCodec<>("MaxContainerItemsConsumedPerSweep", Codec.INTEGER),
            (settings, value) -> settings.maxContainerItemsConsumedPerSweep = value,
            settings -> settings.maxContainerItemsConsumedPerSweep
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("NearbyWaterDrinkEnabled", Codec.BOOLEAN),
            (settings, value) -> settings.nearbyWaterDrinkEnabled = value,
            settings -> settings.nearbyWaterDrinkEnabled
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("WaterSearchRadius", Codec.DOUBLE),
            (settings, value) -> settings.waterSearchRadius = value,
            settings -> settings.waterSearchRadius
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("WaterConsumeRadius", Codec.DOUBLE),
            (settings, value) -> settings.waterConsumeRadius = value,
            settings -> settings.waterConsumeRadius
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("ThirstGainPerSweepNearWater", Codec.DOUBLE),
            (settings, value) -> settings.thirstGainPerSweepNearWater = value,
            settings -> settings.thirstGainPerSweepNearWater
        )
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
        .add()
        .<Double>append(
            new KeyedCodec<>("ThirstGainOnWaterBucket", Codec.DOUBLE),
            (settings, value) -> settings.thirstGainOnWaterBucket = value,
            settings -> settings.thirstGainOnWaterBucket
        )
        .add()
        .<String[]>append(
            new KeyedCodec<>("WaterBucketItemIds", Codec.STRING_ARRAY),
            (settings, value) -> settings.waterBucketItemIds = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            settings -> settings.waterBucketItemIds
        )
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
        .add()
        .<Integer>append(
            new KeyedCodec<>("Priority", Codec.INTEGER),
            (asset, value) -> asset.priority = value == null ? 0 : value,
            asset -> asset.priority
        )
        .add()
        .<String[]>append(
            new KeyedCodec<>("RoleIds", Codec.STRING_ARRAY),
            (asset, value) -> asset.roleIds = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            asset -> asset.roleIds
        )
        .add()
        .<ValueSettings>append(
            new KeyedCodec<>("Values", VALUE_CODEC),
            (asset, value) -> asset.values = value == null ? new ValueSettings() : value,
            asset -> asset.values
        )
        .add()
        .<DecaySettings>append(
            new KeyedCodec<>("Decay", DECAY_CODEC),
            (asset, value) -> asset.decay = value == null ? new DecaySettings() : value,
            asset -> asset.decay
        )
        .add()
        .<HappinessImpactSettings>append(
            new KeyedCodec<>("HappinessImpact", HAPPINESS_IMPACT_CODEC),
            (asset, value) -> asset.happinessImpact = value == null ? new HappinessImpactSettings() : value,
            asset -> asset.happinessImpact
        )
        .add()
        .<PassiveRefillSettings>append(
            new KeyedCodec<>("PassiveRefill", PASSIVE_REFILL_CODEC),
            (asset, value) -> asset.passiveRefill = value == null ? new PassiveRefillSettings() : value,
            asset -> asset.passiveRefill
        )
        .add()
        .<ManualRefillSettings>append(
            new KeyedCodec<>("ManualRefill", MANUAL_REFILL_CODEC),
            (asset, value) -> asset.manualRefill = value == null ? new ManualRefillSettings() : value,
            asset -> asset.manualRefill
        )
        .add()
        .<TimingSettings>append(
            new KeyedCodec<>("Timing", TIMING_CODEC),
            (asset, value) -> asset.timing = value == null ? new TimingSettings() : value,
            asset -> asset.timing
        )
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
        if (!explicitTopLevelKeys.contains("Enabled")) enabled = parent.enabled;
        if (!explicitTopLevelKeys.contains("Priority")) priority = parent.priority;
        if (!explicitTopLevelKeys.contains("RoleIds")) roleIds = parent.roleIds;
        if (!explicitTopLevelKeys.contains("Values")) values = parent.values;
        if (!explicitTopLevelKeys.contains("Decay")) decay = parent.decay;
        if (!explicitTopLevelKeys.contains("HappinessImpact")) happinessImpact = parent.happinessImpact;
        if (!explicitTopLevelKeys.contains("PassiveRefill")) passiveRefill = parent.passiveRefill;
        if (!explicitTopLevelKeys.contains("ManualRefill")) manualRefill = parent.manualRefill;
        if (!explicitTopLevelKeys.contains("Timing")) timing = parent.timing;
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
        private double containerConsumeRadius = 1.5;
        private String[] containerFoodItemIds = ArrayUtil.EMPTY_STRING_ARRAY;
        private double hungerGainPerConsumedItem = 25.0;
        private int maxContainerItemsConsumedPerSweep = 1;
        private boolean nearbyWaterDrinkEnabled = true;
        private double waterSearchRadius = 4.0;
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
