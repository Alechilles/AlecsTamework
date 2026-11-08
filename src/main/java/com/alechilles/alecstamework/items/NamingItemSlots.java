package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Finds and uses naming item stacks, including ones the player carries but does not hold (companion card rename).
 * The cooldown lives on each stack as {@link TameworkMetadataKeys#NAME_COOLDOWN_UNTIL}, a Unix wall-clock time in ms.
 * Methods that take a container read or change live inventory and run on the player's world thread.
 */
final class NamingItemSlots {
    private NamingItemSlots() {
    }

    /** One stack and where it sits. */
    record Slot(@Nonnull ItemContainer container, short index, @Nonnull ItemStack stack) {
    }

    /**
     * The first carried stack {@code eligible} accepts: the hotbar, then the main storage, then the backpack, each in
     * slot order. Reads the inventory components directly and changes nothing. Null when none matches.
     */
    @Nullable
    static Slot findCarried(@Nonnull Store<EntityStore> store,
                            @Nonnull Ref<EntityStore> playerRef,
                            @Nonnull Predicate<ItemStack> eligible) {
        Slot found = findIn(inventoryOf(store, playerRef, InventoryComponent.Hotbar.getComponentType()), eligible);
        if (found == null) {
            found = findIn(inventoryOf(store, playerRef, InventoryComponent.Storage.getComponentType()), eligible);
        }
        if (found == null) {
            found = findIn(inventoryOf(store, playerRef, InventoryComponent.Backpack.getComponentType()), eligible);
        }
        return found;
    }

    /** The first non-empty stack in {@code container} that {@code eligible} accepts, in slot order. */
    @Nullable
    static Slot findIn(@Nullable ItemContainer container, @Nonnull Predicate<ItemStack> eligible) {
        if (container == null) {
            return null;
        }
        for (short slot = 0; slot < container.getCapacity(); slot++) {
            ItemStack stack = container.getItemStack(slot);
            if (!ItemStack.isEmpty(stack) && eligible.test(stack)) {
                return new Slot(container, slot, stack);
            }
        }
        return null;
    }

    static boolean isCooldownActive(@Nullable ItemStack stack, int cooldownMs) {
        if (stack == null || cooldownMs <= 0) {
            return false;
        }
        Long until = stack.getFromMetadataOrNull(TameworkMetadataKeys.NAME_COOLDOWN_UNTIL, Codec.LONG);
        return until != null && until > System.currentTimeMillis();
    }

    /**
     * Spends one use of the stack in {@code slot}: removes one item when {@code consume}, then starts the cooldown on
     * what is left. Does nothing when the slot is empty.
     */
    static void use(@Nonnull ItemContainer container, short slot, boolean consume, int cooldownMs) {
        if (ItemStack.isEmpty(container.getItemStack(slot))) {
            return;
        }
        if (consume) {
            container.removeItemStackFromSlot(slot, 1);
        }
        if (cooldownMs <= 0) {
            return;
        }
        ItemStack after = container.getItemStack(slot);
        if (ItemStack.isEmpty(after)) {
            return;
        }
        long until = System.currentTimeMillis() + cooldownMs;
        container.setItemStackForSlot(slot, after.withMetadata(TameworkMetadataKeys.NAME_COOLDOWN_UNTIL, Codec.LONG, until));
    }

    @Nullable
    private static ItemContainer inventoryOf(@Nonnull Store<EntityStore> store,
                                             @Nonnull Ref<EntityStore> playerRef,
                                             @Nullable ComponentType<EntityStore, ? extends InventoryComponent> type) {
        if (type == null) {
            return null;
        }
        InventoryComponent component = store.getComponent(playerRef, type);
        return component != null ? component.getInventory() : null;
    }
}
