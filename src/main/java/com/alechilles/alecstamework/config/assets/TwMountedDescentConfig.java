package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.npc.movement.NativeMountedDescentPhysics;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Native mounted-descent settings keyed by source {@code MovementConfig} ID.
 *
 * <p>Stored under {@code Server/Tamework/Mounts/Descent}.
 */
public final class TwMountedDescentConfig
        implements JsonAssetWithMap<String, DefaultAssetMap<String, TwMountedDescentConfig>>,
        TwParentFallbackAsset<TwMountedDescentConfig> {
    public static final AssetBuilderCodec<String, TwMountedDescentConfig> CODEC =
            TwMountedDescentConfigCodec.CODEC;

    private static AssetStore<String, TwMountedDescentConfig,
            DefaultAssetMap<String, TwMountedDescentConfig>> ASSET_STORE;
    private static final Object INHERITANCE_CACHE_LOCK = new Object();
    private static volatile boolean INHERITANCE_CACHE_DIRTY = true;
    private static final Object PROFILE_CACHE_LOCK = new Object();
    private static volatile boolean PROFILE_CACHE_DIRTY = true;
    private static volatile Map<String, NativeMountedDescentPhysics.Settings> PROFILE_CACHE = Map.of();

    private AssetExtraInfo.Data data;
    private String id;
    private boolean enabled = true;
    private Map<String, NativeMountedDescentPhysics.Settings> profiles = Map.of();

    TwMountedDescentConfig() {
    }

    public static AssetStore<String, TwMountedDescentConfig,
            DefaultAssetMap<String, TwMountedDescentConfig>> getAssetStore() {
        if (ASSET_STORE == null) {
            ASSET_STORE = AssetRegistry.getAssetStore(TwMountedDescentConfig.class);
        }
        return ASSET_STORE;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public static DefaultAssetMap<String, TwMountedDescentConfig> getAssetMap() {
        AssetStore<String, TwMountedDescentConfig, DefaultAssetMap<String, TwMountedDescentConfig>> store =
                getAssetStore();
        if (store == null) {
            return null;
        }
        DefaultAssetMap<String, TwMountedDescentConfig> assetMap =
                (DefaultAssetMap<String, TwMountedDescentConfig>) store.getAssetMap();
        ensureInheritanceFallbackApplied(assetMap);
        return assetMap;
    }

    /** Clears cached profile selection after an asset load, removal, or reload. */
    public static void clearProfileCache() {
        INHERITANCE_CACHE_DIRTY = true;
        PROFILE_CACHE_DIRTY = true;
    }

    /** Resolves validated settings for the configured native movement profile, if any. */
    @Nonnull
    public static Optional<NativeMountedDescentPhysics.Settings> resolveForMovementConfigId(
            @Nullable String movementConfigId) {
        String normalizedProfileId = normalizeKey(movementConfigId);
        if (normalizedProfileId.isEmpty()) {
            return Optional.empty();
        }
        DefaultAssetMap<String, TwMountedDescentConfig> assetMap = getAssetMap();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return Optional.empty();
        }
        Map<String, NativeMountedDescentPhysics.Settings> cache = PROFILE_CACHE;
        if (PROFILE_CACHE_DIRTY || cache == null) {
            synchronized (PROFILE_CACHE_LOCK) {
                if (PROFILE_CACHE_DIRTY || PROFILE_CACHE == null) {
                    PROFILE_CACHE = buildProfileCache(assetMap.getAssetMap().values());
                    PROFILE_CACHE_DIRTY = false;
                }
                cache = PROFILE_CACHE;
            }
        }
        return Optional.ofNullable(cache.get(normalizedProfileId));
    }

    static Optional<NativeMountedDescentPhysics.Settings> resolveForMovementConfigIdForTest(
            @Nonnull List<TwMountedDescentConfig> configs,
            @Nullable String movementConfigId) {
        return Optional.ofNullable(buildProfileCache(configs).get(normalizeKey(movementConfigId)));
    }

    @Nonnull
    private static Map<String, NativeMountedDescentPhysics.Settings> buildProfileCache(
            @Nullable Iterable<TwMountedDescentConfig> configs) {
        if (configs == null) {
            return Map.of();
        }
        List<TwMountedDescentConfig> orderedConfigs = new ArrayList<>();
        for (TwMountedDescentConfig config : configs) {
            if (config != null && config.enabled) {
                orderedConfigs.add(config);
            }
        }
        orderedConfigs.sort(Comparator.comparing(config -> normalizeKey(config.id)));
        Map<String, NativeMountedDescentPhysics.Settings> resolved = new HashMap<>();
        for (TwMountedDescentConfig config : orderedConfigs) {
            for (Map.Entry<String, NativeMountedDescentPhysics.Settings> entry : config.profiles.entrySet()) {
                String profileId = normalizeKey(entry.getKey());
                NativeMountedDescentPhysics.Settings settings = entry.getValue();
                if (!profileId.isEmpty() && settings != null && settings.isValid()) {
                    resolved.putIfAbsent(profileId, settings);
                }
            }
        }
        return Map.copyOf(resolved);
    }

    private static void ensureInheritanceFallbackApplied(
            @Nullable DefaultAssetMap<String, TwMountedDescentConfig> assetMap) {
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
    public void inheritMissingTopLevelFrom(@Nonnull TwMountedDescentConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys) {
        if (!explicitTopLevelKeys.contains("Enabled")) {
            enabled = parent.enabled;
        }
        if (!explicitTopLevelKeys.contains("Profiles")) {
            profiles = parent.profiles;
        }
    }

    @Nullable
    @Override
    public String getId() {
        return id;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Nonnull
    public Map<String, NativeMountedDescentPhysics.Settings> getProfiles() {
        return profiles;
    }

    void setId(@Nullable String value) {
        id = value;
    }

    void setData(@Nullable AssetExtraInfo.Data value) {
        data = value;
    }

    @Nullable
    AssetExtraInfo.Data getData() {
        return data;
    }

    void setEnabled(@Nullable Boolean value) {
        enabled = value == null || value;
    }

    void setProfiles(@Nullable Map<String, NativeMountedDescentPhysics.Settings> value) {
        if (value == null || value.isEmpty()) {
            profiles = Map.of();
            return;
        }
        Map<String, NativeMountedDescentPhysics.Settings> normalized = new HashMap<>();
        for (Map.Entry<String, NativeMountedDescentPhysics.Settings> entry : value.entrySet()) {
            String profileId = normalizeKey(entry.getKey());
            if (!profileId.isEmpty() && entry.getValue() != null) {
                normalized.put(profileId, entry.getValue());
            }
        }
        profiles = Map.copyOf(normalized);
    }

    @Nonnull
    private static String normalizeKey(@Nullable String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
