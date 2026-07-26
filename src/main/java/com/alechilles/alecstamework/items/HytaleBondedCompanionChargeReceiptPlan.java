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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Persistent pending/charged receipt and receipt-first refund plan for one bonded revive. */
final class HytaleBondedCompanionChargeReceiptPlan {
    private static final OperationKind KIND = new OperationKind("bonded_revive");
    private static final String PENDING_SUFFIX = ":pending";
    private static final String COMPENSATING_SUFFIX = ":compensating";
    private static final String COMPENSATED_SUFFIX = ":compensated";

    private final Ref<EntityStore> playerRef;
    private final Store<EntityStore> store;
    private final ComponentType<EntityStore,
            TameworkInventoryOperationReceiptsComponent> receiptType;
    private final String operationKey;
    private final OperationId operationId;
    private final Sha256Hash planHash;
    private final String receiptKey;
    private final String itemId;
    private final int quantity;
    private final InventoryOperationReceipt charged;
    private final UUID ownerUuid;
    private final String worldKey;

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
        this.itemId = itemId;
        this.quantity = quantity;
        operationId = new OperationId(UUID.nameUUIDFromBytes(
                ("bonded-revive\0" + operationKey)
                        .getBytes(StandardCharsets.UTF_8)));
        planHash = Sha256Hash.ofUtf8(
                operationKey + "\0" + itemId + "\0" + quantity);
        receiptKey = "bonded-revive:" + operationId;
        charged = new InventoryOperationReceipt(
                receiptKey, operationId, KIND, planHash, 0L);
        this.ownerUuid = ownerUuid;
        this.worldKey = worldKey;
    }

    BondedCompanionChargeCoordinator.State state(int availableQuantity) {
        List<InventoryOperationReceipt> relevant = relevant(current());
        if (relevant.isEmpty()) {
            return BondedCompanionChargeCoordinator.State.ABSENT;
        }
        if (relevant.size() != 1 || !matches(relevant.getFirst())) {
            return BondedCompanionChargeCoordinator.State.CONFLICT;
        }
        InventoryOperationReceipt receipt = relevant.getFirst();
        String key = receipt.receiptKey();
        if (key.equals(receiptKey)) {
            return BondedCompanionChargeCoordinator.State.CHARGED;
        }
        if (key.equals(receiptKey + PENDING_SUFFIX)) {
            return BondedCompanionChargeCoordinator.State.DEBITED;
        }
        if (key.equals(receiptKey + COMPENSATING_SUFFIX)) {
            return BondedCompanionChargeCoordinator.State.COMPENSATING;
        }
        if (key.equals(receiptKey + COMPENSATED_SUFFIX)) {
            return BondedCompanionChargeCoordinator.State.COMPENSATED;
        }
        Integer prepared = suffixQuantity(key, PENDING_SUFFIX);
        if (prepared != null) {
            int debited = prepared - availableQuantity;
            if (debited == 0) {
                return BondedCompanionChargeCoordinator.State.PREPARED;
            }
            return debited > 0 && debited <= quantity
                    ? BondedCompanionChargeCoordinator.State.DEBITED
                    : BondedCompanionChargeCoordinator.State.CONFLICT;
        }
        Integer compensating = suffixQuantity(key, COMPENSATING_SUFFIX);
        return compensating != null && compensating > 0
                && compensating <= quantity
                ? BondedCompanionChargeCoordinator.State.COMPENSATING
                : BondedCompanionChargeCoordinator.State.CONFLICT;
    }

    boolean installPending(int availableQuantity) {
        TameworkInventoryOperationReceiptsComponent current = current();
        if (state(availableQuantity)
                == BondedCompanionChargeCoordinator.State.PREPARED) return true;
        if (state(availableQuantity)
                != BondedCompanionChargeCoordinator.State.ABSENT) return false;
        if (current == null) {
            current = new TameworkInventoryOperationReceiptsComponent();
        }
        InventoryOperationReceipt pending = receipt(
                receiptKey + PENDING_SUFFIX + ":" + availableQuantity);
        return write(current.withReceipt(pending))
                && state(availableQuantity)
                == BondedCompanionChargeCoordinator.State.PREPARED;
    }

    String operationKey() {
        return operationKey;
    }

    boolean markCharged(int availableQuantity) {
        if (debitQuantity(availableQuantity) != quantity) return false;
        TameworkInventoryOperationReceiptsComponent current = current();
        if (current == null) return false;
        TameworkInventoryOperationReceiptsComponent updated =
                withoutOperationReceipts(current).withReceipt(charged);
        return write(updated) && state(availableQuantity)
                == BondedCompanionChargeCoordinator.State.CHARGED;
    }

    boolean refund(CombinedItemContainer inventory) {
        int availableQuantity = availableQuantity(inventory);
        if (state(availableQuantity)
                == BondedCompanionChargeCoordinator.State.COMPENSATED) {
            return true;
        }
        int refundQuantity = debitQuantity(availableQuantity);
        if (refundQuantity <= 0 || !markCompensating(refundQuantity)) {
            return false;
        }
        RefundClaim refund = new RefundClaim(
                operationId, ownerUuid, worldKey,
                List.of(new RefundItem(itemId, refundQuantity)),
                "bonded_revive_rejected", receiptKey + ":refund",
                0L, null, null);
        LiveOperationResult delivered = new ReceiptFirstRefundDelivery()
                .applyOrResolve(refund, new ReceiptInventory(inventory));
        return delivered.status() == LiveOperationResult.Status.CONFIRMED
                && markCompensated();
    }

    private boolean markCompensated() {
        TameworkInventoryOperationReceiptsComponent current = current();
        if (current == null || compensatingQuantity(current) == null) {
            return false;
        }
        InventoryOperationReceipt compensated = receipt(
                receiptKey + COMPENSATED_SUFFIX);
        TameworkInventoryOperationReceiptsComponent updated =
                withoutOperationReceipts(current).withReceipt(compensated);
        if (!write(updated)) return false;
        List<InventoryOperationReceipt> readback = relevant(current());
        return readback.size() == 1 && compensated.equals(readback.getFirst());
    }

    private boolean markCompensating(int refundQuantity) {
        TameworkInventoryOperationReceiptsComponent current = current();
        if (current == null) return false;
        Integer existing = compensatingQuantity(current);
        if (existing != null) return existing == refundQuantity;
        InventoryOperationReceipt compensating = receipt(
                receiptKey + COMPENSATING_SUFFIX + ":" + refundQuantity);
        TameworkInventoryOperationReceiptsComponent updated =
                withoutOperationReceipts(current).withReceipt(compensating);
        return write(updated)
                && Integer.valueOf(refundQuantity).equals(
                        compensatingQuantity(current()));
    }

    boolean release() {
        TameworkInventoryOperationReceiptsComponent current = current();
        if (current == null) return false;
        return write(withoutOperationReceipts(current))
                && relevant(current()).isEmpty();
    }

    boolean releasePrepared(int availableQuantity) {
        return state(availableQuantity)
                == BondedCompanionChargeCoordinator.State.PREPARED
                && release();
    }

    private int debitQuantity(int availableQuantity) {
        TameworkInventoryOperationReceiptsComponent current = current();
        if (current == null) return 0;
        Integer compensating = compensatingQuantity(current);
        if (compensating != null) return compensating;
        List<InventoryOperationReceipt> relevant = relevant(current);
        if (relevant.size() != 1 || !matches(relevant.getFirst())) return 0;
        String key = relevant.getFirst().receiptKey();
        if (key.equals(receiptKey)
                || key.equals(receiptKey + PENDING_SUFFIX)
                || key.equals(receiptKey + COMPENSATING_SUFFIX)) {
            return quantity;
        }
        Integer prepared = suffixQuantity(key, PENDING_SUFFIX);
        if (prepared == null) return 0;
        int debited = prepared - availableQuantity;
        return debited > 0 && debited <= quantity ? debited : 0;
    }

    private int availableQuantity(CombinedItemContainer inventory) {
        int available = 0;
        for (short slot = 0; slot < inventory.getCapacity(); slot++) {
            ItemStack stack = inventory.getItemStack(slot);
            if (stack != null && itemId.equals(stack.getItemId())) {
                available = Math.addExact(available, stack.getQuantity());
            }
        }
        return available;
    }

    private Integer compensatingQuantity(
            TameworkInventoryOperationReceiptsComponent current) {
        List<InventoryOperationReceipt> relevant = relevant(current);
        if (relevant.size() != 1 || !matches(relevant.getFirst())) return null;
        String key = relevant.getFirst().receiptKey();
        if (key.equals(receiptKey + COMPENSATING_SUFFIX)) return quantity;
        Integer parsed = suffixQuantity(key, COMPENSATING_SUFFIX);
        return parsed != null && parsed > 0 && parsed <= quantity
                ? parsed : null;
    }

    private Integer suffixQuantity(String key, String phaseSuffix) {
        String prefix = receiptKey + phaseSuffix + ":";
        if (!key.startsWith(prefix)) return null;
        try {
            return Integer.valueOf(key.substring(prefix.length()));
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    private InventoryOperationReceipt receipt(String key) {
        return new InventoryOperationReceipt(
                key, operationId, KIND, planHash, 0L);
    }

    private boolean matches(InventoryOperationReceipt receipt) {
        return receipt.operationId().equals(operationId)
                && receipt.operationKind().equals(KIND)
                && receipt.planHash().equals(planHash)
                && receipt.installedAtMs() == 0L;
    }

    private List<InventoryOperationReceipt> relevant(
            TameworkInventoryOperationReceiptsComponent receipts) {
        if (receipts == null) return List.of();
        ArrayList<InventoryOperationReceipt> relevant = new ArrayList<>();
        for (InventoryOperationReceipt receipt : receipts.receipts()) {
            if (receipt.receiptKey().equals(receiptKey)
                    || receipt.receiptKey().startsWith(receiptKey + ":")) {
                relevant.add(receipt);
            }
        }
        return List.copyOf(relevant);
    }

    private TameworkInventoryOperationReceiptsComponent withoutOperationReceipts(
            TameworkInventoryOperationReceiptsComponent receipts) {
        TameworkInventoryOperationReceiptsComponent updated = receipts;
        for (InventoryOperationReceipt receipt : relevant(receipts)) {
            updated = updated.withoutReceipt(receipt.receiptKey());
        }
        return updated;
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
