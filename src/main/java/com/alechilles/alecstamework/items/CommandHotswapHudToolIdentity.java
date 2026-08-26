package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.inventory.PlayerInventoryAccess;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Internal identity for the exact active command stack and hotbar position. */
final class CommandHotswapHudToolIdentity {
    private final ItemStack stack;
    private final byte hotbarSlot;
    private final String itemId;

    private CommandHotswapHudToolIdentity(
            @Nonnull ItemStack stack,
            byte hotbarSlot,
            @Nonnull String itemId
    ) {
        this.stack = Objects.requireNonNull(stack, "stack");
        this.hotbarSlot = hotbarSlot;
        this.itemId = Objects.requireNonNull(itemId, "itemId");
    }

    @Nonnull
    static CommandHotswapHudToolIdentity from(
            @Nonnull Player player,
            @Nonnull ItemStack stack
    ) {
        return of(stack, PlayerInventoryAccess.getActiveHotbarSlot(player));
    }

    static CommandHotswapHudToolIdentity of(
            @Nonnull ItemStack stack,
            byte hotbarSlot
    ) {
        String itemId = Objects.requireNonNull(stack.getItemId(), "stack.itemId");
        return new CommandHotswapHudToolIdentity(stack, hotbarSlot, itemId);
    }

    @Nonnull
    String itemId() {
        return itemId;
    }

    byte hotbarSlot() {
        return hotbarSlot;
    }

    boolean same(@Nonnull CommandHotswapHudToolIdentity other) {
        return this == other
                || (stack == other.stack
                && hotbarSlot == other.hotbarSlot
                && itemId.equals(other.itemId));
    }

    @Override
    public String toString() {
        return itemId + "@" + hotbarSlot + "/" + Integer.toHexString(
                System.identityHashCode(stack));
    }
}
