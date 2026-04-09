package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves per-item feed desirability scores for needs-driven container consumption.
 */
final class FeedItemPreferenceResolver {
    private static final double DEFAULT_SCORE = 0.0;

    @Nullable
    private final Ref<EntityStore> npcRef;
    @Nullable
    private final Store<EntityStore> store;
    @Nullable
    private final TwHappinessConfig happinessConfig;
    @Nonnull
    private final Map<String, Double> scoreCache = new HashMap<>();

    private FeedItemPreferenceResolver(@Nullable Ref<EntityStore> npcRef,
                                       @Nullable Store<EntityStore> store,
                                       @Nullable TwHappinessConfig happinessConfig) {
        this.npcRef = npcRef;
        this.store = store;
        this.happinessConfig = happinessConfig;
    }

    @Nonnull
    static FeedItemPreferenceResolver create(@Nullable Ref<EntityStore> npcRef,
                                             @Nullable Store<EntityStore> store,
                                             @Nullable String roleId) {
        TwHappinessConfig byRole = null;
        if (roleId != null && !roleId.isBlank()) {
            byRole = TwHappinessConfig.resolveForRole(roleId);
        }
        TwHappinessConfig config = byRole != null
                ? byRole
                : HappinessConfigResolver.resolveConfig(npcRef, store, null);
        return new FeedItemPreferenceResolver(npcRef, store, config);
    }

    @Nonnull
    static FeedItemPreferenceResolver create(@Nullable TwHappinessConfig happinessConfig) {
        return new FeedItemPreferenceResolver(null, null, happinessConfig);
    }

    double score(@Nullable String itemId) {
        String normalizedItemId = normalize(itemId);
        if (normalizedItemId == null) {
            return DEFAULT_SCORE;
        }
        Double cached = scoreCache.get(normalizedItemId);
        if (cached != null) {
            return cached;
        }
        double resolved = CompanionHappinessService.resolveFeedItemPreferenceScore(
                npcRef,
                store,
                happinessConfig,
                normalizedItemId,
                DEFAULT_SCORE
        );
        if (!Double.isFinite(resolved)) {
            resolved = DEFAULT_SCORE;
        }
        scoreCache.put(normalizedItemId, resolved);
        return resolved;
    }

    @Nullable
    private static String normalize(@Nullable String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        return itemId.trim().toLowerCase(Locale.ROOT);
    }
}
