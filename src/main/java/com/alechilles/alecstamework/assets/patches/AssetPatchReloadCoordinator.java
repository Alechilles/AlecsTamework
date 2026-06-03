package com.alechilles.alecstamework.assets.patches;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.alechilles.alecstamework.config.overrides.TwConfigFamily;
import com.hypixel.hytale.assetstore.AssetLoadResult;
import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.assetstore.AssetMap;
import com.hypixel.hytale.assetstore.AssetStore;

/**
 * Classifies generated optional asset patch outputs that still require explicit restart reporting.
 */
public final class AssetPatchReloadCoordinator {
    private final TameworkConfigLoader tameworkConfigLoader;
    private final Runnable itemFeatureConfigReloader;

    public AssetPatchReloadCoordinator() {
        this(AssetPatchReloadCoordinator::loadGeneratedTameworkConfigFamily, () -> {
        });
    }

    AssetPatchReloadCoordinator(@Nonnull TameworkConfigLoader tameworkConfigLoader,
                                @Nonnull Runnable itemFeatureConfigReloader) {
        this.tameworkConfigLoader = tameworkConfigLoader;
        this.itemFeatureConfigReloader = itemFeatureConfigReloader;
    }

    public void reloadPublishedTargets(@Nonnull AssetPack generatedPack,
                                       @Nonnull Collection<String> affectedTargets,
                                       @Nonnull AssetPatchStatus status) {
        LinkedHashSet<String> normalizedTargets = new LinkedHashSet<>();
        for (String target : affectedTargets) {
            normalizedTargets.add(AssetPatchDefinition.normalizeAssetPath(target));
        }

        Map<TwConfigFamily, LinkedHashSet<String>> tameworkConfigTargets = new LinkedHashMap<>();
        for (String target : normalizedTargets) {
            AssetPatchTargetClassification classification = AssetPatchTargetClassifier.classify(target);
            switch (classification.reloadMode()) {
                case NPC_BUILDERS -> {
                    // Hytale's NPC asset watcher handles generated role files asynchronously.
                }
                case TAMEWORK_CONFIG -> {
                    TwConfigFamily family = familyForTarget(target);
                    if (family == null || family == TwConfigFamily.OTHER) {
                        status.addRestartRequiredTarget(target);
                        continue;
                    }
                    tameworkConfigTargets
                            .computeIfAbsent(family, ignored -> new LinkedHashSet<>())
                            .add(target);
                }
                case HYTALE_ASSET_STORE, RESTART_REQUIRED ->
                        status.addRestartRequiredTarget(target);
            }
        }

        reloadTameworkConfigTargets(generatedPack, tameworkConfigTargets, status);
    }

    private void reloadTameworkConfigTargets(@Nonnull AssetPack generatedPack,
                                             @Nonnull Map<TwConfigFamily, LinkedHashSet<String>> targetsByFamily,
                                             @Nonnull AssetPatchStatus status) {
        if (targetsByFamily.isEmpty()) {
            return;
        }

        boolean reloadItemFeatures = false;
        for (Map.Entry<TwConfigFamily, LinkedHashSet<String>> entry : targetsByFamily.entrySet()) {
            TwConfigFamily family = entry.getKey();
            List<String> targets = List.copyOf(entry.getValue());
            try {
                tameworkConfigLoader.load(generatedPack, family);
                targets.forEach(status::addHotReloadedTarget);
                if (isItemFeatureFamily(family)) {
                    reloadItemFeatures = true;
                }
            } catch (IOException | RuntimeException ex) {
                String detail = "Failed to hot-reload generated Tamework config family "
                        + family.getDisplayName()
                        + " targets="
                        + targets
                        + ": "
                        + ex.getMessage();
                status.addFailed(detail);
                targets.forEach(status::addRestartRequiredTarget);
            }
        }

        if (reloadItemFeatures) {
            try {
                itemFeatureConfigReloader.run();
                status.addHotReloadedTarget("Server/Tamework/Items/*");
            } catch (RuntimeException ex) {
                status.addFailed("Failed to reload Tamework item feature configs: " + ex.getMessage());
            }
        }
    }

    @Nullable
    private static TwConfigFamily familyForTarget(@Nonnull String target) {
        String normalized = AssetPatchDefinition.normalizeAssetPath(target);
        if (!normalized.startsWith("Server/Tamework/") || normalized.startsWith("Server/Tamework/Patches/")) {
            return null;
        }
        int fileSeparator = normalized.lastIndexOf('/');
        if (fileSeparator < "Server/".length()) {
            return null;
        }
        String storePath = normalized.substring("Server/".length(), fileSeparator);
        return TwConfigFamily.fromStorePath(storePath);
    }

    private static boolean isItemFeatureFamily(@Nonnull TwConfigFamily family) {
        return family == TwConfigFamily.SPAWNER
                || family == TwConfigFamily.NAME_ITEM
                || family == TwConfigFamily.COMMAND_ITEM;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void loadGeneratedTameworkConfigFamily(@Nonnull AssetPack generatedPack,
                                                  @Nonnull TwConfigFamily family) throws IOException {
        AssetStore<String, ?, ? extends AssetMap<String, ?>> store = family.getAssetStore();
        if (store == null) {
            throw new IOException("asset store is not registered");
        }
        Path packRoot = generatedPack.getRoot();
        if (packRoot == null) {
            throw new IOException("generated pack root is unavailable");
        }
        Path familyRoot = packRoot
                .resolve("Server")
                .resolve(family.getStorePath())
                .toAbsolutePath()
                .normalize();
        if (!containsJsonFiles(familyRoot)) {
            throw new IOException("generated family directory has no JSON files: " + familyRoot);
        }
        AssetLoadResult result = ((AssetStore) store).loadAssetsFromDirectory(generatedPack.getName(), familyRoot);
        if (result != null && result.hasFailed()) {
            throw new IOException(
                    "asset load failed keys="
                            + result.getFailedToLoadKeys().size()
                            + " paths="
                            + result.getFailedToLoadPaths().size()
            );
        }
    }

    private static boolean containsJsonFiles(@Nonnull Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (var stream = Files.walk(directory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .anyMatch(path -> path.getFileName() != null && path.getFileName().toString().endsWith(".json"));
        }
    }

    @FunctionalInterface
    interface TameworkConfigLoader {
        void load(@Nonnull AssetPack generatedPack, @Nonnull TwConfigFamily family) throws IOException;
    }
}
