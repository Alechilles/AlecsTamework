package com.alechilles.alecstamework.assets;

import java.lang.reflect.Method;
import java.util.logging.Level;

import javax.annotation.Nonnull;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.asset.AssetPackRegisterEvent;

/**
 * Makes Tamework's embedded asset pack available as a read-only Asset Editor source.
 */
public final class TameworkAssetEditorPackService {

    private static final String ASSET_EDITOR_PLUGIN_CLASS = "com.hypixel.hytale.builtin.asseteditor.AssetEditorPlugin";

    private final Tamework plugin;

    public TameworkAssetEditorPackService(@Nonnull Tamework plugin) {
        this.plugin = plugin;
    }

    /**
     * Ensures Asset Editor sees Tamework's embedded jar pack as a read-only source.
     */
    public void ensurePackVisible() {
        AssetModule assetModule = AssetModule.get();
        if (assetModule == null) {
            plugin.getLogger().at(Level.WARNING).log(
                    "Tamework asset editor sync: AssetModule unavailable."
            );
            return;
        }

        String packId = new PluginIdentifier(plugin.getManifest()).toString();
        AssetPack pack = assetModule.getAssetPack(packId);
        if (pack == null) {
            plugin.getLogger().at(Level.WARNING).log(
                    "Tamework asset editor sync: pack '" + packId + "' not found."
            );
            return;
        }

        // Asset Editor keeps its own data-source map. Use reflection to call its internal
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
}
