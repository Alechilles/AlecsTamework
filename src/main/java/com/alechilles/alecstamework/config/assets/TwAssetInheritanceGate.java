package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import javax.annotation.Nullable;

/** Coordinates one fallback repair per invalidated config family. */
final class TwAssetInheritanceGate {
    private final Object lock = new Object();
    private volatile boolean dirty = true;

    void markDirty() {
        dirty = true;
    }

    <T extends TwParentFallbackAsset<T>> void repairIfDirty(
            @Nullable DefaultAssetMap<String, T> assetMap) {
        if (!dirty || assetMap == null || assetMap.getAssetMap() == null) {
            return;
        }
        synchronized (lock) {
            if (!dirty || assetMap.getAssetMap() == null) {
                return;
            }
            TwAssetInheritanceFallback.repairAll(assetMap);
            dirty = false;
        }
    }
}
