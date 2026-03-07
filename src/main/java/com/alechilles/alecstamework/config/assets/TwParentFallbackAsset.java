package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Contract for assets that support parent-based fallback inheritance using raw top-level key presence.
 *
 * @param <T> concrete asset type
 */
interface TwParentFallbackAsset<T extends TwParentFallbackAsset<T>>
        extends JsonAssetWithMap<String, DefaultAssetMap<String, T>> {
    /**
     * @return this asset's ID key
     */
    @Nullable
    String getId();

    /**
     * @return parent asset ID from metadata, or null when no parent is configured
     */
    @Nullable
    String getParentIdForFallback();

    /**
     * Copies missing top-level fields from {@code parent}, honoring explicitly present child keys.
     */
    void inheritMissingTopLevelFrom(@Nonnull T parent, @Nonnull Set<String> explicitTopLevelKeys);

    /**
     * Copies missing fields from {@code parent}, honoring explicitly present child top-level and nested keys.
     *
     * <p>Default behavior delegates to legacy top-level handling for asset types that do not need nested
     * key awareness.
     */
    default void inheritMissingTopLevelFrom(@Nonnull T parent,
                                            @Nonnull Set<String> explicitTopLevelKeys,
                                            @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        inheritMissingTopLevelFrom(parent, explicitTopLevelKeys);
    }
}
