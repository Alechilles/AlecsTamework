package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.BondedCompanionEscrowTransfer
        .RestoreResult;
import com.alechilles.alecstamework.items.components
        .TameworkBondedReviveEscrowComponent;
import com.alechilles.alecstamework.items.components
        .TameworkBondedReviveEscrowComponent.Phase;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Durably settles one bonded-payment escrow without duplicating inventory. */
final class BondedCompanionEscrowSettlementCoordinator {
    private final Store<EntityStore> store;
    private final ComponentType<EntityStore,
            TameworkBondedReviveEscrowComponent> escrowType;
    private final BondedCompanionEscrowDurability durability;
    private final BondedCompanionEscrowTransfer transfer;
    private final Supplier<Ref<EntityStore>> actorRef;
    private final Supplier<CombinedItemContainer> sourceInventory;
    private CompletableFuture<Boolean> refundInFlight;
    private String refundOperationId;

    BondedCompanionEscrowSettlementCoordinator(
            Store<EntityStore> store,
            ComponentType<EntityStore,
                    TameworkBondedReviveEscrowComponent> escrowType,
            BondedCompanionEscrowDurability durability,
            BondedCompanionEscrowTransfer transfer,
            Supplier<Ref<EntityStore>> actorRef,
            Supplier<CombinedItemContainer> sourceInventory
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.escrowType = Objects.requireNonNull(escrowType, "escrowType");
        this.durability = Objects.requireNonNull(durability, "durability");
        this.transfer = Objects.requireNonNull(transfer, "transfer");
        this.actorRef = Objects.requireNonNull(actorRef, "actorRef");
        this.sourceInventory = Objects.requireNonNull(
                sourceInventory, "sourceInventory");
    }

    /** Resumes an exact refund and returns true only after it is empty. */
    CompletionStage<Boolean> refund(
            TameworkBondedReviveEscrowComponent expected) {
        CompletableFuture<Boolean> flight;
        synchronized (this) {
            if (refundInFlight != null) {
                return expected.operationId().equals(refundOperationId)
                        ? refundInFlight : completed(false);
            }
            flight = new CompletableFuture<>();
            refundInFlight = flight;
            refundOperationId = expected.operationId();
        }
        CompletionStage<Boolean> execution;
        try {
            execution = durability.resumeOnWorldThread(
                    () -> begin(expected), () -> false)
                    .thenCompose(refunded -> refunded
                            ? removeTerminal(expected) : completed(false));
        } catch (RuntimeException | LinkageError failure) {
            finishFlight(flight, false);
            return flight;
        }
        execution.whenComplete((result, failure) -> finishFlight(
                flight, failure == null && Boolean.TRUE.equals(result)));
        return flight;
    }

    private void finishFlight(
            CompletableFuture<Boolean> flight, boolean result) {
        synchronized (this) {
            if (refundInFlight == flight) {
                refundInFlight = null;
                refundOperationId = null;
            }
        }
        flight.complete(result);
    }

    /** Durably removes an already-empty terminal escrow tombstone. */
    CompletionStage<Boolean> removeTerminal(
            TameworkBondedReviveEscrowComponent expected) {
        return durability.saveActor().thenCompose(saved -> {
            if (!saved.saved()) return completed(false);
            return durability.resumeOnWorldThread(() -> {
                TameworkBondedReviveEscrowComponent current = current();
                if (current == null) return completed(true);
                if (!matches(current, expected)
                        || current.reservedQuantity() != 0
                        || (current.phase() != Phase.REFUNDED
                        && current.phase() != Phase.COMMITTED)) {
                    return completed(false);
                }
                TameworkBondedReviveEscrowComponent tombstone = current.clone();
                store.removeComponent(actorRef.get(), escrowType);
                return durability.saveActor().thenCompose(removed -> {
                    if (removed.saved()) return completed(true);
                    return durability.resumeOnWorldThread(() -> {
                        if (current() != null) return completed(false);
                        store.putComponent(actorRef.get(), escrowType, tombstone);
                        return completed(false);
                    }, () -> false);
                });
            }, () -> false);
        });
    }

    private CompletionStage<Boolean> begin(
            TameworkBondedReviveEscrowComponent expected) {
        TameworkBondedReviveEscrowComponent current = current();
        if (!matches(current, expected)) return completed(false);
        if (current.phase() == Phase.REFUNDED) return completed(true);
        if (current.phase() == Phase.COMMITTED
                || current.phase() == Phase.QUARANTINED) {
            return completed(false);
        }
        if (current.phase() == Phase.REFUNDING) {
            // A prior attempt may have moved a slot whose save failed. Fence
            // that live progress before this invocation advances another slot.
            return saveThenMove(expected);
        }
        current.setPhase(Phase.REFUNDING);
        store.putComponent(actorRef.get(), escrowType, current);
        return saveThenMove(expected);
    }

    private CompletionStage<Boolean> saveThenMove(
            TameworkBondedReviveEscrowComponent expected) {
        return durability.saveActor().thenCompose(saved -> {
            if (!saved.saved()) return completed(false);
            return durability.resumeOnWorldThread(
                    () -> moveNext(expected), () -> false);
        });
    }

    private CompletionStage<Boolean> moveNext(
            TameworkBondedReviveEscrowComponent expected) {
        TameworkBondedReviveEscrowComponent current = current();
        if (!matches(current, expected) || current.phase() != Phase.REFUNDING) {
            return completed(false);
        }
        int reserved = current.reservedQuantity();
        if (reserved < 0 || reserved > current.quantity()) {
            return quarantine(current);
        }
        CombinedItemContainer source = sourceInventory.get();
        if (source == null) return completed(false);
        RestoreResult result = transfer.restoreNext(source, current);
        if (result == RestoreResult.INVALID) return quarantine(current);
        if (result == RestoreResult.BLOCKED) return completed(false);
        if (result == RestoreResult.COMPLETE) {
            current.setPhase(Phase.REFUNDED);
            store.putComponent(actorRef.get(), escrowType, current);
            return completed(true);
        }
        store.putComponent(actorRef.get(), escrowType, current);
        return saveThenMove(expected);
    }

    private CompletionStage<Boolean> quarantine(
            TameworkBondedReviveEscrowComponent escrow) {
        escrow.setPhase(Phase.QUARANTINED);
        store.putComponent(actorRef.get(), escrowType, escrow);
        return durability.saveActor().thenApply(ignored -> false);
    }

    private TameworkBondedReviveEscrowComponent current() {
        store.assertThread();
        return store.getComponent(actorRef.get(), escrowType);
    }

    private static boolean matches(
            TameworkBondedReviveEscrowComponent current,
            TameworkBondedReviveEscrowComponent expected) {
        return current != null && current.matches(
                expected.operationId(), expected.itemId(), expected.quantity());
    }

    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }
}
