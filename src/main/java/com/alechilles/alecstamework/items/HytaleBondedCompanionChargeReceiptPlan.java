package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.persistence.compensation.RefundClaim;
import com.alechilles.alecstamework.persistence.compensation.RefundItem;
import com.alechilles.alecstamework.persistence.compensation.runtime.ReceiptFirstRefundDelivery;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.runtime.player.InventoryOperationReceipt;
import com.alechilles.alecstamework.persistence.runtime.player.TameworkInventoryOperationReceiptsComponent;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Persistent pending/charged receipt and receipt-first refund plan for one bonded revive. */
final class HytaleBondedCompanionChargeReceiptPlan {
    private static final OperationKind KIND = new OperationKind("bonded_revive");
    private static final String PENDING_SUFFIX = ":pending";
    private static final String COMPENSATING_SUFFIX = ":compensating";

    private final Ref<EntityStore> playerRef;
    private final Store<EntityStore> store;
    private final ComponentType<EntityStore,
            TameworkInventoryOperationReceiptsComponent> receiptType;
    private final String operationKey;
    private final InventoryOperationReceipt pending;
    private final InventoryOperationReceipt charged;
    private final InventoryOperationReceipt compensating;
    private final RefundClaim refund;

    HytaleBondedCompanionChargeReceiptPlan(
            @Nonnull Ref<EntityStore> playerRef,
            @Nonnull Store<EntityStore> store,
            @Nonnull ComponentType<EntityStore,
                    TameworkInventoryOperationReceiptsComponent> receiptType,
            @Nonnull UUID ownerUuid,
            @Nonnull String worldKey,
            @Nonnull String operationKey,
            @Nonnull String itemId,
            int quantity) {
        this.playerRef = playerRef;
        this.store = store;
        this.receiptType = receiptType;
        this.operationKey = operationKey;
        OperationId operationId = new OperationId(UUID.nameUUIDFromBytes(
                ("bonded-revive\0" + operationKey)
                        .getBytes(StandardCharsets.UTF_8)));
        Sha256Hash planHash = Sha256Hash.ofUtf8(
                operationKey + "\0" + itemId + "\0" + quantity);
        String receiptKey = "bonded-revive:" + operationId;
        pending = new InventoryOperationReceipt(
                receiptKey + PENDING_SUFFIX, operationId, KIND, planHash, 0L);
        charged = new InventoryOperationReceipt(
                receiptKey, operationId, KIND, planHash, 0L);
        compensating = new InventoryOperationReceipt(
                receiptKey + COMPENSATING_SUFFIX,
                operationId, KIND, planHash, 0L);
        refund = new RefundClaim(
                operationId, ownerUuid, worldKey,
                List.of(new RefundItem(itemId, quantity)),
                "bonded_revive_rejected", receiptKey + ":refund",
                0L, null, null);
    }

    boolean charged() {
        TameworkInventoryOperationReceiptsComponent current = current();
        return current != null
                && charged.equals(current.receiptFor(charged.receiptKey()))
                && current.receiptFor(pending.receiptKey()) == null
                && current.receiptFor(compensating.receiptKey()) == null;
    }

    boolean recoverable() {
        return charged() || compensating();
    }

    boolean compensating() {
        TameworkInventoryOperationReceiptsComponent current = current();
        return current != null
                && compensating.equals(current.receiptFor(
                        compensating.receiptKey()))
                && current.receiptFor(pending.receiptKey()) == null
                && current.receiptFor(charged.receiptKey()) == null;
    }

    boolean installPending() {
        TameworkInventoryOperationReceiptsComponent current = current();
        if (current == null) current = new TameworkInventoryOperationReceiptsComponent();
        InventoryOperationReceipt foundPending =
                current.receiptFor(pending.receiptKey());
        InventoryOperationReceipt foundCharged =
                current.receiptFor(charged.receiptKey());
        InventoryOperationReceipt foundCompensating =
                current.receiptFor(compensating.receiptKey());
        if (charged.equals(foundCharged) && foundPending == null
                && foundCompensating == null) return true;
        if (foundCharged != null || foundCompensating != null
                || (foundPending != null
                && !pending.equals(foundPending))) return false;
        return write(current.withReceipt(pending)) && pending();
    }

    String operationKey() {
        return operationKey;
    }

