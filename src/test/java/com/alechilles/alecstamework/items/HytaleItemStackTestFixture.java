package com.alechilles.alecstamework.items.persistence;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.lang.reflect.Field;
import org.bson.BsonDocument;
import sun.misc.Unsafe;

/**
 * Builds exact item-stack values without requiring the Hytale asset store in unit tests.
 */
final class HytaleItemStackTestFixture {
    private HytaleItemStackTestFixture() {
    }

    static ItemStack stack(String itemId, BsonDocument metadata) {
        return stack(itemId, 1, 0.0D, 0.0D, metadata);
    }

    static ItemStack stack(
            String itemId,
            int quantity,
            double durability,
            double maxDurability,
            BsonDocument metadata
    ) {
        try {
            ItemStack stack = (ItemStack) unsafe().allocateInstance(
                    ItemStack.class
            );
            set(stack, "itemId", itemId);
            set(stack, "quantity", quantity);
            set(stack, "durability", durability);
            set(stack, "maxDurability", maxDurability);
            set(stack, "metadata", metadata);
            return stack;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                    "Unable to construct exact test ItemStack",
                    failure
            );
        }
    }

    private static void set(ItemStack stack, String name, Object value)
            throws ReflectiveOperationException {
        Field field = ItemStack.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(stack, value);
    }

    private static Unsafe unsafe() throws ReflectiveOperationException {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
