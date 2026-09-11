package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwDynamicIconConfig;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.AssetUpdateQuery;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.bson.BsonDocument;

/** Installs real decoded icon assets for consumer tests without starting a server. */
final class DynamicIconTestAssets implements AutoCloseable {
    private final Field storeField;
    private final Object previous;

    DynamicIconTestAssets(String json) throws Exception {
        storeField = TwDynamicIconConfig.class.getDeclaredField("ASSET_STORE");
        storeField.setAccessible(true);
        previous = storeField.get(null);
        replace(json);
    }

    void replace(String json) throws Exception {
        storeField.set(null, new MemoryStore(new DefaultAssetMap<>(Map.of("test-icons", config(json)))));
        TwDynamicIconConfig.clearRoleCache();
    }

    static TwDynamicIconConfig config(String json) throws Exception {
        TwDynamicIconConfig config = TwDynamicIconConfig.CODEC.decode(BsonDocument.parse(json), new ExtraInfo());
        Field id = TwDynamicIconConfig.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(config, "test-icons");
        return config;
    }

    @Override public void close() throws Exception {
        storeField.set(null, previous);
        TwDynamicIconConfig.clearRoleCache();
    }

    private static final class MemoryStore extends AssetStore<String,
            TwDynamicIconConfig, DefaultAssetMap<String, TwDynamicIconConfig>> {
        MemoryStore(DefaultAssetMap<String, TwDynamicIconConfig> map) { super(new Builder(map)); }
        @Override protected com.hypixel.hytale.event.IEventBus getEventBus() { return null; }
        @Override public void addFileMonitor(String pack, Path path) { }
        @Override public void removeFileMonitor(Path path) { }
        @Override protected void handleRemoveOrUpdate(Set<String> removed,
                Map<String, TwDynamicIconConfig> changed, AssetUpdateQuery query) { }

        private static final class Builder extends AssetStore.Builder<String,
                TwDynamicIconConfig, DefaultAssetMap<String, TwDynamicIconConfig>, Builder> {
            private final DefaultAssetMap<String, TwDynamicIconConfig> map;
            Builder(DefaultAssetMap<String, TwDynamicIconConfig> map) {
                super(String.class, TwDynamicIconConfig.class, map);
                this.map = map;
                setPath("Tamework/DynamicIcons");
                setCodec(TwDynamicIconConfig.CODEC);
                setKeyFunction(TwDynamicIconConfig::getId);
            }
            @Override public AssetStore<String, TwDynamicIconConfig,
                    DefaultAssetMap<String, TwDynamicIconConfig>> build() { return new MemoryStore(map); }
        }
    }
}
