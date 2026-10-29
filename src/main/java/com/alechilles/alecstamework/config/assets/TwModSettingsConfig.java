package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One group of server settings declared by a mod, shown in the Mod Settings tab under the mod's
 * name. Stored under Server/Tamework/ModSettings. Patches read the values through Patchwork
 * {@code Setting} conditions and {@code $Setting} values using each setting's Id.
 */
public final class TwModSettingsConfig
        implements JsonAssetWithMap<String, DefaultAssetMap<String, TwModSettingsConfig>> {
    private static final TwModSetting[] EMPTY_SETTINGS = new TwModSetting[0];

    public static final AssetBuilderCodec<String, TwModSettingsConfig> CODEC = AssetBuilderCodec.builder(
            TwModSettingsConfig.class,
            TwModSettingsConfig::new,
            Codec.STRING,
            (asset, id) -> asset.id = id,
            asset -> asset.id,
            (asset, data) -> asset.data = data,
            asset -> asset.data
    )
            .documentation("A group of server settings that a mod adds to the Mod Settings tab of /tw settings.")
            .<String>append(
                    new KeyedCodec<>("Label", Codec.STRING),
                    (asset, value) -> asset.label = value,
                    asset -> asset.label
            )
            .documentation("Language key for the group heading, such as Mob Spawning.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("Order", Codec.INTEGER),
                    (asset, value) -> asset.order = value == null ? 0 : value,
                    asset -> asset.order
            )
            .documentation("Sort order among this mod's groups; lower numbers come first.")
            .add()
            .<TwModSetting[]>append(
                    new KeyedCodec<>("Settings", new ArrayCodec<>(TwModSetting.CODEC, TwModSetting[]::new)),
                    (asset, value) -> asset.settings = value == null ? EMPTY_SETTINGS : value,
                    asset -> asset.settings
            )
            .documentation("Settings in this group, in display order.")
            .add()
            .build();

    private static AssetStore<String, TwModSettingsConfig, DefaultAssetMap<String, TwModSettingsConfig>> ASSET_STORE;

    private AssetExtraInfo.Data data;
    String id;
    private String label;
    private int order;
    TwModSetting[] settings = EMPTY_SETTINGS;

    TwModSettingsConfig() {
    }

    @Nullable
    public static AssetStore<String, TwModSettingsConfig, DefaultAssetMap<String, TwModSettingsConfig>> getAssetStore() {
        if (ASSET_STORE == null) {
            ASSET_STORE = AssetRegistry.getAssetStore(TwModSettingsConfig.class);
        }
        return ASSET_STORE;
    }

    @Nullable
    public static DefaultAssetMap<String, TwModSettingsConfig> getAssetMap() {
        AssetStore<String, TwModSettingsConfig, DefaultAssetMap<String, TwModSettingsConfig>> store = getAssetStore();
        return store == null ? null : (DefaultAssetMap<String, TwModSettingsConfig>) store.getAssetMap();
    }

    public String getId() {
        return id;
    }

    @Nullable
    public String getLabel() {
        return label == null || label.isBlank() ? null : label.trim();
    }

    public int getOrder() {
        return order;
    }

    @Nonnull
    public TwModSetting[] getSettings() {
        return settings == null ? EMPTY_SETTINGS : settings;
    }
}
