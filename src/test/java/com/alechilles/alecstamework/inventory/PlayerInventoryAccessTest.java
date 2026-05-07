package com.alechilles.alecstamework.inventory;

import com.hypixel.hytale.server.core.inventory.InventoryComponent.Hotbar;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class PlayerInventoryAccessTest {
    @Test
    void readsActiveSlotItemAndContainerFromUpdate5HotbarComponent() throws ReflectiveOperationException {
        SimpleItemContainer container = new SimpleItemContainer((short) 3);
        ItemStack expected = itemStack("test:item", 1);
        container.setItemStackForSlot((short) 2, expected);
        Hotbar hotbar = new Hotbar(container, (byte) 2);

        assertEquals(2, PlayerInventoryAccess.getActiveHotbarSlot(hotbar));
        assertSame(expected, PlayerInventoryAccess.getActiveHotbarItem(hotbar));
        assertSame(container, PlayerInventoryAccess.getHotbar(hotbar));
    }

    @Test
    void missingHotbarReturnsEmptyAccessState() {
        assertEquals(-1, PlayerInventoryAccess.getActiveHotbarSlot((Hotbar) null));
        assertNull(PlayerInventoryAccess.getActiveHotbarItem((Hotbar) null));
        assertNull(PlayerInventoryAccess.getHotbar((Hotbar) null));
    }

    private static ItemStack itemStack(String itemId, int quantity) throws ReflectiveOperationException {
        ItemStack itemStack = (ItemStack) unsafe().allocateInstance(ItemStack.class);
        setField(ItemStack.class, itemStack, "itemId", itemId);
        setField(ItemStack.class, itemStack, "quantity", quantity);
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
