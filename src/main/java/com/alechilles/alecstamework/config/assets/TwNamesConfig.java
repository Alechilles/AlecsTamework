package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Asset-backed random-name pools for naming UI randomization.
 * Stored under Server/Tamework/Names.
 */
public class TwNamesConfig implements JsonAssetWithMap<String, DefaultAssetMap<String, TwNamesConfig>>,
        TwParentFallbackAsset<TwNamesConfig> {
    public static final AssetBuilderCodec<String, TwNamesConfig> CODEC =
            AssetBuilderCodec.builder(
                            TwNamesConfig.class,
                            TwNamesConfig::new,
                            Codec.STRING,
                            (asset, id) -> asset.id = id,
                            asset -> asset.id,
                            (asset, data) -> asset.data = data,
                            asset -> asset.data
                    )
                    .documentation("Random name pools for Alec's Tamework naming UI.")
                    .<String[]>append(
                            new KeyedCodec<>("NorthAmericaMale", Codec.STRING_ARRAY),
                            (asset, value) -> asset.northAmericaMale = value == null ? new String[0] : value,
                            asset -> asset.northAmericaMale
                    )
                    .documentation("North America (US + Canada) male first names. Inheritance: omitted inherits; "
                            + "explicit array replaces parent.")
                    .add()
                    .<String[]>append(
                            new KeyedCodec<>("NorthAmericaFemale", Codec.STRING_ARRAY),
                            (asset, value) -> asset.northAmericaFemale = value == null ? new String[0] : value,
                            asset -> asset.northAmericaFemale
                    )
                    .documentation("North America (US + Canada) female first names. Inheritance: omitted inherits; "
                            + "explicit array replaces parent.")
                    .add()
                    .<String[]>append(
                            new KeyedCodec<>("GermanMale", Codec.STRING_ARRAY),
                            (asset, value) -> asset.germanMale = value == null ? new String[0] : value,
                            asset -> asset.germanMale
                    )
                    .documentation("German male first names. Inheritance: omitted inherits; explicit array replaces parent.")
                    .add()
                    .<String[]>append(
                            new KeyedCodec<>("GermanFemale", Codec.STRING_ARRAY),
                            (asset, value) -> asset.germanFemale = value == null ? new String[0] : value,
                            asset -> asset.germanFemale
                    )
                    .documentation("German female first names. Inheritance: omitted inherits; explicit array replaces parent.")
                    .add()
                    .<String[]>append(
                            new KeyedCodec<>("SpanishMale", Codec.STRING_ARRAY),
                            (asset, value) -> asset.spanishMale = value == null ? new String[0] : value,
                            asset -> asset.spanishMale
                    )
                    .documentation("Spanish male first names. Inheritance: omitted inherits; explicit array replaces parent.")
                    .add()
                    .<String[]>append(
                            new KeyedCodec<>("SpanishFemale", Codec.STRING_ARRAY),
                            (asset, value) -> asset.spanishFemale = value == null ? new String[0] : value,
                            asset -> asset.spanishFemale
                    )
                    .documentation("Spanish female first names. Inheritance: omitted inherits; explicit array replaces parent.")
                    .add()
                    .<String[]>append(
                            new KeyedCodec<>("BrazilianPortugueseMale", Codec.STRING_ARRAY),
                            (asset, value) -> asset.brazilianPortugueseMale = value == null ? new String[0] : value,
                            asset -> asset.brazilianPortugueseMale
                    )
                    .documentation("Brazilian Portuguese male first names. Inheritance: omitted inherits; explicit array "
                            + "replaces parent.")
                    .add()
                    .<String[]>append(
                            new KeyedCodec<>("BrazilianPortugueseFemale", Codec.STRING_ARRAY),
                            (asset, value) -> asset.brazilianPortugueseFemale = value == null ? new String[0] : value,
                            asset -> asset.brazilianPortugueseFemale
                    )
                    .documentation("Brazilian Portuguese female first names. Inheritance: omitted inherits; explicit array "
                            + "replaces parent.")
                    .add()
                    .build();

    private static AssetStore<String, TwNamesConfig, DefaultAssetMap<String, TwNamesConfig>> ASSET_STORE;
    private static final Object INHERITANCE_CACHE_LOCK = new Object();
    private static volatile boolean INHERITANCE_CACHE_DIRTY = true;

    private AssetExtraInfo.Data data;
    private String id;
    private String[] northAmericaMale = new String[0];
    private String[] northAmericaFemale = new String[0];
    private String[] germanMale = new String[0];
    private String[] germanFemale = new String[0];
    private String[] spanishMale = new String[0];
    private String[] spanishFemale = new String[0];
    private String[] brazilianPortugueseMale = new String[0];
    private String[] brazilianPortugueseFemale = new String[0];

    public static AssetStore<String, TwNamesConfig, DefaultAssetMap<String, TwNamesConfig>> getAssetStore() {
        if (ASSET_STORE == null) {
            ASSET_STORE = AssetRegistry.getAssetStore(TwNamesConfig.class);
        }
        return ASSET_STORE;
    }

    @Nullable
    public static DefaultAssetMap<String, TwNamesConfig> getAssetMap() {
        AssetStore<String, TwNamesConfig, DefaultAssetMap<String, TwNamesConfig>> store = getAssetStore();
        if (store == null) {
            return null;
        }
        DefaultAssetMap<String, TwNamesConfig> assetMap = (DefaultAssetMap<String, TwNamesConfig>) store.getAssetMap();
        ensureInheritanceFallbackApplied(assetMap);
        return assetMap;
    }

    public static void clearInheritanceFallbackCache() {
        INHERITANCE_CACHE_DIRTY = true;
    }

    @Nonnull
    public static String[] resolveMergedPoolById(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return new String[0];
        }
        DefaultAssetMap<String, TwNamesConfig> assetMap = getAssetMap();
        if (assetMap == null) {
            return new String[0];
        }
        TwNamesConfig asset = assetMap.getAsset(id);
        if (asset == null) {
            return new String[0];
        }
        return asset.getMergedPool();
    }

    private static void ensureInheritanceFallbackApplied(@Nullable DefaultAssetMap<String, TwNamesConfig> assetMap) {
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

    protected TwNamesConfig() {
    }

    public String getId() {
        return id;
    }

    @Nonnull
    public String[] getNorthAmericaMale() {
        return northAmericaMale;
    }

    @Nonnull
    public String[] getNorthAmericaFemale() {
        return northAmericaFemale;
    }

    @Nonnull
    public String[] getGermanMale() {
        return germanMale;
    }

    @Nonnull
    public String[] getGermanFemale() {
        return germanFemale;
    }

    @Nonnull
    public String[] getSpanishMale() {
        return spanishMale;
    }

    @Nonnull
    public String[] getSpanishFemale() {
        return spanishFemale;
    }

    @Nonnull
    public String[] getBrazilianPortugueseMale() {
        return brazilianPortugueseMale;
    }

    @Nonnull
    public String[] getBrazilianPortugueseFemale() {
        return brazilianPortugueseFemale;
    }

    @Nullable
    @Override
    public String getParentIdForFallback() {
        if (data == null || data.getParentKey() == null) {
            return null;
        }
        String parentId = data.getParentKey().toString();
        return parentId == null || parentId.isBlank() ? null : parentId;
    }

    @Override
    public void inheritMissingTopLevelFrom(@Nonnull TwNamesConfig parent, @Nonnull Set<String> explicitTopLevelKeys) {
        inheritMissingTopLevelFrom(parent, explicitTopLevelKeys, null);
    }

    @Override
    public void inheritMissingTopLevelFrom(@Nonnull TwNamesConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys,
                                           @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        if (!explicitTopLevelKeys.contains("NorthAmericaMale")) northAmericaMale = parent.northAmericaMale;
        if (!explicitTopLevelKeys.contains("NorthAmericaFemale")) northAmericaFemale = parent.northAmericaFemale;
        if (!explicitTopLevelKeys.contains("GermanMale")) germanMale = parent.germanMale;
        if (!explicitTopLevelKeys.contains("GermanFemale")) germanFemale = parent.germanFemale;
        if (!explicitTopLevelKeys.contains("SpanishMale")) spanishMale = parent.spanishMale;
        if (!explicitTopLevelKeys.contains("SpanishFemale")) spanishFemale = parent.spanishFemale;
        if (!explicitTopLevelKeys.contains("BrazilianPortugueseMale")) {
            brazilianPortugueseMale = parent.brazilianPortugueseMale;
        }
        if (!explicitTopLevelKeys.contains("BrazilianPortugueseFemale")) {
            brazilianPortugueseFemale = parent.brazilianPortugueseFemale;
        }
    }

    @Nonnull
    public String[] getMergedPool() {
        ArrayList<String> out = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        appendUnique(out, seen, northAmericaMale);
        appendUnique(out, seen, northAmericaFemale);
        appendUnique(out, seen, germanMale);
        appendUnique(out, seen, germanFemale);
        appendUnique(out, seen, spanishMale);
        appendUnique(out, seen, spanishFemale);
        appendUnique(out, seen, brazilianPortugueseMale);
        appendUnique(out, seen, brazilianPortugueseFemale);
        return out.toArray(String[]::new);
    }

    private static void appendUnique(@Nonnull ArrayList<String> out, @Nonnull HashSet<String> seen, @Nullable String[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            String key = trimmed.toLowerCase(Locale.ROOT);
            if (!seen.add(key)) {
                continue;
            }
            out.add(trimmed);
        }
    }
}
