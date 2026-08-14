package com.alechilles.alecstamework.npc.progression;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Caches role appearance IDs between explicit asset-generation changes. */
final class CompanionRoleAppearanceCache {
    private final Map<Path, Optional<String>> appearances = new HashMap<>();
    private final Function<Path, JsonObject> loader;

    CompanionRoleAppearanceCache(@Nonnull Function<Path, JsonObject> loader) {
        this.loader = loader;
    }

    @Nullable
    synchronized String resolve(@Nullable Path rolePath,
                                @Nonnull Function<String, Path> referencePathResolver) {
        return resolve(rolePath, referencePathResolver, new HashSet<>());
    }

    synchronized void clear() {
        appearances.clear();
    }

    @Nullable
    private String resolve(@Nullable Path rolePath,
                           Function<String, Path> referencePathResolver,
                           Set<Path> visitedPaths) {
        Path key = normalize(rolePath);
        if (key == null || !visitedPaths.add(key)) {
            return null;
        }
        Optional<String> cached = appearances.get(key);
        if (cached != null) {
            return cached.orElse(null);
        }

        JsonObject root = loader.apply(key);
        String appearance = getString(root, "Appearance");
        if (appearance == null) {
            appearance = getString(getObject(root, "Modify"), "Appearance");
        }
        if (appearance == null) {
            String reference = getString(root, "Reference");
            if (reference != null) {
                appearance = resolve(referencePathResolver.apply(reference), referencePathResolver, visitedPaths);
            }
        }
        appearances.put(key, Optional.ofNullable(appearance));
        return appearance;
    }

    @Nullable
    private static Path normalize(@Nullable Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    @Nullable
    private static JsonObject getObject(@Nullable JsonObject root, String key) {
        if (root == null) {
            return null;
        }
        JsonElement element = root.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    @Nullable
    private static String getString(@Nullable JsonObject root, String key) {
        if (root == null) {
            return null;
        }
        JsonElement element = root.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        String value = element.getAsString();
        return value == null || value.isBlank() ? null : value;
    }
}
