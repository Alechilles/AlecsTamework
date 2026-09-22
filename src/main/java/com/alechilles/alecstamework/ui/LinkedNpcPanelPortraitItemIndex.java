package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Immutable item-icon lookup used while binding companion portrait cards.
 *
 * <p>Item asset events rebuild the index. The asset-store identity check also makes test and
 * runtime asset-store replacement safe without retaining mutable item assets or entities.</p>
 */
public final class LinkedNpcPanelPortraitItemIndex {
    private static final Object LOCK = new Object();
    private static volatile Snapshot snapshot = Snapshot.empty();

    private LinkedNpcPanelPortraitItemIndex() {
    }

    /** Rebuilds the detached icon-to-item lookup after item assets change. */
    public static void refresh() {
        synchronized (LOCK) {
            rebuild(Item.getAssetStore());
        }
    }

    /** Releases the detached lookup during plugin shutdown. */
    public static void clear() {
        synchronized (LOCK) {
            snapshot = Snapshot.empty();
        }
    }

    @Nullable
    static String itemIdFor(@Nullable String icon) {
        if (icon == null || icon.isBlank()) {
            return null;
        }
        Object assetStore = Item.getAssetStore();
        Snapshot current = snapshot;
        if (current.assetStore() != assetStore) {
            synchronized (LOCK) {
                current = snapshot;
                if (current.assetStore() != assetStore) {
                    rebuild(assetStore);
                    current = snapshot;
                }
            }
        }
        return current.itemIdsByIcon().get(icon);
    }

    private static void rebuild(@Nullable Object assetStore) {
        Map<String, String> itemIdsByIcon = new LinkedHashMap<>();
        if (assetStore != null) {
            var assetMap = Item.getAssetMap();
            if (assetMap != null && assetMap.getAssetMap() != null) {
                for (var asset : assetMap.getAssetMap().entrySet()) {
                    Item item = asset.getValue();
                    if (item == null) {
                        continue;
                    }
                    String icon = item.getIcon();
                    if (icon == null) continue;
                    // Keep the same first matching item that the previous card-time scan used.
                    itemIdsByIcon.putIfAbsent(icon, asset.getKey());
                }
            }
        }
        snapshot = new Snapshot(assetStore, Map.copyOf(itemIdsByIcon));
    }

    private record Snapshot(@Nullable Object assetStore, Map<String, String> itemIdsByIcon) {
        private static Snapshot empty() {
            return new Snapshot(null, Map.of());
        }
    }
}
