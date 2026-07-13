package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.inventory.PlayerInventoryAccess;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Settles attachment item consumption and refunds without dropping partial inventory mutations. */
final class AttachmentExchangeInventoryService {
    boolean canApply(@Nullable Player player, @Nonnull AttachmentExchangePlan plan) {
        InventoryPort port = resolvePort(player);
        return port != null && canApply(port, plan);
    }

    static boolean canApply(@Nonnull InventoryPort port, @Nonnull AttachmentExchangePlan plan) {
        if (!matchesPlanInput(port.activeItem(), plan)) {
            return false;
        }
        return !plan.needsSeparateRefundInsertion() || port.canAddAllOrNothing(plan.refundedItemId());
    }

    boolean apply(@Nullable Player player, @Nonnull AttachmentExchangePlan plan) {
        InventoryPort port = resolvePort(player);
        return port != null && apply(port, plan);
    }

    static boolean apply(@Nonnull InventoryPort port, @Nonnull AttachmentExchangePlan plan) {
        ActiveItem original = port.activeItem();
        if (!matchesPlanInput(original, plan)) {
            return false;
        }
        ActiveItem replacement = buildActiveReplacement(original, plan);
        if (!port.replaceActive(original, replacement)) {
            return false;
        }
        if (!plan.needsSeparateRefundInsertion()) {
            return true;
        }
        try {
            if (port.addAllOrNothing(plan.refundedItemId())) {
                return true;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // The active stack is restored below; the caller rolls back model state.
        }
        port.replaceActive(replacement, original);
        return false;
    }

    @Nullable
    private InventoryPort resolvePort(@Nullable Player player) {
        if (player == null) {
            return null;
        }
        ItemContainer hotbar = PlayerInventoryAccess.getHotbar(player);
        byte activeSlot = PlayerInventoryAccess.getActiveHotbarSlot(player);
        if (hotbar == null || activeSlot < 0) {
            return null;
        }
        Inventory inventory = player.getInventory();
        CombinedItemContainer combined = inventory != null
                ? inventory.getCombinedBackpackStorageHotbar()
                : null;
        return new HytaleInventoryPort(hotbar, activeSlot, combined);
    }

    private static boolean matchesPlanInput(@Nonnull ActiveItem active,
                                            @Nonnull AttachmentExchangePlan plan) {
        if (plan.removesAttachment()) {
            return active.isEmpty();
        }
        return !active.isEmpty()
                && plan.consumedItemId().equals(active.itemId())
                && plan.heldQuantity() == active.quantity();
    }

    @Nonnull
    private static ActiveItem buildActiveReplacement(@Nonnull ActiveItem original,
                                                     @Nonnull AttachmentExchangePlan plan) {
        if (plan.removesAttachment()) {
            return new ActiveItem(plan.refundedItemId(), 1);
        }
        if (plan.heldQuantity() > 1) {
            return new ActiveItem(original.itemId(), plan.heldQuantity() - 1);
        }
        return plan.refundedItemId() == null
                ? ActiveItem.EMPTY
                : new ActiveItem(plan.refundedItemId(), 1);
    }

    interface InventoryPort {
        @Nonnull
        ActiveItem activeItem();

        boolean replaceActive(@Nonnull ActiveItem expected, @Nonnull ActiveItem replacement);

        boolean canAddAllOrNothing(@Nonnull String itemId);

        boolean addAllOrNothing(@Nonnull String itemId);
    }

    record ActiveItem(@Nullable String itemId, int quantity) {
        private static final ActiveItem EMPTY = new ActiveItem(null, 0);

        private boolean isEmpty() {
            return itemId == null || itemId.isBlank() || quantity <= 0;
        }

        @Nonnull
        private static ActiveItem from(@Nullable ItemStack stack) {
            return stack == null || stack.isEmpty()
                    ? EMPTY
                    : new ActiveItem(stack.getItemId(), stack.getQuantity());
        }
    }

    private record HytaleInventoryPort(@Nonnull ItemContainer hotbar,
                                       short activeSlot,
                                       @Nullable CombinedItemContainer combined) implements InventoryPort {
        @Override
        public ActiveItem activeItem() {
            return ActiveItem.from(hotbar.getItemStack(activeSlot));
        }

        @Override
        public boolean replaceActive(@Nonnull ActiveItem expected, @Nonnull ActiveItem replacement) {
            ItemStack liveStack = hotbar.getItemStack(activeSlot);
            ActiveItem live = ActiveItem.from(liveStack);
            if (!live.equals(expected)) {
                return false;
            }
            ItemStack replacementStack = replacement.isEmpty()
                    ? ItemStack.EMPTY
                    : buildReplacementStack(liveStack, replacement);
            ItemStackSlotTransaction transaction = hotbar.setItemStackForSlot(activeSlot, replacementStack);
            return transaction != null
                    && transaction.succeeded()
                    && ActiveItem.from(hotbar.getItemStack(activeSlot)).equals(replacement);
        }

        @Nonnull
        private ItemStack buildReplacementStack(@Nullable ItemStack liveStack,
                                                @Nonnull ActiveItem replacement) {
            if (liveStack != null
                    && !liveStack.isEmpty()
                    && replacement.itemId().equals(liveStack.getItemId())) {
                return liveStack.withQuantity(replacement.quantity());
            }
            return new ItemStack(replacement.itemId(), replacement.quantity());
        }

        @Override
        public boolean canAddAllOrNothing(@Nonnull String itemId) {
            try {
                return combined != null && combined.canAddItemStack(new ItemStack(itemId, 1), false, true);
            } catch (RuntimeException | LinkageError ignored) {
                return false;
            }
        }

        @Override
        public boolean addAllOrNothing(@Nonnull String itemId) {
            if (combined == null) {
                return false;
            }
            ItemStackTransaction transaction = combined.addItemStack(
                    new ItemStack(itemId, 1),
                    true,
                    false,
                    true
            );
            return transaction != null
                    && transaction.succeeded()
                    && (transaction.getRemainder() == null || transaction.getRemainder().isEmpty());
        }
    }
}