    boolean markCharged() {
        TameworkInventoryOperationReceiptsComponent current = current();
        if (current == null || !pending.equals(
                current.receiptFor(pending.receiptKey()))) return false;
        TameworkInventoryOperationReceiptsComponent updated = current
                .withoutReceipt(pending.receiptKey())
                .withReceipt(charged);
        return write(updated) && charged();
    }

    boolean refund(CombinedItemContainer inventory) {
        if (!markCompensating()) return false;
        LiveOperationResult delivered = new ReceiptFirstRefundDelivery()
                .applyOrResolve(refund, new ReceiptInventory(inventory));
        return delivered.status() == LiveOperationResult.Status.CONFIRMED
                && release();
    }

    private boolean markCompensating() {
        TameworkInventoryOperationReceiptsComponent current = current();
        if (current == null) return false;
        if (compensating()) return true;
        boolean exactCharge = charged.equals(
                current.receiptFor(charged.receiptKey()));
        boolean exactPending = pending.equals(
                current.receiptFor(pending.receiptKey()));
        if (!exactCharge && !exactPending) return false;
        TameworkInventoryOperationReceiptsComponent updated = current
                .withoutReceipt(pending.receiptKey())
                .withoutReceipt(charged.receiptKey())
                .withReceipt(compensating);
        return write(updated) && compensating();
    }

    boolean release() {
        TameworkInventoryOperationReceiptsComponent current = current();
        if (current == null) return false;
        TameworkInventoryOperationReceiptsComponent updated = current
                .withoutReceipt(pending.receiptKey())
                .withoutReceipt(charged.receiptKey())
                .withoutReceipt(compensating.receiptKey());
        return write(updated) && absent();
    }

    private boolean pending() {
        TameworkInventoryOperationReceiptsComponent current = current();
        return current != null
                && pending.equals(current.receiptFor(pending.receiptKey()))
                && current.receiptFor(charged.receiptKey()) == null
                && current.receiptFor(compensating.receiptKey()) == null;
    }

    private boolean absent() {
        TameworkInventoryOperationReceiptsComponent current = current();
        return current != null
                && current.receiptFor(pending.receiptKey()) == null
                && current.receiptFor(charged.receiptKey()) == null
                && current.receiptFor(compensating.receiptKey()) == null;
    }

    private TameworkInventoryOperationReceiptsComponent current() {
        store.assertThread();
        return store.getComponent(playerRef, receiptType);
    }

    private boolean write(TameworkInventoryOperationReceiptsComponent value) {
        store.assertThread();
        store.putComponent(playerRef, receiptType, value);
        return true;
    }

    private static final class ReceiptInventory
            implements ReceiptFirstRefundDelivery.ReceiptInventory {
        private final CombinedItemContainer inventory;

        private ReceiptInventory(CombinedItemContainer inventory) {
            this.inventory = inventory;
        }

        @Override
        public ReceiptFirstRefundDelivery.ReceiptObservation observe(
                String expectedItemId, String receipt) {
            int quantity = 0;
            boolean conflict = false;
            for (short slot = 0; slot < inventory.getCapacity(); slot++) {
                ItemStack stack = inventory.getItemStack(slot);
                if (!carries(stack, receipt)) continue;
                if (!expectedItemId.equals(stack.getItemId())) {
                    conflict = true;
                    continue;
                }
                try {
                    quantity = Math.addExact(quantity, stack.getQuantity());
                } catch (ArithmeticException overflow) {
                    conflict = true;
                }
            }
            return ReceiptFirstRefundDelivery.ReceiptObservation.readable(
                    quantity, conflict);
        }

        @Override
        public ReceiptFirstRefundDelivery.AddResult add(
                String itemId, int quantity, String receipt) {
            ItemStack stack = new ItemStack(itemId, quantity).withMetadata(
                    TameworkMetadataKeys.PERSISTENCE_REFUND_RECEIPT,
                    Codec.STRING, receipt);
            ItemStackTransaction transaction = inventory.addItemStack(
                    stack, true, false, true);
            return transaction != null && transaction.succeeded()
                    && ItemStack.isEmpty(transaction.getRemainder())
                    ? ReceiptFirstRefundDelivery.AddResult.APPLIED
                    : ReceiptFirstRefundDelivery.AddResult.REJECTED;
        }

        private boolean carries(ItemStack stack, String receipt) {
            return stack != null && !stack.isEmpty() && receipt.equals(
                    stack.getFromMetadataOrNull(
                            TameworkMetadataKeys.PERSISTENCE_REFUND_RECEIPT,
                            Codec.STRING));
        }
    }
}
