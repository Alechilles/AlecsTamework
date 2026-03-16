package com.alechilles.alecstamework.npc.progression;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.BuilderInfo;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves adult lifecycle scale baselines from role appearance assets instead of the current baby model.
 */
final class CompanionAdultScaleResolver {
    private static final double EPSILON = 0.000001;
    private static final double MIN_SCALE = 0.10;

    private CompanionAdultScaleResolver() {
    }

    static double resolveAdultScale(@Nullable Ref<EntityStore> npcRef,
                                    @Nullable Store<EntityStore> store,
                                    @Nullable String adultRoleId,
                                    double fallbackScale) {
        double safeFallback = sanitizeScale(fallbackScale);
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return safeFallback;
        }
        ModelAsset currentModelAsset = CompanionModelAttachmentService.resolveModelAsset(npcRef, store);
        ModelAsset adultModelAsset = resolveRoleModelAsset(adultRoleId);
        if (currentModelAsset == null || adultModelAsset == null) {
            return safeFallback;
        }

        double sizeMultiplier = sanitizeMultiplier(TraitModifierService.resolveMultiplier(
                npcRef,
                store,
                "SizeMultiplier",
                1.0
        ));
        double currentScale = CompanionModelScaleService.resolveCurrentScale(npcRef, store, safeFallback);
        double naturalCurrentScale = currentScale / sizeMultiplier;
        Double adultNaturalScale = remapNaturalScale(
                naturalCurrentScale,
                currentModelAsset.getMinScale(),
                currentModelAsset.getMaxScale(),
                adultModelAsset.getMinScale(),
                adultModelAsset.getMaxScale()
        );
        if (adultNaturalScale == null) {
            return safeFallback;
        }
        return sanitizeScale(adultNaturalScale * sizeMultiplier);
    }

    @Nullable
    static Double remapNaturalScale(double sourceScale,
                                    double sourceMin,
                                    double sourceMax,
                                    double targetMin,
                                    double targetMax) {
        if (!Double.isFinite(sourceScale)
                || !Double.isFinite(sourceMin)
                || !Double.isFinite(sourceMax)
                || !Double.isFinite(targetMin)
                || !Double.isFinite(targetMax)) {
            return null;
        }

        double sourceLow = Math.min(sourceMin, sourceMax);
        double sourceHigh = Math.max(sourceMin, sourceMax);
        double targetLow = Math.min(targetMin, targetMax);
        double targetHigh = Math.max(targetMin, targetMax);
        double sourceRange = sourceHigh - sourceLow;
        double targetRange = targetHigh - targetLow;

        if (sourceRange <= EPSILON) {
            return targetRange <= EPSILON ? targetLow : targetLow + (targetRange * 0.5);
        }
        if (sourceScale < sourceLow - EPSILON || sourceScale > sourceHigh + EPSILON) {
            return null;
        }

        double progress = (sourceScale - sourceLow) / sourceRange;
        if (progress < 0.0) {
            progress = 0.0;
        } else if (progress > 1.0) {
            progress = 1.0;
        }
        return targetLow + (targetRange * progress);
    }

    @Nullable
    static String resolveAppearanceFromRoleAsset(@Nullable Path roleAssetPath,
                                                 @Nonnull Function<String, Path> referencePathResolver) {
        if (roleAssetPath == null || referencePathResolver == null) {
            return null;
        }
        return resolveAppearanceFromRoleAsset(roleAssetPath, referencePathResolver, new HashSet<>());
    }

    @Nullable
    private static ModelAsset resolveRoleModelAsset(@Nullable String roleId) {
        String appearanceName = resolveRoleAppearance(roleId, new HashSet<>());
        if (appearanceName == null || appearanceName.isBlank()) {
            return null;
        }
        return ModelAsset.getAssetMap().getAsset(appearanceName);
    }

    @Nullable
    private static String resolveRoleAppearance(@Nullable String roleId, Set<String> visitedRoleIds) {
        String normalizedRoleId = normalizeRoleId(roleId);
        if (normalizedRoleId == null || !visitedRoleIds.add(normalizedRoleId.toLowerCase(Locale.ROOT))) {
            return null;
        }
        Path rolePath = resolveRoleAssetPath(normalizedRoleId);
        if (rolePath == null) {
            return null;
        }
        return resolveAppearanceFromRoleAsset(rolePath, CompanionAdultScaleResolver::resolveRoleAssetPath);
    }

    @Nullable
    private static String resolveAppearanceFromRoleAsset(@Nullable Path roleAssetPath,
                                                         @Nonnull Function<String, Path> referencePathResolver,
                                                         Set<Path> visitedPaths) {
        Path normalizedPath = normalizePath(roleAssetPath);
        if (normalizedPath == null || !visitedPaths.add(normalizedPath)) {
            return null;
        }

        JsonObject root = readJsonObject(normalizedPath);
        if (root == null) {
            return null;
        }

        String appearance = getString(root, "Appearance");
        if (appearance != null) {
            return appearance;
        }
        JsonObject modify = getObject(root, "Modify");
        appearance = getString(modify, "Appearance");
        if (appearance != null) {
            return appearance;
        }

        String reference = getString(root, "Reference");
        if (reference == null) {
            return null;
        }
        Path referencePath = normalizePath(referencePathResolver.apply(reference));
        if (referencePath == null) {
            return null;
        }
        return resolveAppearanceFromRoleAsset(referencePath, referencePathResolver, visitedPaths);
    }

    @Nullable
    private static Path resolveRoleAssetPath(@Nullable String roleId) {
        String normalizedRoleId = normalizeRoleId(roleId);
        if (normalizedRoleId == null) {
            return null;
        }
        NPCPlugin plugin = NPCPlugin.get();
        if (plugin == null) {
            return null;
        }
        int roleIndex = plugin.getIndex(normalizedRoleId);
        if (roleIndex < 0) {
            return null;
        }
        BuilderInfo builderInfo = plugin.getRoleBuilderInfo(roleIndex);
        return builderInfo != null ? normalizePath(builderInfo.getPath()) : null;
    }

    @Nullable
    private static JsonObject readJsonObject(@Nullable Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    @Nullable
    private static JsonObject getObject(@Nullable JsonObject root, String key) {
        if (root == null || key == null || key.isBlank()) {
            return null;
        }
        JsonElement element = root.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    @Nullable
    private static String getString(@Nullable JsonObject root, String key) {
        if (root == null || key == null || key.isBlank()) {
            return null;
        }
        JsonElement element = root.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        String value = element.getAsString();
        return value == null || value.isBlank() ? null : value;
    }

    @Nullable
    private static String normalizeRoleId(@Nullable String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        return roleId.trim();
    }

    @Nullable
    private static Path normalizePath(@Nullable Path path) {
        if (path == null) {
            return null;
        }
        return path.toAbsolutePath().normalize();
    }

    private static double sanitizeScale(double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            return 1.0;
        }
        return Math.max(MIN_SCALE, value);
    }

    private static double sanitizeMultiplier(double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            return 1.0;
        }
        return value;
    }
}
