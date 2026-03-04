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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Asset-backed coop intake integration config for Tamework capture policy overlays.
 * Stored under {@code Server/Tamework/Farming/Coops}.
 */
public final class TwCoopConfig implements JsonAssetWithMap<String, DefaultAssetMap<String, TwCoopConfig>>,
        TwParentFallbackAsset<TwCoopConfig> {
    private static final BuilderCodec<CapturePolicySettings> CAPTURE_POLICY_CODEC = BuilderCodec.builder(
            CapturePolicySettings.class,
            CapturePolicySettings::new
    )
            .<Boolean>append(
                    new KeyedCodec<>("RequireTamed", Codec.BOOLEAN),
                    (settings, value) -> settings.requireTamed = value,
                    settings -> settings.requireTamed
            )
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("OwnerRestricted", Codec.BOOLEAN),
                    (settings, value) -> settings.ownerRestricted = value,
                    settings -> settings.ownerRestricted
            )
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("RequireOwner", Codec.BOOLEAN),
                    (settings, value) -> settings.requireOwner = value,
                    settings -> settings.requireOwner
            )
            .add()
            .<String>append(
                    new KeyedCodec<>("ParticleSystem", Codec.STRING),
                    (settings, value) -> settings.particleSystem = value,
                    settings -> settings.particleSystem
            )
            .add()
            .<String>append(
                    new KeyedCodec<>("SoundEvent", Codec.STRING),
                    (settings, value) -> settings.soundEvent = value,
                    settings -> settings.soundEvent
            )
            .add()
            .build();

    public static final AssetBuilderCodec<String, TwCoopConfig> CODEC = AssetBuilderCodec.builder(
            TwCoopConfig.class,
            TwCoopConfig::new,
            Codec.STRING,
            (asset, id) -> asset.id = id,
            asset -> asset.id,
            (asset, data) -> asset.data = data,
            asset -> asset.data
    )
            .documentation("Coop integration policy settings for Alec's Tamework.")
            .<Boolean>append(
                    new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (asset, value) -> asset.enabled = value,
                    asset -> asset.enabled
            )
            .documentation("Enables this coop integration config.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("Priority", Codec.INTEGER),
                    (asset, value) -> asset.priority = value,
                    asset -> asset.priority
            )
            .documentation("Priority when multiple configs target the same coop id.")
            .add()
            .<String>append(
                    new KeyedCodec<>("CoopId", Codec.STRING),
                    (asset, value) -> asset.coopId = value,
                    asset -> asset.coopId
            )
            .documentation("Farming coop asset id to bind this config to.")
            .add()
            .<CapturePolicySettings>append(
                    new KeyedCodec<>("CapturePolicy", CAPTURE_POLICY_CODEC),
                    (asset, value) -> asset.capturePolicy = value == null ? new CapturePolicySettings() : value,
                    asset -> asset.capturePolicy
            )
            .documentation("Additional Tamework capture intake policy for this coop.")
            .add()
            .build();

    private static AssetStore<String, TwCoopConfig, DefaultAssetMap<String, TwCoopConfig>> ASSET_STORE;
    private static final Object INHERITANCE_CACHE_LOCK = new Object();
    private static volatile boolean INHERITANCE_CACHE_DIRTY = true;
    private static final Object COOP_CACHE_LOCK = new Object();
    private static volatile boolean COOP_CACHE_DIRTY = true;
    private static volatile Map<String, TwCoopConfig> COOP_CACHE = Map.of();

    private AssetExtraInfo.Data data;
    private String id;
    private boolean enabled = true;
    private int priority;
    private String coopId;
    private CapturePolicySettings capturePolicy = new CapturePolicySettings();

    public static AssetStore<String, TwCoopConfig, DefaultAssetMap<String, TwCoopConfig>> getAssetStore() {
        if (ASSET_STORE == null) {
            ASSET_STORE = AssetRegistry.getAssetStore(TwCoopConfig.class);
        }
        return ASSET_STORE;
    }

    @Nullable
    public static DefaultAssetMap<String, TwCoopConfig> getAssetMap() {
        AssetStore<String, TwCoopConfig, DefaultAssetMap<String, TwCoopConfig>> store = getAssetStore();
        if (store == null) {
            return null;
        }
        DefaultAssetMap<String, TwCoopConfig> assetMap = (DefaultAssetMap<String, TwCoopConfig>) store.getAssetMap();
        ensureInheritanceFallbackApplied(assetMap);
        return assetMap;
    }

    public static void clearCoopCache() {
        INHERITANCE_CACHE_DIRTY = true;
        COOP_CACHE_DIRTY = true;
    }

    @Nullable
    public static TwCoopConfig resolveForCoop(@Nullable String coopId) {
        String key = normalizeIdentifier(coopId);
        if (key == null) {
            return null;
        }
        DefaultAssetMap<String, TwCoopConfig> assetMap = getAssetMap();
        if (assetMap == null) {
            return null;
        }
        Map<String, TwCoopConfig> cache = COOP_CACHE;
        if (COOP_CACHE_DIRTY || cache == null) {
            synchronized (COOP_CACHE_LOCK) {
                if (COOP_CACHE_DIRTY || COOP_CACHE == null) {
                    COOP_CACHE = buildCoopCache(assetMap);
                    COOP_CACHE_DIRTY = false;
                }
                cache = COOP_CACHE;
            }
        }
        return cache.get(key);
    }

    @Nullable
    public static TwCoopConfig resolveById(@Nullable String configId) {
        if (configId == null || configId.isBlank()) {
            return null;
        }
        DefaultAssetMap<String, TwCoopConfig> assetMap = getAssetMap();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return null;
        }
        Map<String, TwCoopConfig> map = assetMap.getAssetMap();
        TwCoopConfig direct = map.get(configId);
        if (direct != null) {
            return direct;
        }
        for (TwCoopConfig candidate : map.values()) {
            if (candidate == null || candidate.getId() == null) {
                continue;
            }
            if (candidate.getId().equalsIgnoreCase(configId)) {
                return candidate;
            }
        }
        return null;
    }

    private static Map<String, TwCoopConfig> buildCoopCache(DefaultAssetMap<String, TwCoopConfig> assetMap) {
        Map<String, TwCoopConfig> cache = new HashMap<>();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return cache;
        }
        for (TwCoopConfig candidate : assetMap.getAssetMap().values()) {
            if (candidate == null || !candidate.isEnabled()) {
                continue;
            }
            String key = normalizeIdentifier(candidate.getCoopId());
            if (key == null) {
                continue;
            }
            TwCoopConfig existing = cache.get(key);
            if (existing == null || shouldPreferCandidate(candidate, existing)) {
                cache.put(key, candidate);
            }
        }
        return cache;
    }

    private static boolean shouldPreferCandidate(@Nonnull TwCoopConfig candidate,
                                                 @Nonnull TwCoopConfig existing) {
        int candidatePriority = candidate.getPriority();
        int existingPriority = existing.getPriority();
        if (candidatePriority != existingPriority) {
            return candidatePriority > existingPriority;
        }
        String candidateId = candidate.getId();
        String existingId = existing.getId();
        if (candidateId == null && existingId == null) {
            return false;
        }
        if (candidateId == null) {
            return false;
        }
        if (existingId == null) {
            return true;
        }
        return candidateId.compareToIgnoreCase(existingId) < 0;
    }

    private static void ensureInheritanceFallbackApplied(@Nullable DefaultAssetMap<String, TwCoopConfig> assetMap) {
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

    @Nullable
    private static String normalizeIdentifier(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    protected TwCoopConfig() {
    }

    public String getId() {
        return id;
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
    public void inheritMissingTopLevelFrom(@Nonnull TwCoopConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys) {
        if (!explicitTopLevelKeys.contains("Enabled")) enabled = parent.enabled;
        if (!explicitTopLevelKeys.contains("Priority")) priority = parent.priority;
        if (!explicitTopLevelKeys.contains("CoopId")) coopId = parent.coopId;
        if (!explicitTopLevelKeys.contains("CapturePolicy")) capturePolicy = parent.capturePolicy;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getPriority() {
        return priority;
    }

    public String getCoopId() {
        return coopId;
    }

    public CapturePolicySettings getCapturePolicy() {
        return capturePolicy == null ? new CapturePolicySettings() : capturePolicy;
    }

    /** Additional intake checks/effects applied before vanilla coop admission. */
    public static final class CapturePolicySettings {
        private boolean requireTamed;
        private boolean ownerRestricted;
        private boolean requireOwner;
        private String particleSystem;
        private String soundEvent;

        public boolean isRequireTamed() {
            return requireTamed;
        }

        public boolean isOwnerRestricted() {
            return ownerRestricted;
        }

        public boolean isRequireOwner() {
            return requireOwner;
        }

        public String getParticleSystem() {
            return particleSystem;
        }

        public String getSoundEvent() {
            return soundEvent;
        }
    }
}
