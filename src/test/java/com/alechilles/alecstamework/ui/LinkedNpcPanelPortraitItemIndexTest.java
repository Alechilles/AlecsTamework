package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.assetstore.TestItemAssetStore;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LinkedNpcPanelPortraitItemIndexTest {
    @Test
    void reusesOnePortraitIndexUntilItemAssetsAreReplacedOrRemoved() throws Exception {
        Field storeField = Item.class.getDeclaredField("ASSET_STORE");
        storeField.setAccessible(true);
        Object previousStore = storeField.get(null);
        try {
            AtomicInteger iconReads = new AtomicInteger();
            Map<String, Item> firstItems = new LinkedHashMap<>();
            firstItems.put("First", countedItem("First", "Icons/Animals/Sheep.png", iconReads));
            firstItems.put("Duplicate", item("Duplicate", "Icons/Animals/Sheep.png"));
            DefaultAssetMap<String, Item> assetMap = new DefaultAssetMap<>(firstItems);
            storeField.set(null, new TestItemAssetStore(assetMap));
            LinkedNpcPanelPortraitItemIndex.clear();
            LinkedNpcPanelPortraitItemIndex.refresh();

            assertEquals("First", LinkedNpcPanelPortraitItemIndex.itemIdFor("Icons/Animals/Sheep.png"));
            int initialIconReads = iconReads.get();
            for (int i = 0; i < 100; i++) {
                assertEquals("First", LinkedNpcPanelPortraitItemIndex.itemIdFor("Icons/Animals/Sheep.png"));
            }
            assertEquals(initialIconReads, iconReads.get(),
                    "Repeated card binding must reuse the detached item-icon index.");

            firstItems.clear();
            firstItems.put("Replacement", item("Replacement", "Icons/Animals/Sheep.png"));
            LinkedNpcPanelPortraitItemIndex.refresh();
            assertEquals("Replacement", LinkedNpcPanelPortraitItemIndex.itemIdFor("Icons/Animals/Sheep.png"));

            firstItems.clear();
            LinkedNpcPanelPortraitItemIndex.refresh();
            assertNull(LinkedNpcPanelPortraitItemIndex.itemIdFor("Icons/Animals/Sheep.png"),
                    "Removed item assets must hide a portrait instead of retaining a stale item ID.");

            storeField.set(null, new TestItemAssetStore(new DefaultAssetMap<>()));
            assertNull(LinkedNpcPanelPortraitItemIndex.itemIdFor("Icons/Animals/Sheep.png"),
                    "Replacing a fixture asset store must not retain its previous portrait index.");
        } finally {
            storeField.set(null, previousStore);
            LinkedNpcPanelPortraitItemIndex.clear();
        }
    }

    private static Item item(String id, String icon) throws Exception {
        Item item = new Item(id);
        Field iconField = Item.class.getDeclaredField("icon");
        iconField.setAccessible(true);
        iconField.set(item, icon);
        return item;
    }

    private static Item countedItem(String id, String icon, AtomicInteger iconReads) throws Exception {
        Item item = new Item(id) {
            @Override
            public String getIcon() {
                iconReads.incrementAndGet();
                return super.getIcon();
            }
        };
        Field iconField = Item.class.getDeclaredField("icon");
        iconField.setAccessible(true);
        iconField.set(item, icon);
        return item;
    }
}
