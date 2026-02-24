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
import javax.annotation.Nullable;

/**
 * Asset-backed companion happiness configuration for role-scoped happiness progression.
 * Stored under Server/Tamework/Happiness.
 */
public final class TwHappinessConfig implements JsonAssetWithMap<String, DefaultAssetMap<String, TwHappinessConfig>> {
    private static final BuilderCodec<ValueSettings> VALUE_CODEC = BuilderCodec.builder(
            ValueSettings.class,
            ValueSettings::new
    )
        .<Double>append(
            new KeyedCodec<>("CurrentDefault", Codec.DOUBLE),
            (settings, value) -> settings.currentDefault = value,
            settings -> settings.currentDefault
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("Min", Codec.DOUBLE),
            (settings, value) -> settings.min = value,
            settings -> settings.min
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("Max", Codec.DOUBLE),
            (settings, value) -> settings.max = value,
            settings -> settings.max
        )
        .add()
        .build();

    private static final BuilderCodec<SourceSettings> SOURCE_CODEC = BuilderCodec.builder(
            SourceSettings.class,
            SourceSettings::new
    )
        .<Double>append(
            new KeyedCodec<>("DecayPerMinute", Codec.DOUBLE),
            (settings, value) -> settings.decayPerMinute = value,
            settings -> settings.decayPerMinute
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("GainOnFeed", Codec.DOUBLE),
            (settings, value) -> settings.gainOnFeed = value,
            settings -> settings.gainOnFeed
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("GainOnPet", Codec.DOUBLE),
            (settings, value) -> settings.gainOnPet = value,
            settings -> settings.gainOnPet
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("GainPerMinuteNearOwner", Codec.DOUBLE),
            (settings, value) -> settings.gainPerMinuteNearOwner = value,
            settings -> settings.gainPerMinuteNearOwner
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("LoseOnDamage", Codec.DOUBLE),
            (settings, value) -> settings.loseOnDamage = value,
            settings -> settings.loseOnDamage
        )
        .add()
        .build();

    public static final AssetBuilderCodec<String, TwHappinessConfig> CODEC = AssetBuilderCodec.builder(
            TwHappinessConfig.class,
            TwHappinessConfig::new,
            Codec.STRING,
            (asset, id) -> asset.id = id,
            asset -> asset.id,
            (asset, data) -> asset.data = data,
            asset -> asset.data
    )
        .documentation("Shared happiness configuration for Alec's Tamework companions.")
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
        .<SourceSettings>append(
            new KeyedCodec<>("Sources", SOURCE_CODEC),
            (asset, value) -> asset.sources = value == null ? new SourceSettings() : value,
            asset -> asset.sources
        )
        .add()
        .build();

    private static AssetStore<String, TwHappinessConfig, DefaultAssetMap<String, TwHappinessConfig>> ASSET_STORE;
    private static final Object ROLE_CACHE_LOCK = new Object();
    private static volatile boolean ROLE_CACHE_DIRTY = true;
    private static volatile Map<String, TwHappinessConfig> ROLE_CACHE = Map.of();

    private AssetExtraInfo.Data data;
    private String id;
    private boolean enabled = true;
    private int priority;
    private String[] roleIds = ArrayUtil.EMPTY_STRING_ARRAY;
    private ValueSettings values = new ValueSettings();
    private SourceSettings sources = new SourceSettings();

    public static AssetStore<String, TwHappinessConfig, DefaultAssetMap<String, TwHappinessConfig>> getAssetStore() {
        if (ASSET_STORE == null) {
            ASSET_STORE = AssetRegistry.getAssetStore(TwHappinessConfig.class);
        }
        return ASSET_STORE;
    }

    @Nullable
    public static DefaultAssetMap<String, TwHappinessConfig> getAssetMap() {
        AssetStore<String, TwHappinessConfig, DefaultAssetMap<String, TwHappinessConfig>> store = getAssetStore();
        if (store == null) {
            return null;
        }
        return (DefaultAssetMap<String, TwHappinessConfig>) store.getAssetMap();
    }

    public static void clearRoleCache() {
        ROLE_CACHE_DIRTY = true;
    }

    @Nullable
    public static TwHappinessConfig resolveForRole(@Nullable String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        DefaultAssetMap<String, TwHappinessConfig> assetMap = getAssetMap();
        if (assetMap == null) {
            return null;
        }
        Map<String, TwHappinessConfig> cache = ROLE_CACHE;
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
    public static TwHappinessConfig resolveById(@Nullable String configId) {
        if (configId == null || configId.isBlank()) {
            return null;
        }
        DefaultAssetMap<String, TwHappinessConfig> assetMap = getAssetMap();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return null;
        }
        Map<String, TwHappinessConfig> map = assetMap.getAssetMap();
        TwHappinessConfig direct = map.get(configId);
        if (direct != null) {
            return direct;
        }
        String normalized = configId.trim();
        for (TwHappinessConfig candidate : map.values()) {
            if (candidate == null || candidate.getId() == null) {
                continue;
            }
            if (candidate.getId().equalsIgnoreCase(normalized)) {
                return candidate;
            }
        }
        return null;
    }

    private static Map<String, TwHappinessConfig> buildRoleCache(
            @Nullable DefaultAssetMap<String, TwHappinessConfig> assetMap) {
        Map<String, TwHappinessConfig> cache = new HashMap<>();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return cache;
        }
        for (TwHappinessConfig candidate : assetMap.getAssetMap().values()) {
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
                TwHappinessConfig existing = cache.get(normalizedRole);
                if (shouldReplaceCandidate(candidate, existing)) {
                    cache.put(normalizedRole, candidate);
                }
            }
        }
        return cache;
    }

    private static boolean shouldReplaceCandidate(@Nullable TwHappinessConfig candidate,
                                                  @Nullable TwHappinessConfig existing) {
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

    protected TwHappinessConfig() {
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

    public SourceSettings getSources() {
        return sources == null ? new SourceSettings() : sources;
    }

    /** Bounds and default value for shared happiness state. */
    public static final class ValueSettings {
        private double currentDefault = 50.0;
        private double min = 0.0;
        private double max = 100.0;

        public double getCurrentDefault() {
            return currentDefault;
        }

        public double getMin() {
            return min;
        }

        public double getMax() {
            return max;
        }
    }

    /** Event/source knobs that mutate companion happiness over time. */
    public static final class SourceSettings {
        private double decayPerMinute = 1.0;
        private double gainOnFeed = 5.0;
        private double gainOnPet = 3.0;
        private double gainPerMinuteNearOwner = 1.0;
        private double loseOnDamage = 10.0;

        public double getDecayPerMinute() {
            return decayPerMinute;
        }

        public double getGainOnFeed() {
            return gainOnFeed;
        }

        public double getGainOnPet() {
            return gainOnPet;
        }

        public double getGainPerMinuteNearOwner() {
            return gainPerMinuteNearOwner;
        }

        public double getLoseOnDamage() {
            return loseOnDamage;
        }
    }
}
