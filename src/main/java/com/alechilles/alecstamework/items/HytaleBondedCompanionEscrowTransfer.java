package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.components
        .TameworkBondedReviveEscrowComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.MoveTransaction;

/** Hytale container-locking implementation of exact bonded escrow movement. */
final class HytaleBondedCompanionEscrowTransfer
        implements BondedCompanionEscrowTransfer {
    @Override
    public int availableQuantity(
            CombinedItemContainer source, String itemId) {
        ItemStack query = new ItemStack(itemId, 1);
        int available = 0;
        for (short slot = 0; slot < source.getCapacity(); slot++) {
            ItemStack stack = source.getItemStack(slot);
            if (ItemStack.isStackableWith(stack, query)) {
                available = Math.addExact(available, stack.getQuantity());
            }
        }
        return available;
    }

    @Override
    public void reserveRemaining(
            CombinedItemContainer source,
            TameworkBondedReviveEscrowComponent escrow,
            int remaining) {
        ItemStack query = new ItemStack(escrow.itemId(), 1);
        for (short slot = 0; slot < source.getCapacity() && remaining > 0;
             slot++) {
            ItemStack stack = source.getItemStack(slot);
            if (!ItemStack.isStackableWith(stack, query)) continue;
            int moved = Math.min(remaining, stack.getQuantity());
            MoveTransaction<ItemStackTransaction> transaction =
                    source.moveItemStackFromSlot(
                            slot, moved, escrow.getInventory(), true, false);
            if (transaction == null || !transaction.succeeded()) break;
            remaining -= moved;
        }
    }

    @Override
    public boolean restore(
            CombinedItemContainer source,
            TameworkBondedReviveEscrowComponent escrow) {
        ItemContainer reserved = escrow.getInventory();
        for (short slot = 0; slot < reserved.getCapacity(); slot++) {
            ItemStack stack = reserved.getItemStack(slot);
            if (ItemStack.isEmpty(stack)) continue;
            MoveTransaction<ItemStackTransaction> transaction =
                    reserved.moveItemStackFromSlot(
                            slot, source, true, false);
            if (transaction == null || !transaction.succeeded()) return false;
        }
        return escrow.reservedQuantity() == 0;
    }
}
