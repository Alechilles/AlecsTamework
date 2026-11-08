package com.alechilles.alecstamework.items;

import com.hypixel.hytale.assetstore.TestItemAssetStore;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NamingItemSlotsTest {
    private static final String NAMETAG = "Alecs_Nametag";
    private static final int COOLDOWN_MS = 60_000;

    private Field itemAssets;
    private Object previousItemAssets;

    /** An {@link ItemStack} looks its item up in the asset store. */
    @BeforeEach
    void items() throws Exception {
        itemAssets = Item.class.getDeclaredField("ASSET_STORE");
        itemAssets.setAccessible(true);
        previousItemAssets = itemAssets.get(null);
        itemAssets.set(null, new TestItemAssetStore(new DefaultAssetMap<>(Map.of(NAMETAG, new Item(NAMETAG)))));
    }

    @AfterEach
    void restoreItems() throws Exception {
        itemAssets.set(null, previousItemAssets);
    }

    @Test
    void usingAStackConsumesOneStartsItsCooldownAndTheNextSearchSkipsIt() {
        SimpleItemContainer container = new SimpleItemContainer((short) 4);
        container.setItemStackForSlot((short) 1, new ItemStack(NAMETAG, 2));
        container.setItemStackForSlot((short) 3, new ItemStack(NAMETAG, 1));

        NamingItemSlots.Slot first = NamingItemSlots.findIn(container, NamingItemSlotsTest::usable);
        assertNotNull(first);
        assertEquals(1, first.index());

        NamingItemSlots.use(container, first.index(), true, COOLDOWN_MS);

        ItemStack spent = container.getItemStack((short) 1);
        assertEquals(1, spent.getQuantity());
        assertTrue(NamingItemSlots.isCooldownActive(spent, COOLDOWN_MS));
        NamingItemSlots.Slot next = NamingItemSlots.findIn(container, NamingItemSlotsTest::usable);
        assertNotNull(next);
        assertEquals(3, next.index());
    }

    private static boolean usable(ItemStack stack) {
        return NAMETAG.equals(stack.getItemId()) && !NamingItemSlots.isCooldownActive(stack, COOLDOWN_MS);
    }
}
