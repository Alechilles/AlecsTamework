package com.alechilles.alecstamework.assets.patches;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

import javax.annotation.Nonnull;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.AssetUpdateQuery;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.npc.NPCPlugin;

/**
 * Reloads generated optional asset patch outputs through the narrow runtime paths known to be safe.
 */
public final class AssetPatchReloadCoordinator {
    private final JavaPlugin plugin;
    private final NpcBuilderReloadAdapter npcBuilderReloadAdapter;
    private final AssetStoreReloadAdapter assetStoreReloadAdapter;
    private final TameworkConfigReloadAdapter tameworkConfigReloadAdapter;

    public AssetPatchReloadCoordinator(@Nonnull JavaPlugin plugin) {
        this(
                plugin,
                new NpcPluginBuilderReloadAdapter(),
                new HytaleAssetStoreReloadAdapter(),
                new PluginTameworkConfigReloadAdapter()
        );
    }

    AssetPatchReloadCoordinator(@Nonnull JavaPlugin plugin,
                                @Nonnull NpcBuilderReloadAdapter npcBuilderReloadAdapter,
                                @Nonnull AssetStoreReloadAdapter assetStoreReloadAdapter,
                                @Nonnull TameworkConfigReloadAdapter tameworkConfigReloadAdapter) {
        this.plugin = plugin;
        this.npcBuilderReloadAdapter = npcBuilderReloadAdapter;
        this.assetStoreReloadAdapter = assetStoreReloadAdapter;
        this.tameworkConfigReloadAdapter = tameworkConfigReloadAdapter;
    }

    public void reloadPublishedTargets(@Nonnull AssetPack generatedPack,
                                       @Nonnull Collection<String> affectedTargets,
                                       @Nonnull AssetPatchStatus status) {
        LinkedHashSet<String> normalizedTargets = new LinkedHashSet<>();
        for (String target : affectedTargets) {
            normalizedTargets.add(AssetPatchDefinition.normalizeAssetPath(target));
        }

        boolean reloadNpcBuilders = false;
        LinkedHashSet<String> itemFeatureConfigTargets = new LinkedHashSet<>();
        for (String target : normalizedTargets) {
            AssetPatchTargetClassification classification = AssetPatchTargetClassifier.classify(target);
            switch (classification.reloadMode()) {
                case NPC_BUILDERS -> reloadNpcBuilders = true;
                case HYTALE_ASSET_STORE -> reloadAssetStoreTarget(generatedPack, target, status);
                case TAMEWORK_CONFIG -> reloadTameworkConfigTarget(generatedPack, target, itemFeatureConfigTargets, status);
                case RESTART_REQUIRED -> status.addRestartRequiredTarget(target);
            }
        }

        if (reloadNpcBuilders) {
            reloadNpcBuilders(generatedPack, status);
        }
        reloadTameworkItemFeatureConfigs(itemFeatureConfigTargets, status);
    }

    private void reloadAssetStoreTarget(@Nonnull AssetPack generatedPack,
                                        @Nonnull String target,
                                        @Nonnull AssetPatchStatus status) {
        try {
            if (assetStoreReloadAdapter.reload(generatedPack, target)) {
                status.addHotReloadedTarget(target);
                return;
            }
            status.addRestartRequiredTarget(target);
        } catch (RuntimeException ex) {
            markReloadFailure(target, ex, status);
        }
    }

    private void reloadTameworkConfigTarget(@Nonnull AssetPack generatedPack,
                                            @Nonnull String target,
                                            @Nonnull Set<String> itemFeatureConfigTargets,
                                            @Nonnull AssetPatchStatus status) {
        reloadAssetStoreTarget(generatedPack, target, status);
        if (tameworkConfigReloadAdapter.supportsItemFeatureConfig(target)) {
            itemFeatureConfigTargets.add(target);
        }
    }

    private void reloadNpcBuilders(@Nonnull AssetPack generatedPack, @Nonnull AssetPatchStatus status) {
        try {
            npcBuilderReloadAdapter.load(generatedPack);
            status.addHotReloadedTarget("Server/NPC/Roles/*");
        } catch (RuntimeException ex) {
            markReloadFailure("Server/NPC/Roles/*", ex, status);
        }
    }

    private void reloadTameworkItemFeatureConfigs(@Nonnull Collection<String> targets,
                                                  @Nonnull AssetPatchStatus status) {
        if (targets.isEmpty()) {
            return;
        }
        try {
            if (!tameworkConfigReloadAdapter.reloadItemFeatureConfigs(targets)) {
                targets.forEach(status::addRestartRequiredTarget);
                return;
            }
            status.addHotReloadedTarget("Server/Tamework/Items/*");
        } catch (RuntimeException ex) {
            targets.forEach(status::addRestartRequiredTarget);
            String message = "Failed to reload Tamework item feature configs from generated patches: " + ex.getMessage();
            status.addFailed(message);
            plugin.getLogger().at(Level.WARNING).withCause(ex).log(message);
        }
    }

