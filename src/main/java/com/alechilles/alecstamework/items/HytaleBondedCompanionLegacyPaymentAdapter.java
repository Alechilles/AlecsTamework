package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.operation
        .BondedCompanionPaymentOperationId;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** Read-only compatibility boundary for pre-escrow payment markers. */
final class HytaleBondedCompanionLegacyPaymentAdapter {
    private static final OperationKind KIND = new OperationKind("bonded_revive");
    private final Store<EntityStore> store;
    private final ComponentType<EntityStore,
            TameworkInventoryOperationReceiptsComponent> legacyType;
    private final BondedCompanionEscrowDurability durability;
    private final Supplier<Ref<EntityStore>> actorRef;

    HytaleBondedCompanionLegacyPaymentAdapter(
            Store<EntityStore> store,
            @Nullable ComponentType<EntityStore,
                    TameworkInventoryOperationReceiptsComponent> legacyType,
            BondedCompanionEscrowDurability durability,
            Supplier<Ref<EntityStore>> actorRef
    ) {
        this.store = store;
        this.legacyType = legacyType;
        this.durability = durability;
        this.actorRef = actorRef;
    }

    @Nullable HytaleBondedCompanionChargeReceiptPlan find(
            String operationId,
            String itemId,
            int quantity
    ) {
        if (legacyType == null) return null;
        return new HytaleBondedCompanionChargeReceiptPlan(
                actorRef.get(), store, legacyType,
                BondedCompanionPaymentOperationId.legacyOperationKey(
                operationId), itemId, quantity);
    }

    /** Finds operation-bound legacy evidence without current price policy. */
    @Nullable OperationEvidence findByIdentity(String operationId) {
        LegacyIdentity identity = identity(operationId);
        List<InventoryOperationReceipt> receipts = relevant(
                current(), identity.receiptKey());
        if (receipts.isEmpty()) return null;
        boolean safe = receipts.size() == 1
                && matchesIdentity(receipts.getFirst(), identity);
        boolean compensated = safe && receipts.getFirst().receiptKey().equals(
                identity.receiptKey() + ":compensated");
        return new OperationEvidence(safe, compensated);
    }

    CompletionStage<Boolean> release(
            String operationId,
            String itemId,
            int quantity
    ) {
        return durability.resumeOnWorldThread(() -> {
            HytaleBondedCompanionChargeReceiptPlan current = find(
                    operationId, itemId, quantity);
            if (current == null || !current.hasEvidence()) {
                return completed(false);
            }
            TameworkInventoryOperationReceiptsComponent snapshot =
                    current.snapshot();
            if (!current.release()) return completed(false);
            return durability.saveActor().thenCompose(saved -> {
                if (saved.saved()) return completed(true);
                return durability.resumeOnWorldThread(() -> {
                    current.restore(snapshot);
                    return completed(false);
                }, () -> false);
            });
        }, () -> false);
    }

    /** Releases exact old evidence after SQLite independently proves terminal. */
    CompletionStage<Boolean> releaseByIdentity(String operationId) {
        return durability.resumeOnWorldThread(() -> {
            LegacyIdentity identity = identity(operationId);
            TameworkInventoryOperationReceiptsComponent current = current();
            List<InventoryOperationReceipt> receipts = relevant(
                    current, identity.receiptKey());
            if (receipts.size() != 1
                    || !matchesIdentity(receipts.getFirst(), identity)) {
                return completed(false);
            }
            TameworkInventoryOperationReceiptsComponent snapshot =
                    current.clone();
            store.putComponent(actorRef.get(), legacyType,
                    current.withoutReceipt(receipts.getFirst().receiptKey()));
            return durability.saveActor().thenCompose(saved -> {
                if (saved.saved()) return completed(true);
                return durability.resumeOnWorldThread(() -> {
                    store.putComponent(actorRef.get(), legacyType, snapshot);
                    return completed(false);
                }, () -> false);
            });
        }, () -> false);
    }

    private boolean matchesIdentity(
            InventoryOperationReceipt receipt,
            LegacyIdentity identity
    ) {
        return receipt.operationId().equals(identity.operationId())
                && receipt.operationKind().equals(KIND)
                && receipt.installedAtMs() == 0L
                && knownKey(receipt.receiptKey(), identity.receiptKey());
    }

    private boolean knownKey(String key, String base) {
        if (key.equals(base) || key.equals(base + ":pending")
                || key.equals(base + ":compensating")
                || key.equals(base + ":compensated")) return true;
        return numericSuffix(key, base + ":pending:")
                || numericSuffix(key, base + ":compensating:");
    }

    private boolean numericSuffix(String key, String prefix) {
        if (!key.startsWith(prefix)) return false;
        try {
            return Integer.parseInt(key.substring(prefix.length())) >= 0;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private List<InventoryOperationReceipt> relevant(
            @Nullable TameworkInventoryOperationReceiptsComponent receipts,
            String receiptKey
    ) {
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

    private LegacyIdentity identity(String operationId) {
        String legacy = BondedCompanionPaymentOperationId.legacyOperationKey(
                operationId);
        OperationId id = new OperationId(UUID.nameUUIDFromBytes(
                ("bonded-revive\0" + legacy)
                        .getBytes(StandardCharsets.UTF_8)));
        return new LegacyIdentity(id, "bonded-revive:" + id);
    }

    @Nullable
    private TameworkInventoryOperationReceiptsComponent current() {
        if (legacyType == null) return null;
        store.assertThread();
        return store.getComponent(actorRef.get(), legacyType);
    }

    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }

    record OperationEvidence(boolean safe, boolean compensated) {
    }

    private record LegacyIdentity(OperationId operationId, String receiptKey) {
    }
}
