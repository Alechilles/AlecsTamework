package com.alechilles.alecstamework.assets;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

import javax.annotation.Nonnull;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.asset.AssetPackRegisterEvent;
import com.hypixel.hytale.server.core.asset.LoadAssetEvent;

/**
 * Coordinates Tamework asset-pack registration/order behavior and editor visibility.
 */
public final class TameworkAssetPackCoordinator {

    private static final short EARLY_ASSET_PACK_ORDER_PRIORITY = (short) -40;
    private static final String BASE_ASSET_PACK_ID = "Hytale:Hytale";
    private static final boolean ENABLE_EARLY_ASSET_PACK_ORDERING = true;
    private static final String ASSET_EDITOR_PLUGIN_CLASS = "com.hypixel.hytale.builtin.asseteditor.AssetEditorPlugin";

    private final Tamework plugin;

    public TameworkAssetPackCoordinator(@Nonnull Tamework plugin) {
        this.plugin = plugin;
    }

    /**
     * Registers the early load hook that keeps the Tamework pack directly after base assets.
     */
    public void registerEarlyAssetPackOrderingHook() {
        if (!ENABLE_EARLY_ASSET_PACK_ORDERING) {
            plugin.getLogger().at(Level.INFO).log(
                    "Tamework asset pack ordering: early reorder hook disabled for compatibility diagnostics."
            );
            return;
        }
        if (plugin.getEventRegistry() == null) {
            return;
        }
        plugin.getEventRegistry().register(
                EARLY_ASSET_PACK_ORDER_PRIORITY,
                LoadAssetEvent.class,
                this::onEarlyAssetLoad
        );
    }

    /**
     * Ensures AssetEditor sees Tamework's embedded jar pack as a read-only source.
     */
    public void ensureAssetEditorPackVisible() {
        AssetModule assetModule = AssetModule.get();
        if (assetModule == null) {
            plugin.getLogger().at(Level.WARNING).log(
                    "Tamework asset editor sync: AssetModule unavailable."
            );
            return;
        }

        String packId = resolvePackId();
        AssetPack pack = assetModule.getAssetPack(packId);
        if (pack == null) {
            plugin.getLogger().at(Level.WARNING).log(
                    "Tamework asset editor sync: pack '" + packId + "' not found."
            );
            return;
        }

        // AssetEditor keeps its own data-source map. Use reflection to call its internal
        // pack-registration flow so the jar-backed Tamework pack appears in the UI.
        try {
            Class<?> assetEditorPluginClass = Class.forName(ASSET_EDITOR_PLUGIN_CLASS);
            Method getMethod = assetEditorPluginClass.getMethod("get");
            Object assetEditorPlugin = getMethod.invoke(null);
            if (assetEditorPlugin == null) {
                plugin.getLogger().at(Level.INFO).log(
                        "Tamework asset editor sync: AssetEditor plugin instance unavailable."
                );
                return;
            }

            Method getDataSourceForPack = assetEditorPluginClass.getMethod("getDataSourceForPack", String.class);
            if (getDataSourceForPack.invoke(assetEditorPlugin, packId) != null) {
                return;
            }

            Method onRegisterAssetPack =
                    assetEditorPluginClass.getDeclaredMethod("onRegisterAssetPack", AssetPackRegisterEvent.class);
            onRegisterAssetPack.setAccessible(true);
            onRegisterAssetPack.invoke(assetEditorPlugin, new AssetPackRegisterEvent(pack));

            if (getDataSourceForPack.invoke(assetEditorPlugin, packId) != null) {
                plugin.getLogger().at(Level.INFO).log(
                        "Tamework asset editor sync: registered read-only data source for pack '" + packId + "'."
                );
                return;
            }

            plugin.getLogger().at(Level.WARNING).log(
                    "Tamework asset editor sync: failed to register data source for pack '" + packId + "'."
            );
        } catch (ClassNotFoundException ex) {
            plugin.getLogger().at(Level.INFO).log(
                    "Tamework asset editor sync: AssetEditor plugin class not found."
            );
        } catch (Exception ex) {
            plugin.getLogger().at(Level.WARNING).withCause(ex).log(
                    "Tamework asset editor sync: failed while registering pack in AssetEditor."
            );
        }
    }

