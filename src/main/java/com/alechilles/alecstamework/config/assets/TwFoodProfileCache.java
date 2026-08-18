package com.alechilles.alecstamework.config.assets;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Caches resolved food profiles for a single food config asset.
 */
final class TwFoodProfileCache {
    private final Object lock = new Object();
    private volatile CacheState state = new CacheState(Long.MIN_VALUE, new ConcurrentHashMap<>());

    @Nonnull
    TwFoodConfig.ResolvedFoodProfile resolve(@Nullable String roleId,
                                             long currentGeneration,
                                             @Nonnull ProfileFactory factory) {
        String key = normalizeRoleKey(roleId);
        CacheState snapshot = state;
        if (snapshot.generation != currentGeneration) {
            snapshot = switchGeneration(currentGeneration);
        }
        TwFoodConfig.ResolvedFoodProfile cached = snapshot.profilesByRole.get(key);
        if (cached != null) {
            return cached;
        }
        return snapshot.profilesByRole.computeIfAbsent(key, ignored -> factory.resolve(roleId));
    }

    @Nonnull
    private CacheState switchGeneration(long currentGeneration) {
        synchronized (lock) {
            CacheState current = state;
            if (current.generation >= currentGeneration) {
                return current;
            }
            current = new CacheState(currentGeneration, new ConcurrentHashMap<>());
            state = current;
            return current;
        }
    }

    @Nonnull
    private static String normalizeRoleKey(@Nullable String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return "";
        }
        String normalized = roleId.trim().toLowerCase(Locale.ROOT);
        int separator = normalized.lastIndexOf(':');
        if (separator >= 0 && separator < normalized.length() - 1) {
            return normalized.substring(separator + 1);
        }
        return normalized;
    }

    interface ProfileFactory {
        @Nonnull
        TwFoodConfig.ResolvedFoodProfile resolve(@Nullable String roleId);
    }

    private record CacheState(long generation,
                              ConcurrentMap<String, TwFoodConfig.ResolvedFoodProfile> profilesByRole) {
    }
}
