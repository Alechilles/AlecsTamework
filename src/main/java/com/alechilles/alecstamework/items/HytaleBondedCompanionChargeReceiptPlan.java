package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.runtime.player
        .InventoryOperationReceipt;
import com.alechilles.alecstamework.persistence.runtime.player
        .TameworkInventoryOperationReceiptsComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Read-only quarantine adapter for receipts written by pre-escrow releases.
 *
 * <p>Historical pending markers were installed before the debit and therefore
 * prove neither zero nor full payment. This adapter never infers a quantity,
 * retries a debit, or issues a refund from those markers. It only releases an
 * exact legacy marker after an independent terminal SQLite result.</p>
 */
final class HytaleBondedCompanionChargeReceiptPlan {
    private static final OperationKind KIND =
            new OperationKind("bonded_revive");
    private static final String COMPENSATED_SUFFIX = ":compensated";

    private final Ref<EntityStore> playerRef;
    private final Store<EntityStore> store;
    private final ComponentType<EntityStore,
            TameworkInventoryOperationReceiptsComponent> receiptType;
    private final OperationId operationId;
    private final Sha256Hash planHash;
    private final String receiptKey;

    HytaleBondedCompanionChargeReceiptPlan(
            @Nonnull Ref<EntityStore> playerRef,
            @Nonnull Store<EntityStore> store,
            @Nonnull ComponentType<EntityStore,
                    TameworkInventoryOperationReceiptsComponent> receiptType,
            @Nonnull String operationKey,
            @Nonnull String itemId,
            int quantity) {
        this.playerRef = playerRef;
        this.store = store;
        this.receiptType = receiptType;
        operationId = new OperationId(UUID.nameUUIDFromBytes(
                ("bonded-revive\0" + operationKey)
                        .getBytes(StandardCharsets.UTF_8)));
        planHash = Sha256Hash.ofUtf8(
                operationKey + "\0" + itemId + "\0" + quantity);
        receiptKey = "bonded-revive:" + operationId;
    }

    /** Any marker for the operation is ambiguous until SQLite proves terminal. */
    boolean hasEvidence() {
        return !relevant(current()).isEmpty();
    }

    /** The old terminal compensated marker can be discarded, never replayed. */
    boolean hasCompensatedEvidence() {
        List<InventoryOperationReceipt> evidence = relevant(current());
        return evidence.size() == 1 && matches(evidence.getFirst())
                && evidence.getFirst().receiptKey().equals(
                receiptKey + COMPENSATED_SUFFIX);
    }

    /** Releases exact legacy evidence only after the caller proves terminal. */
    boolean release() {
        TameworkInventoryOperationReceiptsComponent current = current();
        if (current == null) return true;
        List<InventoryOperationReceipt> evidence = relevant(current);
        for (InventoryOperationReceipt receipt : evidence) {
            if (!matches(receipt)) return false;
        }
        TameworkInventoryOperationReceiptsComponent updated = current;
        for (InventoryOperationReceipt receipt : evidence) {
            updated = updated.withoutReceipt(receipt.receiptKey());
        }
        store.putComponent(playerRef, receiptType, updated);
        return relevant(current()).isEmpty();
    }

    TameworkInventoryOperationReceiptsComponent snapshot() {
        TameworkInventoryOperationReceiptsComponent receipts = current();
        return receipts == null ? null : receipts.clone();
    }

    void restore(TameworkInventoryOperationReceiptsComponent snapshot) {
        if (snapshot != null) {
            store.putComponent(playerRef, receiptType, snapshot.clone());
        }
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
        ArrayList<InventoryOperationReceipt> evidence = new ArrayList<>();
        for (InventoryOperationReceipt receipt : receipts.receipts()) {
            if (receipt.receiptKey().equals(receiptKey)
                    || receipt.receiptKey().startsWith(receiptKey + ":")) {
                evidence.add(receipt);
            }
        }
        return List.copyOf(evidence);
    }

    private TameworkInventoryOperationReceiptsComponent current() {
        store.assertThread();
        return store.getComponent(playerRef, receiptType);
    }
}
