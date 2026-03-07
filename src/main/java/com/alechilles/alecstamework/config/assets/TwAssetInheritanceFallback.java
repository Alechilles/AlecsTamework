package com.alechilles.alecstamework.config.assets;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Generic fallback repair for parented Tamework assets when child JSON omits top-level keys.
 *
 * <p>This does not override explicit child keys. It only fills omitted top-level sections from the parent
 * chain and is intended as a compatibility layer when the runtime does not merge parent values automatically.
 */
final class TwAssetInheritanceFallback {
    private TwAssetInheritanceFallback() {
    }

    static <T extends TwParentFallbackAsset<T>> void repairAll(@Nullable DefaultAssetMap<String, T> assetMap) {
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return;
        }
        Context<T> context = createContext(assetMap);
        for (T candidate : assetMap.getAssetMap().values()) {
            repairParentFallback(candidate, context);
        }
    }

    static <T extends TwParentFallbackAsset<T>> Context<T> createContext(@Nonnull DefaultAssetMap<String, T> assetMap) {
        return new Context<>(assetMap);
    }

    static <T extends TwParentFallbackAsset<T>> void repairParentFallback(@Nullable T candidate,
                                                                           @Nonnull Context<T> context) {
        if (candidate == null || candidate.getId() == null || candidate.getId().isBlank()) {
            return;
        }
        String idKey = candidate.getId().trim().toLowerCase(Locale.ROOT);
        if (context.repaired.contains(idKey) || !context.repairing.add(idKey)) {
            return;
        }
        try {
            String parentId = candidate.getParentIdForFallback();
            if (parentId == null || parentId.isBlank()) {
                return;
            }
            T parent = resolveByIdFromMap(context.assetMap, parentId);
            if (parent == null || parent == candidate) {
                return;
            }
            repairParentFallback(parent, context);
            Set<String> explicit = loadExplicitTopLevelKeys(candidate, context);
            Map<String, Set<String>> explicitNested = loadExplicitNestedKeysByTopLevel(candidate, context);
            if (explicit != null) {
                candidate.inheritMissingTopLevelFrom(parent, explicit, explicitNested);
            }
        } finally {
            context.repairing.remove(idKey);
            context.repaired.add(idKey);
        }
    }

    @Nullable
    private static <T extends TwParentFallbackAsset<T>> Set<String> loadExplicitTopLevelKeys(@Nonnull T config,
                                                                                               @Nonnull Context<T> context) {
        String id = config.getId();
        if (id == null || id.isBlank()) {
            return null;
        }
        String idKey = id.trim().toLowerCase(Locale.ROOT);
        if (context.explicitTopLevelKeys.containsKey(idKey)) {
            return context.explicitTopLevelKeys.get(idKey);
        }
        Path path = context.assetMap.getPath(id);
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            String raw = Files.readString(path);
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonObject object = parsed.getAsJsonObject();
            Set<String> keys = new HashSet<>(object.keySet());
            Map<String, Set<String>> nestedKeysByTopLevel = new HashMap<>();
            object.entrySet().forEach(entry -> {
                if (!entry.getValue().isJsonObject()) {
                    return;
                }
                nestedKeysByTopLevel.put(entry.getKey(), new HashSet<>(entry.getValue().getAsJsonObject().keySet()));
            });
            context.explicitTopLevelKeys.put(idKey, keys);
            context.explicitNestedKeysByTopLevel.put(idKey, nestedKeysByTopLevel);
            return keys;
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nonnull
    private static <T extends TwParentFallbackAsset<T>> Map<String, Set<String>> loadExplicitNestedKeysByTopLevel(
            @Nonnull T config,
            @Nonnull Context<T> context) {
        String id = config.getId();
        if (id == null || id.isBlank()) {
            return Map.of();
        }
        String idKey = id.trim().toLowerCase(Locale.ROOT);
        if (!context.explicitTopLevelKeys.containsKey(idKey)) {
            loadExplicitTopLevelKeys(config, context);
        }
        Map<String, Set<String>> nested = context.explicitNestedKeysByTopLevel.get(idKey);
        return nested == null ? Map.of() : nested;
    }

    @Nullable
    private static <T extends TwParentFallbackAsset<T>> T resolveByIdFromMap(@Nonnull DefaultAssetMap<String, T> assetMap,
                                                                              @Nonnull String configId) {
        if (assetMap.getAssetMap() == null || configId.isBlank()) {
            return null;
        }
        T direct = assetMap.getAssetMap().get(configId);
        if (direct != null) {
            return direct;
        }
        for (T candidate : assetMap.getAssetMap().values()) {
            if (candidate == null || candidate.getId() == null) {
                continue;
            }
            if (candidate.getId().equalsIgnoreCase(configId)) {
                return candidate;
            }
        }
        return null;
    }

    static final class Context<T extends TwParentFallbackAsset<T>> {
        private final DefaultAssetMap<String, T> assetMap;
        private final Map<String, Set<String>> explicitTopLevelKeys = new HashMap<>();
        private final Map<String, Map<String, Set<String>>> explicitNestedKeysByTopLevel = new HashMap<>();
        private final Set<String> repairing = new HashSet<>();
        private final Set<String> repaired = new HashSet<>();

        private Context(@Nonnull DefaultAssetMap<String, T> assetMap) {
            this.assetMap = assetMap;
        }
    }
}
