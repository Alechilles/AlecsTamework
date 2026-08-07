package com.alechilles.alecstamework.inventory;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent.Hotbar;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nullable;

/**
 * Resolves active player hotbar state through Update 5 inventory components.
 */
public final class PlayerInventoryAccess {
    private PlayerInventoryAccess() {
    }

    public static byte getActiveHotbarSlot(@Nullable Player player) {
        return getActiveHotbarSlot(getHotbarComponent(player));
    }

    @Nullable
    public static ItemStack getActiveHotbarItem(@Nullable Player player) {
        return getActiveHotbarItem(getHotbarComponent(player));
    }

    @Nullable
    public static ItemContainer getHotbar(@Nullable Player player) {
        return getHotbar(getHotbarComponent(player));
    }

    /**
     * Removes an exact quantity only when the player's live active item still matches the expected item.
     */
    public static boolean removeActiveHotbarItem(
            @Nullable Player player,
            @Nullable String expectedItemId,
            int quantity
    ) {
        return removeActiveHotbarItem(getHotbarComponent(player), expectedItemId, quantity);
    }

    static byte getActiveHotbarSlot(@Nullable Hotbar hotbar) {
        return hotbar != null ? hotbar.getActiveSlot() : -1;
    }

    @Nullable
    static ItemStack getActiveHotbarItem(@Nullable Hotbar hotbar) {
        return hotbar != null ? hotbar.getActiveItem() : null;
    }

    @Nullable
    static ItemContainer getHotbar(@Nullable Hotbar hotbar) {
        return hotbar != null ? hotbar.getInventory() : null;
    }

    static boolean removeActiveHotbarItem(
            @Nullable Hotbar hotbar,
            @Nullable String expectedItemId,
            int quantity
    ) {
        if (hotbar == null || expectedItemId == null || expectedItemId.isBlank() || quantity <= 0) {
            return false;
        }
        byte slot = hotbar.getActiveSlot();
        ItemContainer container = hotbar.getInventory();
        if (slot < 0 || container == null) {
            return false;
        }
        ItemStack active = container.getItemStack(slot);
        if (active == null
                || active.isEmpty()
                || !expectedItemId.equals(active.getItemId())
                || active.getQuantity() < quantity) {
            return false;
        }
        ItemStackSlotTransaction transaction = container.removeItemStackFromSlot((short) slot, quantity);
        return transaction != null && transaction.succeeded();
    }

    @Nullable
    private static Hotbar getHotbarComponent(@Nullable Player player) {
        Ref<EntityStore> reference = resolvePlayerReference(player);
        if (reference == null || !reference.isValid()) {
            return null;
        }
        Store<EntityStore> store = reference.getStore();
        return store != null ? store.getComponent(reference, Hotbar.getComponentType()) : null;
    }

    @Nullable
    private static Ref<EntityStore> resolvePlayerReference(@Nullable Player player) {
        if (player == null) {
            return null;
        }
        PlayerRef playerRef = player.getPlayerRef();
        return playerRef != null ? playerRef.getReference() : null;
    }
}
