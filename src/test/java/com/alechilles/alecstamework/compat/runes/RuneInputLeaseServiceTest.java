package com.alechilles.alecstamework.compat.runes;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.assetstore.TestItemAssetStore;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import com.hypixel.hytale.server.core.inventory.container.filter.SlotFilter;
import com.hypixel.hytale.server.core.inventory.transaction.ActionType;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises item restoration after normal use, save/load, and interrupted slot writes. */
class RuneInputLeaseServiceTest {
    private final RuneInputLeaseService service = new RuneInputLeaseService(new TestFilters());
    private Field assetStore;
    private Object previousStore;

    @BeforeEach
    void items() throws Exception {
        assetStore = Item.class.getDeclaredField("ASSET_STORE");
        assetStore.setAccessible(true);
        previousStore = assetStore.get(null);
        assetStore.set(null, new TestItemAssetStore(new DefaultAssetMap<>(Map.of(
                "Weapon_Sword_Wood", new Item("Weapon_Sword_Wood"),
                "Weapon_Axe_Wood", new Item("Weapon_Axe_Wood"),
                "Rune_Fireball", new Item("Rune_Fireball"),
                RuneInputLeaseService.ABILITY2_RUNE, new Item(RuneInputLeaseService.ABILITY2_RUNE),
                RuneInputLeaseService.ABILITY3_RUNE, new Item(RuneInputLeaseService.ABILITY3_RUNE)))));
    }

    @AfterEach
    void restoreItems() throws Exception {
        assetStore.set(null, previousStore);
    }

    @Test
    void installsAndRestoresBothOriginalPrimaryStacks() {
        SimpleItemContainer slots = slots();
        ItemStack original2 = new ItemStack("Weapon_Sword_Wood", 1);
        ItemStack original3 = new ItemStack("Rune_Fireball", 1);
        slots.replaceItemStackInSlot((short) 0, null, original2);
        slots.replaceItemStackInSlot((short) 3, null, original3);

        RuneInputLeaseComponent lease = service.capture(slots, true, true);
        assertNotNull(lease);
        assertTrue(service.reconcile(slots, lease, true, true));
        assertEquals(RuneInputLeaseService.ABILITY2_RUNE, slots.getItemStack((short) 0).getItemId());
        assertEquals(RuneInputLeaseService.ABILITY3_RUNE, slots.getItemStack((short) 3).getItemId());

        assertTrue(service.restore(slots, lease));
        assertEquals(original2, slots.getItemStack((short) 0));
        assertEquals(original3, slots.getItemStack((short) 3));
        assertTrue(lease.empty());
    }

    @Test
    void restoresPartialInstallAfterCloneAndPlayerSaveRoundtrip() {
        SimpleItemContainer slots = slots();
        ItemStack original = new ItemStack("Rune_Fireball", 1)
                .withMetadata("RuneLeaseTest", Codec.STRING, "original-owner");
        slots.replaceItemStackInSlot((short) 0, null, original);
        RuneInputLeaseComponent lease = service.capture(slots, true, true);
        assertNotNull(lease);
        // A save can happen after the lease is recorded but before one rune is written.
        slots.replaceItemStackInSlot((short) 3, null,
                new ItemStack(RuneInputLeaseService.ABILITY3_RUNE));

        SimpleItemContainer loadedSlots = SimpleItemContainer.CODEC.decode(
                SimpleItemContainer.CODEC.encode(slots, new ExtraInfo()), new ExtraInfo());
        RuneInputLeaseComponent loaded = RuneInputLeaseComponent.CODEC.decode(
                RuneInputLeaseComponent.CODEC.encode(lease.clone(), new ExtraInfo()),
                new ExtraInfo());
        assertTrue(service.restore(loadedSlots, loaded));
        assertEquals(original, loadedSlots.getItemStack((short) 0));
        assertEquals("original-owner", loadedSlots.getItemStack((short) 0)
                .getFromMetadataOrNull("RuneLeaseTest", Codec.STRING));
        assertNull(loadedSlots.getItemStack((short) 3));
        assertTrue(loaded.empty());
    }

