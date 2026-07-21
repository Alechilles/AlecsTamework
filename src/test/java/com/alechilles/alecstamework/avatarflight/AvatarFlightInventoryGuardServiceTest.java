package com.alechilles.alecstamework.avatarflight;

import com.hypixel.hytale.server.core.event.events.ecs.InventoryActiveSlotRequestEvent;
import com.hypixel.hytale.server.core.event.events.ecs.InventorySetActiveSlotEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvatarFlightInventoryGuardServiceTest {
    @Test
    void findsTalismanInHotbar() throws ReflectiveOperationException {
        SimpleItemContainer hotbar = new SimpleItemContainer((short) 5);
        hotbar.setItemStackForSlot((short) 3,
                itemStack(AvatarFlightInventoryGuardService.TALISMAN_ITEM_ID));

        assertEquals(3, AvatarFlightInventoryGuardService.findTalismanSlot(hotbar));
    }

    @Test
    void auxiliaryClientSelectionChangesAreLockedWhileServerRestorationRemainsAllowed() {
        AvatarFlightComponent flight = new AvatarFlightComponent();
        flight.setLockedHotbarSlot(3);

        assertTrue(AvatarFlightInventoryGuardSystem.shouldCancel(flight,
                request(InventoryComponent.UTILITY_SECTION_ID, false)));
        assertTrue(AvatarFlightInventoryGuardSystem.shouldCancel(flight,
                request(InventoryComponent.TOOLS_SECTION_ID, false)));
        assertFalse(AvatarFlightInventoryGuardSystem.shouldCancel(flight,
                request(InventoryComponent.HOTBAR_SECTION_ID, false)));
        assertFalse(AvatarFlightInventoryGuardSystem.shouldCancel(flight,
                request(InventoryComponent.UTILITY_SECTION_ID, true)));
    }

    @Test
    void hotbarChangesRestoreTheLockedTalismanSlot() {
        AvatarFlightComponent flight = new AvatarFlightComponent();
        flight.setLockedHotbarSlot(3);

        assertTrue(AvatarFlightHotbarGuardSystem.shouldRestore(flight,
                new InventorySetActiveSlotEvent(InventoryComponent.HOTBAR_SECTION_ID, 3, (byte) 4)));
        assertFalse(AvatarFlightHotbarGuardSystem.shouldRestore(flight,
                new InventorySetActiveSlotEvent(InventoryComponent.HOTBAR_SECTION_ID, 4, (byte) 3)));
    }

    private static InventoryActiveSlotRequestEvent request(int sectionId, boolean serverRequest) {
        return new InventoryActiveSlotRequestEvent(sectionId, 3, (byte) 4, serverRequest);
    }

    private static ItemStack itemStack(String itemId) throws ReflectiveOperationException {
        ItemStack itemStack = (ItemStack) unsafe().allocateInstance(ItemStack.class);
        setField(ItemStack.class, itemStack, "itemId", itemId);
        setField(ItemStack.class, itemStack, "quantity", 1);
        return itemStack;
    }

    private static void setField(Class<?> type, Object target, String fieldName, Object value)
            throws ReflectiveOperationException {
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Unsafe unsafe() throws ReflectiveOperationException {
        Field unsafe = Unsafe.class.getDeclaredField("theUnsafe");
        unsafe.setAccessible(true);
        return (Unsafe) unsafe.get(null);
    }
}