    private void onEarlyAssetLoad(LoadAssetEvent event) {
        AssetModule assetModule = AssetModule.get();
        if (assetModule == null) {
            plugin.getLogger().at(Level.WARNING).log(
                    "Tamework asset pack ordering: AssetModule unavailable during LoadAssetEvent."
            );
            return;
        }

        String packId = resolvePackId();
        Path pluginPackPath = normalizePath(plugin.getFile());
        if (pluginPackPath == null) {
            plugin.getLogger().at(Level.WARNING).log(
                    "Tamework asset pack ordering: plugin file path unavailable during LoadAssetEvent."
            );
            return;
        }

        removeLegacyStandaloneAssetPack(assetModule, packId, pluginPackPath);

        AssetPack existingPack = assetModule.getAssetPack(packId);
        if (existingPack != null) {
            Path existingPackPath = normalizePath(existingPack.getPackLocation());
            if (!samePath(existingPackPath, pluginPackPath)) {
                plugin.getLogger().at(Level.INFO).log(
                        "Tamework asset pack ordering: replacing pre-registered pack '" + packId
                                + "' from " + existingPackPath + " with " + pluginPackPath + "."
                );
                assetModule.unregisterPack(packId);
                tryDeleteLegacyAssetsZip(existingPackPath, pluginPackPath);
            }
        }

        if (assetModule.getAssetPack(packId) == null) {
            try {
                assetModule.registerPack(packId, plugin.getFile(), plugin.getManifest(), AssetPack.PackSource.MODS);
            } catch (RuntimeException ex) {
                plugin.getLogger().at(Level.WARNING).withCause(ex)
                        .log("Tamework asset pack ordering: failed to register missing embedded pack '" + packId + "'.");
            }
        }

        List<AssetPack> packs = assetModule.getAssetPacks();
        int currentIndex = indexOfPack(packs, packId);
        if (currentIndex < 0) {
            plugin.getLogger().at(Level.WARNING).log(
                    "Tamework asset pack ordering: pack '" + packId + "' not found after registration attempt."
            );
            return;
        }

        int targetIndex = desiredTameworkPackIndex(packs);
        if (currentIndex == targetIndex) {
            plugin.getLogger().at(Level.INFO).log(
                    "Tamework asset pack ordering: pack '" + packId + "' already ordered at index " + currentIndex + "."
            );
            return;
        }

        AssetPack tameworkPack = packs.remove(currentIndex);
        if (currentIndex < targetIndex) {
            targetIndex--;
        }
        packs.add(targetIndex, tameworkPack);
        plugin.getLogger().at(Level.INFO).log(
                "Tamework asset pack ordering: moved pack '" + packId + "' from index "
                        + currentIndex + " to index " + targetIndex + "."
        );
    }

    private void removeLegacyStandaloneAssetPack(AssetModule assetModule, String packId, Path pluginPackPath) {
        String legacyPackId = packId + " (Assets)";
        AssetPack legacyPack = assetModule.getAssetPack(legacyPackId);
        if (legacyPack == null) {
            return;
        }
        Path legacyPackPath = normalizePath(legacyPack.getPackLocation());
        plugin.getLogger().at(Level.INFO).log(
                "Tamework asset pack ordering: removing legacy standalone pack '"
                        + legacyPackId + "' from " + legacyPackPath + "."
        );
        assetModule.unregisterPack(legacyPackId);
        tryDeleteLegacyAssetsZip(legacyPackPath, pluginPackPath);
    }

    private void tryDeleteLegacyAssetsZip(Path existingPackPath, Path pluginPackPath) {
        if (existingPackPath == null || !isLegacyAssetsZip(existingPackPath)) {
            return;
        }
        Path pluginDir = pluginPackPath.getParent();
        Path existingDir = existingPackPath.getParent();
        if (pluginDir == null || existingDir == null || !pluginDir.equals(existingDir)) {
            return;
        }
        try {
            if (Files.deleteIfExists(existingPackPath)) {
                plugin.getLogger().at(Level.INFO).log(
                        "Tamework asset pack ordering: deleted legacy assets archive " + existingPackPath + "."
                );
            }
        } catch (Exception ex) {
            plugin.getLogger().at(Level.WARNING).withCause(ex).log(
                    "Tamework asset pack ordering: failed to delete legacy assets archive " + existingPackPath + "."
            );
        }
    }

    private String resolvePackId() {
        return new PluginIdentifier(plugin.getManifest()).toString();
    }

    private boolean isLegacyAssetsZip(Path path) {
        String fileName = path.getFileName() == null
                ? ""
                : path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".zip")
                && fileName.contains("tamework")
                && fileName.contains("assets");
    }

    private Path normalizePath(Path path) {
        if (path == null) {
            return null;
        }
        return path.toAbsolutePath().normalize();
    }

    private boolean samePath(Path a, Path b) {
        if (a == null || b == null) {
            return false;
        }
        return a.equals(b);
    }

    private int desiredTameworkPackIndex(List<AssetPack> packs) {
        int basePackIndex = indexOfPack(packs, BASE_ASSET_PACK_ID);
        if (basePackIndex < 0) {
            return 0;
        }
        return basePackIndex + 1;
    }

    private int indexOfPack(List<AssetPack> packs, String packId) {
        for (int i = 0; i < packs.size(); i++) {
            AssetPack pack = packs.get(i);
            if (pack != null && packId.equals(pack.getName())) {
                return i;
            }
        }
        return -1;
    }
}

