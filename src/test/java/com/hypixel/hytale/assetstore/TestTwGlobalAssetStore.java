package com.hypixel.hytale.assetstore;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.event.IEventBus;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** Test-only in-memory asset store that does not require a bootstrapped Hytale server. */
public final class TestTwGlobalAssetStore
        extends AssetStore<String, TwGlobalConfig, DefaultAssetMap<String, TwGlobalConfig>> {
    public TestTwGlobalAssetStore(DefaultAssetMap<String, TwGlobalConfig> map) {
        super(new StoreBuilder(map));
    }

    @Override
    protected IEventBus getEventBus() {
        return null;
    }

    @Override
    public void addFileMonitor(String assetPack, Path path) {
    }

    @Override
    public void removeFileMonitor(Path path) {
    }

    @Override
    protected void handleRemoveOrUpdate(Set<String> removed,
                                        Map<String, TwGlobalConfig> changed,
                                        AssetUpdateQuery query) {
    }

    /** Builder used only to satisfy the base store's immutable construction contract. */
    private static final class StoreBuilder extends AssetStore.Builder<
            String,
            TwGlobalConfig,
            DefaultAssetMap<String, TwGlobalConfig>,
            StoreBuilder> {
        private final DefaultAssetMap<String, TwGlobalConfig> map;

        private StoreBuilder(DefaultAssetMap<String, TwGlobalConfig> map) {
            super(String.class, TwGlobalConfig.class, map);
            this.map = map;
            setPath("Tamework/Global");
            setCodec(TwGlobalConfig.CODEC);
            setKeyFunction(TwGlobalConfig::getId);
        }

        @Override
        public AssetStore<String, TwGlobalConfig, DefaultAssetMap<String, TwGlobalConfig>> build() {
            return new TestTwGlobalAssetStore(map);
        }
    }
}
