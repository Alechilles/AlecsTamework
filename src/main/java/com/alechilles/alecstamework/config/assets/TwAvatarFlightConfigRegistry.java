package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Owns avatar-flight config-store lookup, inheritance repair, and active-profile caching.
 */
final class TwAvatarFlightConfigRegistry {
    private static final Object CACHE_LOCK = new Object();
    private static final Object INHERITANCE_CACHE_LOCK = new Object();

    private static AssetStore<String, TwAvatarFlightConfig, DefaultAssetMap<String, TwAvatarFlightConfig>> assetStore;
    private static volatile boolean cacheDirty = true;
    private static volatile boolean inheritanceCacheDirty = true;
    private static volatile TwAvatarFlightConfig activeConfig;

    private TwAvatarFlightConfigRegistry() {
    }

    @Nullable
    static AssetStore<String, TwAvatarFlightConfig, DefaultAssetMap<String, TwAvatarFlightConfig>> getAssetStore() {
        if (assetStore == null) {
            assetStore = AssetRegistry.getAssetStore(TwAvatarFlightConfig.class);
        }
        return assetStore;
    }

    @Nullable
    static DefaultAssetMap<String, TwAvatarFlightConfig> getAssetMap() {
        AssetStore<String, TwAvatarFlightConfig, DefaultAssetMap<String, TwAvatarFlightConfig>> store = getAssetStore();
        if (store == null) return null;
        DefaultAssetMap<String, TwAvatarFlightConfig> assetMap =
                (DefaultAssetMap<String, TwAvatarFlightConfig>) store.getAssetMap();
        ensureInheritanceFallbackApplied(assetMap);
        return assetMap;
    }

    static void clearCache() {
        cacheDirty = true;
        inheritanceCacheDirty = true;
    }

    @Nonnull
    static TwAvatarFlightConfig resolveActive() {
        DefaultAssetMap<String, TwAvatarFlightConfig> assetMap = getAssetMap();
        if (assetMap == null || assetMap.getAssetMap() == null) return TwAvatarFlightConfig.defaultConfig();
        TwAvatarFlightConfig cached = activeConfig;
        if (cacheDirty || cached == null) {
            synchronized (CACHE_LOCK) {
                if (cacheDirty || activeConfig == null) {
                    activeConfig = selectBest(assetMap.getAssetMap().values());
                    cacheDirty = false;
                }
                cached = activeConfig;
            }
        }
        return cached == null ? TwAvatarFlightConfig.defaultConfig() : cached;
    }

    @Nonnull
    static TwAvatarFlightConfig resolve(@Nullable String configId) {
        if (configId == null || configId.isBlank()) return resolveActive();
        DefaultAssetMap<String, TwAvatarFlightConfig> assetMap = getAssetMap();
        if (assetMap == null || assetMap.getAssetMap() == null) return TwAvatarFlightConfig.defaultConfig();
        TwAvatarFlightConfig direct = assetMap.getAssetMap().get(configId);
        if (direct != null && direct.isEnabled()) return direct;
        for (TwAvatarFlightConfig candidate : assetMap.getAssetMap().values()) {
            if (candidate != null && candidate.isEnabled()
                    && candidate.getId() != null && candidate.getId().equalsIgnoreCase(configId.trim())) {
                return candidate;
            }
        }
        return resolveActive();
    }

    @Nullable
    private static TwAvatarFlightConfig selectBest(@Nullable Iterable<TwAvatarFlightConfig> candidates) {
        TwAvatarFlightConfig best = null;
        if (candidates == null) return null;
        for (TwAvatarFlightConfig candidate : candidates) {
            if (candidate == null || !candidate.isEnabled()) continue;
            if (best == null || candidate.getPriority() > best.getPriority()
                    || (candidate.getPriority() == best.getPriority()
                    && safe(candidate.getId()).compareToIgnoreCase(safe(best.getId())) < 0)) {
                best = candidate;
            }
        }
        return best;
    }

    private static void ensureInheritanceFallbackApplied(@Nullable DefaultAssetMap<String, TwAvatarFlightConfig> assetMap) {
        if (!inheritanceCacheDirty || assetMap == null || assetMap.getAssetMap() == null) return;
        synchronized (INHERITANCE_CACHE_LOCK) {
            if (!inheritanceCacheDirty || assetMap.getAssetMap() == null) return;
            TwAssetInheritanceFallback.repairAll(assetMap);
            inheritanceCacheDirty = false;
        }
    }

    private static String safe(@Nullable String value) {
        return value == null ? "" : value;
    }
}
