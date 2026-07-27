package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.BondedCompanionEscrowTransfer
        .RestoreResult;
import com.alechilles.alecstamework.items.components
        .TameworkBondedReviveEscrowComponent;
import com.alechilles.alecstamework.items.components
        .TameworkBondedReviveEscrowComponent.Phase;
import com.alechilles.alecstamework.items.BondedCompanionEscrowSettlementFlights
        .Action;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
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
    private final UUID ownerUuid;
    private final Supplier<Ref<EntityStore>> actorRef;
    private final Supplier<CombinedItemContainer> sourceInventory;
    private final BondedCompanionEscrowSettlementFlights flights;

    BondedCompanionEscrowSettlementCoordinator(
            Store<EntityStore> store,
            ComponentType<EntityStore,
                    TameworkBondedReviveEscrowComponent> escrowType,
            BondedCompanionEscrowDurability durability,
            BondedCompanionEscrowTransfer transfer,
            UUID ownerUuid,
            Supplier<Ref<EntityStore>> actorRef,
            Supplier<CombinedItemContainer> sourceInventory
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.escrowType = Objects.requireNonNull(escrowType, "escrowType");
        this.durability = Objects.requireNonNull(durability, "durability");
        this.transfer = Objects.requireNonNull(transfer, "transfer");
        this.ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        this.actorRef = Objects.requireNonNull(actorRef, "actorRef");
        this.sourceInventory = Objects.requireNonNull(
                sourceInventory, "sourceInventory");
        this.flights = BondedCompanionEscrowSettlementFlights.shared();
    }

    /** Serializes an exact refund even while another save hides the escrow. */
    CompletionStage<Boolean> refund(
            String operationId, String itemId, int quantity) {
        return flights.coordinate(
                escrowType, ownerUuid, operationId, Action.REFUND,
                true,
                () -> durability.resumeOnWorldThread(
                        () -> refundIdentityInCurrentFlight(
                                operationId, itemId, quantity),
                        () -> false),
                () -> false);
    }

    /** Serializes one durable reservation behind every terminal actor save. */
    <T> CompletionStage<T> prepare(
            String operationId,
            Supplier<CompletionStage<T>> action,
            Supplier<T> unavailable
    ) {
        return flights.coordinate(
                escrowType, ownerUuid, operationId, Action.PREPARE, false,
                () -> durability.resumeOnWorldThread(action, unavailable),
                unavailable);
    }

    /** Reads escrow only after every earlier actor mutation is durable. */
    <T> CompletionStage<T> read(
            String operationId,
            Supplier<T> action,
            Supplier<T> unavailable
    ) {
        return flights.coordinate(
                escrowType, ownerUuid, operationId, Action.READ, false,
                () -> durability.resumeOnWorldThread(
                        () -> completed(action.get()), unavailable),
                unavailable);
    }

    /** Consumes and removes one exact escrow under the shared actor queue. */
    CompletionStage<Boolean> consume(
            String operationId, String itemId, int quantity) {
        return flights.coordinate(
                escrowType, ownerUuid, operationId, Action.COMMIT, true,
                () -> durability.resumeOnWorldThread(
                        () -> consumeInCurrentFlight(
                                operationId, itemId, quantity),
                        () -> false),
                () -> false);
    }

    /** Resumes an exact refund while the caller already owns the actor queue. */
    CompletionStage<Boolean> refundInCurrentFlight(
            TameworkBondedReviveEscrowComponent expected) {
        return begin(expected)
                .thenCompose(refunded -> refunded
                        ? removeTerminalInCurrentFlight(expected)
                        : completed(false));
    }

    /** Removes a terminal tombstone while already inside the actor queue. */
    CompletionStage<Boolean> removeTerminalInCurrentFlight(
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

    private CompletionStage<Boolean> consumeInCurrentFlight(
            String operationId, String itemId, int quantity) {
        TameworkBondedReviveEscrowComponent escrow = current();
        if (escrow == null) return completed(true);
        if (!escrow.matches(operationId, itemId, quantity)) {
            return completed(false);
        }
        if (escrow.phase() == Phase.REFUNDED
                || escrow.phase() == Phase.QUARANTINED
                || escrow.phase() == Phase.REFUNDING) {
            return completed(false);
        }
        if (escrow.phase() != Phase.COMMITTED) {
            if (escrow.phase() != Phase.RESERVED
                    || !escrow.hasExactReservedCharge()) {
                return completed(false);
            }
            escrow.getInventory().clear();
            if (escrow.reservedQuantity() != 0) return completed(false);
            escrow.setPhase(Phase.COMMITTED);
            store.putComponent(actorRef.get(), escrowType, escrow);
        }
        return removeTerminalInCurrentFlight(escrow);
    }

    private CompletionStage<Boolean> refundIdentityInCurrentFlight(
            String operationId, String itemId, int quantity) {
        TameworkBondedReviveEscrowComponent escrow = current();
        if (escrow == null) return completed(true);
        if (!escrow.matches(operationId, itemId, quantity)) {
            return completed(false);
        }
        if (escrow.phase() == Phase.COMMITTED
                || escrow.phase() == Phase.QUARANTINED) {
            return completed(false);
        }
        if (escrow.phase() == Phase.REFUNDED) {
            return removeTerminalInCurrentFlight(escrow);
        }
        return refundInCurrentFlight(escrow);
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
