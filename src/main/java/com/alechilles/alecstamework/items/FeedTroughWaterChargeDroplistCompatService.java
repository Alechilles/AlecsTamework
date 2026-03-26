package com.alechilles.alecstamework.items;

import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDrop;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDropList;
import com.hypixel.hytale.server.core.asset.type.item.config.container.SingleItemDropContainer;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * Ensures legacy feed-trough water charge metadata droplist ids resolve on pre-release.
 */
public final class FeedTroughWaterChargeDroplistCompatService {
    private static final String ASSET_SOURCE_ID = "Alechilles:Alec's Tamework!";
    private static final String CHARGE_DROPLIST_PREFIX = "tw_water_charges:";
    private static final String PLACEHOLDER_ITEM_ID = "Tw_Feed_Trough";

    private boolean reconciling;

    public synchronized void reconcile() {
        if (reconciling) {
            return;
        }
        reconciling = true;
        try {
            DefaultAssetMap<String, ItemDropList> dropListMap = ItemDropList.getAssetMap();
            AssetStore<String, ItemDropList, DefaultAssetMap<String, ItemDropList>> dropListStore =
                    ItemDropList.getAssetStore();
            if (dropListMap == null || dropListStore == null || dropListMap.getAssetMap() == null) {
                return;
            }
            List<ItemDropList> compatEntries = new ArrayList<>();
            for (int charges = 1; charges <= FeedTroughWaterStateService.MAX_WATER_CHARGES; charges++) {
                String id = CHARGE_DROPLIST_PREFIX + charges;
                if (dropListMap.getAsset(id) != null) {
                    continue;
                }
                compatEntries.add(newCompatDroplist(id));
            }
            if (!compatEntries.isEmpty()) {
                dropListStore.loadAssets(ASSET_SOURCE_ID, compatEntries);
            }
        } finally {
            reconciling = false;
        }
    }

    @Nonnull
    private ItemDropList newCompatDroplist(@Nonnull String id) {
        ItemDrop placeholderDrop = new ItemDrop(PLACEHOLDER_ITEM_ID, null, 1, 1);
        return new ItemDropList(id, new SingleItemDropContainer(placeholderDrop, 100.0));
    }
}
