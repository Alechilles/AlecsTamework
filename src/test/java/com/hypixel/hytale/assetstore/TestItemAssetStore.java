package com.hypixel.hytale.assetstore;

import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.event.IEventBus;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** Test-only item asset store used while Hytale validates inventory codec defaults. */
public final class TestItemAssetStore
        extends AssetStore<String, Item, DefaultAssetMap<String, Item>> {
    public TestItemAssetStore(DefaultAssetMap<String, Item> map) {
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
                                        Map<String, Item> changed,
                                        AssetUpdateQuery query) {
    }

    private static final class StoreBuilder extends AssetStore.Builder<
            String,
            Item,
            DefaultAssetMap<String, Item>,
            StoreBuilder> {
        private final DefaultAssetMap<String, Item> map;

        private StoreBuilder(DefaultAssetMap<String, Item> map) {
            super(String.class, Item.class, map);
            this.map = map;
            setPath("Item");
            setCodec(Item.CODEC);
            setKeyFunction(Item::getId);
        }

        @Override
        public AssetStore<String, Item, DefaultAssetMap<String, Item>> build() {
            return new TestItemAssetStore(map);
        }
    }
}
