package com.alechilles.alecstamework.config.assets;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Caches resolved food profiles for a single food config asset.
 */
final class TwFoodProfileCache {
    private final Object lock = new Object();
    private long generation = Long.MIN_VALUE;
    private Map<String, TwFoodConfig.ResolvedFoodProfile> profilesByRole = new HashMap<>();

    @Nonnull
    TwFoodConfig.ResolvedFoodProfile resolve(@Nullable String roleId,
                                             long currentGeneration,
                                             @Nonnull ProfileFactory factory) {
        String key = normalizeRoleKey(roleId);
        synchronized (lock) {
            if (generation != currentGeneration) {
                profilesByRole = new HashMap<>();
                generation = currentGeneration;
            }
            TwFoodConfig.ResolvedFoodProfile cached = profilesByRole.get(key);
            if (cached != null) {
                return cached;
            }
            TwFoodConfig.ResolvedFoodProfile resolved = factory.resolve(roleId);
            profilesByRole.put(key, resolved);
            return resolved;
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
}
