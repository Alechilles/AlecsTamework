package com.alechilles.alecstamework.inventory;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent.Hotbar;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
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
        Hotbar hotbar = getHotbarComponent(player);
        return hotbar != null ? hotbar.getActiveSlot() : -1;
    }

    @Nullable
    public static ItemStack getActiveHotbarItem(@Nullable Player player) {
        Hotbar hotbar = getHotbarComponent(player);
        return hotbar != null ? hotbar.getActiveItem() : null;
    }

    @Nullable
    public static ItemContainer getHotbar(@Nullable Player player) {
        Hotbar hotbar = getHotbarComponent(player);
        return hotbar != null ? hotbar.getInventory() : null;
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
