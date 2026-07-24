package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Resolves authority-eligible coop configs while owning the derived lookup caches. */
final class TwCoopConfigResolver {
    private final Object coopCacheLock = new Object();
    private final Object blockTypeCacheLock = new Object();
    private volatile boolean coopCacheDirty = true;
    private volatile boolean blockTypeCacheDirty = true;
    private volatile Map<String, TwCoopConfig> coopCache = Map.of();
    private volatile Map<String, TwCoopConfig> blockTypeCache = Map.of();

    void clear() {
        coopCacheDirty = true;
        blockTypeCacheDirty = true;
    }

    @Nullable
    TwCoopConfig resolveForCoop(@Nullable String coopId,
                                @Nullable DefaultAssetMap<String, TwCoopConfig> assetMap) {
        String key = normalizeIdentifier(coopId);
        if (key == null || assetMap == null) {
            return null;
        }
        Map<String, TwCoopConfig> resolved = coopCache;
        if (coopCacheDirty || resolved == null) {
            synchronized (coopCacheLock) {
                if (coopCacheDirty || coopCache == null) {
                    coopCache = buildCoopCache(assetMap);
                    coopCacheDirty = false;
                }
                resolved = coopCache;
            }
        }
        return resolved.get(key);
    }

    @Nullable
    TwCoopConfig resolveForBlockType(@Nullable String blockTypeId,
                                     @Nullable DefaultAssetMap<String, TwCoopConfig> assetMap) {
        String key = normalizeBlockTypeId(blockTypeId);
        if (key == null || assetMap == null) {
            return null;
        }
        Map<String, TwCoopConfig> resolved = blockTypeCache;
        if (blockTypeCacheDirty || resolved == null) {
            synchronized (blockTypeCacheLock) {
                if (blockTypeCacheDirty || blockTypeCache == null) {
                    blockTypeCache = buildBlockTypeCache(assetMap);
                    blockTypeCacheDirty = false;
                }
                resolved = blockTypeCache;
            }
        }
        return resolved.get(key);
    }

    @Nonnull
    static Map<String, TwCoopConfig> buildCoopCache(
            @Nullable DefaultAssetMap<String, TwCoopConfig> assetMap) {
        Map<String, TwCoopConfig> cache = new HashMap<>();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return cache;
        }
        for (TwCoopConfig candidate : assetMap.getAssetMap().values()) {
            if (!isAuthorityCandidate(candidate)) {
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

    @Nonnull
    static Map<String, TwCoopConfig> buildBlockTypeCache(
            @Nullable DefaultAssetMap<String, TwCoopConfig> assetMap) {
        Map<String, TwCoopConfig> cache = new HashMap<>();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return cache;
        }
        for (TwCoopConfig candidate : assetMap.getAssetMap().values()) {
            if (isAuthorityCandidate(candidate)) {
                registerBlockTypeCandidates(cache, candidate);
            }
        }
        return cache;
    }

    private static boolean isAuthorityCandidate(@Nullable TwCoopConfig candidate) {
        return candidate != null && candidate.isEnabled();
    }

    private static void registerBlockTypeCandidates(@Nonnull Map<String, TwCoopConfig> cache,
                                                    @Nonnull TwCoopConfig candidate) {
        boolean resolvedAny = false;
        for (String rawBlockTypeId : candidate.getBlockTypeIds()) {
            String blockTypeId = normalizeBlockTypeId(rawBlockTypeId);
            if (blockTypeId == null) {
                continue;
            }
            resolvedAny = true;
            registerPreferred(cache, blockTypeId, candidate);
        }
        if (!resolvedAny) {
            String fallback = normalizeBlockTypeId(candidate.getCoopId());
            if (fallback != null) {
                registerPreferred(cache, fallback, candidate);
            }
        }
    }

    private static void registerPreferred(@Nonnull Map<String, TwCoopConfig> cache,
                                          @Nonnull String key,
                                          @Nonnull TwCoopConfig candidate) {
        TwCoopConfig existing = cache.get(key);
        if (existing == null || shouldPreferCandidate(candidate, existing)) {
            cache.put(key, candidate);
        }
    }

    private static boolean shouldPreferCandidate(@Nonnull TwCoopConfig candidate,
                                                 @Nonnull TwCoopConfig existing) {
        if (candidate.getPriority() != existing.getPriority()) {
            return candidate.getPriority() > existing.getPriority();
        }
        String candidateId = candidate.getId();
        String existingId = existing.getId();
        if (candidateId == null) {
            return false;
        }
        return existingId == null || candidateId.compareToIgnoreCase(existingId) < 0;
    }

    @Nullable
    private static String normalizeIdentifier(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    @Nullable
    private static String normalizeBlockTypeId(@Nullable String value) {
        String normalized = normalizeIdentifier(value);
        if (normalized == null) {
            return null;
        }
        while (normalized.startsWith("*")) {
            normalized = normalized.substring(1);
        }
        return normalized.isBlank() ? null : normalized;
    }
}