    @Test
    void conflictingStackIsNeverOverwrittenAndOriginalRemainsRecoverable() {
        SimpleItemContainer slots = slots();
        ItemStack original = new ItemStack("Weapon_Sword_Wood", 1);
        ItemStack unexpected = new ItemStack("Weapon_Axe_Wood", 1);
        slots.replaceItemStackInSlot((short) 0, null, original);
        RuneInputLeaseComponent lease = service.capture(slots, true, false);
        assertTrue(service.reconcile(slots, lease, true, false));

        slots.replaceItemStackInSlot((short) 0, slots.getItemStack((short) 0), unexpected);
        assertFalse(service.restore(slots, lease));
        assertEquals(unexpected, slots.getItemStack((short) 0));
        assertTrue(lease.pending(0));
        assertEquals(original, lease.original(0));

        slots.replaceItemStackInSlot((short) 0, unexpected,
                new ItemStack(RuneInputLeaseService.ABILITY2_RUNE));
        assertTrue(service.restore(slots, lease));
        assertEquals(original, slots.getItemStack((short) 0));
    }

    @Test
    void installedRuneRejectsNormalAddRemoveAndDropThenUnlocks() {
        SimpleItemContainer slots = slots();
        RuneInputLeaseComponent lease = service.capture(slots, true, false);
        assertTrue(service.reconcile(slots, lease, true, false));

        assertFalse(slots.removeItemStackFromSlot((short) 0).succeeded());
        assertFalse(slots.setItemStackForSlot((short) 0, new ItemStack("Rune_Fireball")).succeeded());
        // Drop uses its own filter; the test filter observes the native operation directly.
        assertTrue(slots.dropAllItemStacks().isEmpty());

        assertTrue(service.restore(slots, lease));
        assertTrue(slots.setItemStackForSlot((short) 0, new ItemStack("Rune_Fireball")).succeeded());
    }

    @Test
    void rejectedInstallKeepsOriginalAndRollsBackSlotLocks() {
        RejectingContainer slots = new RejectingContainer();
        ItemStack original = new ItemStack("Weapon_Sword_Wood", 1);
        slots.replaceItemStackInSlot((short) 0, null, original);
        RuneInputLeaseComponent lease = service.capture(slots, true, false);
        slots.rejectNext = true;

        assertFalse(service.reconcile(slots, lease, true, false));
        assertEquals(original, slots.getItemStack((short) 0));
        assertTrue(lease.pending(0));
        assertTrue(slots.removeItemStackFromSlot((short) 0).succeeded());
    }

    private static SimpleItemContainer slots() {
        return new SimpleItemContainer((short) 6);
    }

    private static final class TestFilters implements RuneInputLeaseService.Filters {
        @Override public boolean canLock(ItemContainer container) { return container instanceof SimpleItemContainer; }

        @Override public void lock(ItemContainer container, short slot) {
            container.setSlotFilter(FilterActionType.ADD, slot, SlotFilter.DENY);
            container.setSlotFilter(FilterActionType.REMOVE, slot, SlotFilter.DENY);
            container.setSlotFilter(FilterActionType.DROP, slot, SlotFilter.DENY);
        }

        @Override public void unlock(ItemContainer container, short slot) {
            container.setSlotFilter(FilterActionType.ADD, slot, SlotFilter.ALLOW);
            container.setSlotFilter(FilterActionType.REMOVE, slot, null);
            container.setSlotFilter(FilterActionType.DROP, slot, null);
        }
    }

    private static final class RejectingContainer extends SimpleItemContainer {
        private boolean rejectNext;
        RejectingContainer() { super((short) 6); }

        @Override
        public ItemStackSlotTransaction replaceItemStackInSlot(short slot,
                                                                ItemStack expected,
                                                                ItemStack replacement) {
            if (rejectNext) {
                rejectNext = false;
                ItemStack current = getItemStack(slot);
                return new ItemStackSlotTransaction(false, ActionType.REPLACE, slot,
                        current, current, null, true, false, false, false,
                        replacement, replacement);
            }
            return super.replaceItemStackInSlot(slot, expected, replacement);
        }
    }
}