    private void markReloadFailure(@Nonnull String target,
                                   @Nonnull RuntimeException ex,
                                   @Nonnull AssetPatchStatus status) {
        status.addRestartRequiredTarget(target);
        String message = "Failed to hot-reload generated patch target " + target + "; restart required.";
        status.addFailed(message);
        plugin.getLogger().at(Level.WARNING).withCause(ex).log(message);
    }

    interface NpcBuilderReloadAdapter {
        void load(@Nonnull AssetPack generatedPack);
    }

    interface AssetStoreReloadAdapter {
        boolean reload(@Nonnull AssetPack generatedPack, @Nonnull String target);
    }

    interface TameworkConfigReloadAdapter {
        boolean supportsItemFeatureConfig(@Nonnull String target);

        boolean reloadItemFeatureConfigs(@Nonnull Collection<String> targets);
    }

    private static final class NpcPluginBuilderReloadAdapter implements NpcBuilderReloadAdapter {
        @Override
        public void load(@Nonnull AssetPack generatedPack) {
            NPCPlugin npcPlugin = NPCPlugin.get();
            if (npcPlugin != null) {
                npcPlugin.getBuilderManager().loadBuilders(generatedPack, true);
            }
        }
    }

    private static final class HytaleAssetStoreReloadAdapter implements AssetStoreReloadAdapter {
        @Override
        public boolean reload(@Nonnull AssetPack generatedPack, @Nonnull String target) {
            AssetStore<?, ?, ?> store = findStore(target);
            if (store == null) {
                return false;
            }
            Path generatedFile = generatedPack.getRoot()
                    .resolve(AssetPatchDefinition.normalizeAssetPath(target))
                    .toAbsolutePath()
                    .normalize();
            reloadStorePath(store, generatedPack.getName(), generatedFile, Files.exists(generatedFile));
            return true;
        }

        private static AssetStore<?, ?, ?> findStore(@Nonnull String target) {
            String normalizedTarget = AssetPatchDefinition.normalizeAssetPath(target);
            String storeRelativeTarget = normalizedTarget.startsWith("Server/")
                    ? normalizedTarget.substring("Server/".length())
                    : normalizedTarget;
            Map<Class<? extends JsonAssetWithMap>, AssetStore<?, ?, ?>> storeMap = AssetRegistry.getStoreMap();
            for (AssetStore<?, ?, ?> store : storeMap.values()) {
                if (matchesStore(storeRelativeTarget, store)) {
                    return store;
                }
            }
            return null;
        }

        private static boolean matchesStore(@Nonnull String storeRelativeTarget, @Nonnull AssetStore<?, ?, ?> store) {
            String storePath = normalizeStorePart(store.getPath());
            String extension = normalizeExtension(store.getExtension());
            if (storePath.isEmpty() || extension.isEmpty()) {
                return false;
            }
            String lowerTarget = storeRelativeTarget.toLowerCase(Locale.ROOT);
            String lowerPath = storePath.toLowerCase(Locale.ROOT);
            String lowerExtension = extension.toLowerCase(Locale.ROOT);
            return lowerTarget.startsWith(lowerPath + "/") && lowerTarget.endsWith("." + lowerExtension);
        }

        private static String normalizeStorePart(String value) {
            if (value == null) {
                return "";
            }
            String normalized = value.trim().replace('\\', '/');
            while (normalized.startsWith("/")) {
                normalized = normalized.substring(1);
            }
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return normalized;
        }

        private static String normalizeExtension(String value) {
            String normalized = normalizeStorePart(value);
            while (normalized.startsWith(".")) {
                normalized = normalized.substring(1);
            }
            return normalized;
        }

        @SuppressWarnings({ "rawtypes", "unchecked" })
        private static void reloadStorePath(@Nonnull AssetStore store,
                                            @Nonnull String packName,
                                            @Nonnull Path generatedFile,
                                            boolean exists) {
            if (exists) {
                store.loadAssetsFromPaths(packName, List.of(generatedFile), AssetUpdateQuery.DEFAULT);
                return;
            }
            store.removeAssetWithPaths(packName, List.of(generatedFile), AssetUpdateQuery.DEFAULT);
        }
    }

    private static final class PluginTameworkConfigReloadAdapter implements TameworkConfigReloadAdapter {
        @Override
        public boolean supportsItemFeatureConfig(@Nonnull String target) {
            String normalized = AssetPatchDefinition.normalizeAssetPath(target);
            return normalized.startsWith("Server/Tamework/Items/Spawners/")
                    || normalized.startsWith("Server/Tamework/Items/Naming/")
                    || normalized.startsWith("Server/Tamework/Items/Commands/");
        }

        @Override
        public boolean reloadItemFeatureConfigs(@Nonnull Collection<String> targets) {
            Tamework plugin = Tamework.getInstance();
            if (plugin == null) {
                return false;
            }
            plugin.reloadItemFeatureConfigs();
            return true;
        }
    }
}
