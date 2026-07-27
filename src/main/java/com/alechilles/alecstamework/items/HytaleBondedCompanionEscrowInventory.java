package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.BondedCompanionActionContext;
import com.alechilles.alecstamework.api.BondedCompanionReviveCost;
import com.alechilles.alecstamework.items.components
        .TameworkBondedReviveEscrowComponent;
import com.alechilles.alecstamework.persistence.runtime.player
        .TameworkInventoryOperationReceiptsComponent;
import com.alechilles.alecstamework.persistence.operation
        .BondedCompanionPaymentOperationId;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** World-thread player inventory backed by a hidden, durably saved escrow. */
final class HytaleBondedCompanionEscrowInventory
        implements BondedCompanionActionContext.Inventory {
    private final World world;
    private final Store<EntityStore> store;
    private final UUID ownerUuid;
    private final String worldKey;
    private final ComponentType<EntityStore,
            TameworkBondedReviveEscrowComponent> escrowType;
    private final BondedCompanionEscrowDurability durability;
    private final Supplier<CombinedItemContainer> sourceOverride;
    private final BondedCompanionEscrowTransfer transfer;
    private final BondedCompanionEscrowPreflight preflight;
    private final BondedCompanionEscrowSettlementCoordinator settlements;
    private final HytaleBondedCompanionLegacyPaymentAdapter legacyPayments;
    private final BondedCompanionEscrowReceiptFactory receipts;

    HytaleBondedCompanionEscrowInventory(
            World world,
            Store<EntityStore> store,
            UUID ownerUuid,
            String worldKey,
            ComponentType<EntityStore,
                    TameworkBondedReviveEscrowComponent> escrowType,
            @Nullable ComponentType<EntityStore,
                    TameworkInventoryOperationReceiptsComponent> legacyType
    ) {
        this(world, store, ownerUuid, worldKey, escrowType, legacyType,
                new HytaleBondedCompanionEscrowDurability(
                        world, store, worldKey, ownerUuid));
    }

    HytaleBondedCompanionEscrowInventory(
            World world,
            Store<EntityStore> store,
            UUID ownerUuid,
            String worldKey,
            ComponentType<EntityStore,
                    TameworkBondedReviveEscrowComponent> escrowType,
            @Nullable ComponentType<EntityStore,
                    TameworkInventoryOperationReceiptsComponent> legacyType,
            BondedCompanionEscrowDurability durability
    ) {
        this(world, store, ownerUuid, worldKey, escrowType, legacyType,
                durability, null, new HytaleBondedCompanionEscrowTransfer());
    }

    HytaleBondedCompanionEscrowInventory(
            World world,
            Store<EntityStore> store,
            UUID ownerUuid,
            String worldKey,
            ComponentType<EntityStore,
                    TameworkBondedReviveEscrowComponent> escrowType,
            @Nullable ComponentType<EntityStore,
                    TameworkInventoryOperationReceiptsComponent> legacyType,
            BondedCompanionEscrowDurability durability,
            @Nullable Supplier<CombinedItemContainer> sourceOverride
    ) {
        this(world, store, ownerUuid, worldKey, escrowType, legacyType,
                durability, sourceOverride,
                new HytaleBondedCompanionEscrowTransfer());
    }

    HytaleBondedCompanionEscrowInventory(
            World world,
            Store<EntityStore> store,
            UUID ownerUuid,
            String worldKey,
            ComponentType<EntityStore,
                    TameworkBondedReviveEscrowComponent> escrowType,
            @Nullable ComponentType<EntityStore,
                    TameworkInventoryOperationReceiptsComponent> legacyType,
            BondedCompanionEscrowDurability durability,
            @Nullable Supplier<CombinedItemContainer> sourceOverride,
            BondedCompanionEscrowTransfer transfer
    ) {
        this.world = Objects.requireNonNull(world, "world");
        this.store = Objects.requireNonNull(store, "store");
        this.ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        this.worldKey = requireText(worldKey, "worldKey");
        this.escrowType = Objects.requireNonNull(escrowType, "escrowType");
        this.durability = Objects.requireNonNull(durability, "durability");
        this.sourceOverride = sourceOverride;
        this.transfer = Objects.requireNonNull(transfer, "transfer");
        this.preflight = new BondedCompanionEscrowPreflight(transfer);
        this.settlements = new BondedCompanionEscrowSettlementCoordinator(
                store, escrowType, durability, transfer,
                ownerUuid,
                this::actorRef, this::sourceInventory);
        this.legacyPayments = new HytaleBondedCompanionLegacyPaymentAdapter(
                store, legacyType, durability, this::actorRef);
        this.receipts = new BondedCompanionEscrowReceiptFactory(
                settlements, legacyPayments);
    }

    @Override
    public int availableQuantity(String itemId) {
        try {
            store.assertThread();
            CombinedItemContainer source = sourceInventory();
            if (source == null) return 0;
            return transfer.availableQuantity(source, itemId);
        } catch (RuntimeException | LinkageError failure) {
            return 0;
        }
    }

    @Override
    public int availableQuantity(
            String operationId, String itemId, int quantity) {
        try {
            store.assertThread();
            HytaleBondedCompanionChargeReceiptPlan legacy =
                    canonical(operationId) ? null : legacyPayments.find(
                    operationId, itemId, quantity);
            if (legacy != null && legacy.hasEvidence()) return 0;
            int source = availableQuantity(itemId);
            TameworkBondedReviveEscrowComponent escrow = currentEscrow();
            if (escrow == null || escrow.isEmptyTerminal()) return source;
            if (!escrow.matches(operationId, itemId, quantity)
                    || escrow.phase()
                    != TameworkBondedReviveEscrowComponent.Phase.RESERVED
                    || !escrow.hasExactReservedCharge()) return 0;
            return Math.addExact(source, escrow.reservedQuantity());
        } catch (RuntimeException | LinkageError failure) {
            return 0;
        }
    }

    @Override
    public BondedCompanionActionContext.ChargeReceipt findCharge(
            String operationId) {
        try {
            store.assertThread();
            HytaleBondedCompanionLegacyPaymentAdapter.OperationEvidence legacy =
                    canonical(operationId) ? null
                            : legacyPayments.findByIdentity(operationId);
            TameworkBondedReviveEscrowComponent escrow = currentEscrow();
            if (legacy != null) {
                return escrow == null && legacy.safe()
                        ? receipts.legacyIdentity(
                        operationId, legacy.compensated())
                        : receipts.quarantined(operationId);
            }
            if (escrow == null) return null;
            if (!escrow.operationId().equals(operationId)) {
                return receipts.quarantined(escrow.operationId());
            }
            boolean reserved = escrow.phase()
                    == TameworkBondedReviveEscrowComponent.Phase.RESERVED
                    && escrow.hasExactReservedCharge();
            boolean refunding = escrow.phase()
                    == TameworkBondedReviveEscrowComponent.Phase.REFUNDING
                    && escrow.reservedState()
                    != TameworkBondedReviveEscrowComponent.ReservedState.INVALID;
            if (!reserved && !refunding && !escrow.isEmptyTerminal()) {
                return receipts.quarantined(escrow.operationId());
            }
            return receipts.escrow(
                    operationId, escrow.costs(),
                    reserved, refunding || escrow.phase()
                            == TameworkBondedReviveEscrowComponent.Phase
                            .REFUNDED, true);
        } catch (RuntimeException | LinkageError failure) {
            return receipts.quarantined(operationId);
        }
    }

    @Override
    public CompletionStage<BondedCompanionActionContext.ChargeReceipt>
            findChargeAsync(String operationId) {
        return settlements.read(
                operationId,
                () -> findCharge(operationId),
                () -> receipts.quarantined(operationId));
    }

    @Override
    public BondedCompanionActionContext.ChargeReceipt findCharge(
            String operationId, String itemId, int quantity) {
        try {
            store.assertThread();
            HytaleBondedCompanionChargeReceiptPlan legacy =
                    canonical(operationId) ? null : legacyPayments.find(
                    operationId, itemId, quantity);
            TameworkBondedReviveEscrowComponent escrow = currentEscrow();
            if (legacy != null && legacy.hasEvidence()) {
                return escrow == null
                        ? receipts.legacy(operationId, itemId, quantity,
                        legacy.hasCompensatedEvidence())
                        : receipts.quarantined(operationId);
            }
            if (escrow == null) return null;
            if (escrow.matches(operationId, itemId, quantity)) {
                boolean reserved = escrow.phase()
                        == TameworkBondedReviveEscrowComponent.Phase.RESERVED
                        && escrow.hasExactReservedCharge();
                boolean terminal = (escrow.phase()
                        == TameworkBondedReviveEscrowComponent.Phase.COMMITTED
                        || escrow.phase()
                        == TameworkBondedReviveEscrowComponent.Phase.REFUNDED)
                        && escrow.reservedQuantity() == 0;
                boolean refunding = escrow.phase()
                        == TameworkBondedReviveEscrowComponent.Phase.REFUNDING
                        && escrow.reservedQuantity() >= 0
                        && escrow.reservedQuantity() <= escrow.quantity();
                if (reserved || refunding || terminal) {
                    return receipts.escrow(
                            operationId, itemId, quantity, reserved,
                            refunding || escrow.phase()
                                    == TameworkBondedReviveEscrowComponent.Phase
                                    .REFUNDED, true);
                }
            }
            return receipts.quarantined(operationId);
        } catch (RuntimeException | LinkageError failure) {
            return receipts.quarantined(operationId);
        }
    }

    @Override
    public CompletionStage<BondedCompanionActionContext.ChargeReceipt>
            findChargeAsync(String operationId, String itemId, int quantity) {
        return settlements.read(
                operationId,
                () -> findCharge(operationId, itemId, quantity),
                () -> receipts.quarantined(operationId));
    }

    @Override
    public BondedCompanionActionContext.ChargeReceipt consumeExact(
            String operationId, String itemId, int quantity) {
        return null;
    }

    @Override
    public CompletionStage<BondedCompanionActionContext.ChargeReceipt>
            consumeExactAsync(
                    String operationId, String itemId, int quantity) {
        return consumeExactAsync(operationId, List.of(
                new BondedCompanionReviveCost(itemId, quantity)));
    }

    @Override
    public List<Integer> availableQuantities(
            String operationId, List<BondedCompanionReviveCost> costs) {
        try {
            store.assertThread();
            List<BondedCompanionReviveCost> recipe = List.copyOf(costs);
            CombinedItemContainer source = sourceInventory();
            TameworkBondedReviveEscrowComponent escrow = currentEscrow();
            boolean retained = escrow != null && escrow.matches(operationId, recipe)
                    && escrow.phase() == TameworkBondedReviveEscrowComponent.Phase.RESERVED
                    && escrow.hasExactReservedCharge();
            return recipe.stream().map(cost -> {
                int owned = source == null ? 0 : transfer.availableQuantity(
                        source, cost.itemId());
                return retained ? Math.addExact(owned,
                        escrow.reservedQuantity(cost.itemId())) : owned;
            }).toList();
        } catch (RuntimeException | LinkageError failure) {
            return costs.stream().map(ignored -> 0).toList();
        }
    }

    @Override
    public CompletionStage<BondedCompanionActionContext.ChargeReceipt>
            consumeExactAsync(
                    String operationId, List<BondedCompanionReviveCost> costs) {
        try {
            store.assertThread();
            if (!validRequest(operationId, costs)) {
                return completed(null);
            }
            return settlements.prepare(
                    operationId,
                    () -> consumeExactInCurrentFlight(
                            operationId, List.copyOf(costs)),
                    () -> receipts.quarantined(operationId));
        } catch (RuntimeException | LinkageError failure) {
            return completed(receipts.quarantined(operationId));
        }
    }

    private CompletionStage<BondedCompanionActionContext.ChargeReceipt>
            consumeExactInCurrentFlight(
                    String operationId, List<BondedCompanionReviveCost> costs) {
        try {
            store.assertThread();
            BondedCompanionReviveCost first = costs.getFirst();
            HytaleBondedCompanionChargeReceiptPlan legacy = costs.size() == 1
                    && !canonical(operationId) ? legacyPayments.find(
                    operationId, first.itemId(), first.quantity()) : null;
            if (legacy != null && legacy.hasEvidence()) {
                return completed(receipts.legacy(
                        operationId, first.itemId(), first.quantity(),
                        legacy.hasCompensatedEvidence()));
            }
            CombinedItemContainer source = sourceInventory();
            if (source == null || source.getCapacity() <= 0) {
                return completed(null);
            }
            TameworkBondedReviveEscrowComponent escrow = currentEscrow();
            boolean replayed = escrow != null;
            if (escrow == null) {
                if (!preflight.canReserveFresh(source, costs)) {
                    return completed(null);
                }
                escrow = TameworkBondedReviveEscrowComponent.create(
                        source.getCapacity(), operationId, costs,
                        System.currentTimeMillis());
                store.putComponent(actorRef(), escrowType, escrow);
            }
            if (!escrow.matches(operationId, costs)
                    && escrow.isEmptyTerminal()) {
                String staleOperationId = escrow.operationId();
                return settlements.removeTerminalInCurrentFlight(escrow)
                        .thenCompose(removed ->
                        removed ? durability.resumeOnWorldThread(
                                () -> consumeExactInCurrentFlight(
                                        operationId, costs),
                                () -> receipts.quarantined(operationId))
                                : completed(receipts.quarantined(
                                staleOperationId)));
            }
            if (!escrow.matches(operationId, costs)) {
                return completed(receipts.quarantined(
                        escrow.operationId()));
            }
            return prepare(source, escrow, replayed, costs);
        } catch (RuntimeException | LinkageError failure) {
            return completed(receipts.quarantined(operationId));
        }
    }

    private CompletionStage<BondedCompanionActionContext.ChargeReceipt> prepare(
            CombinedItemContainer source,
            TameworkBondedReviveEscrowComponent escrow,
            boolean replayed,
            List<BondedCompanionReviveCost> costs
    ) {
        if (escrow.phase()
                == TameworkBondedReviveEscrowComponent.Phase.QUARANTINED
                || escrow.phase()
                == TameworkBondedReviveEscrowComponent.Phase.COMMITTED) {
            return completed(receipts.quarantined(escrow.operationId()));
        }
        if (escrow.phase()
                == TameworkBondedReviveEscrowComponent.Phase.REFUNDED) {
            return settlements.removeTerminalInCurrentFlight(escrow)
                    .thenApply(ignored -> null);
        }
        if (escrow.phase()
                == TameworkBondedReviveEscrowComponent.Phase.REFUNDING) {
            return settlements.refundInCurrentFlight(escrow)
                    .thenApply(ignored -> null);
        }
        if (escrow.reservedState()
                == TameworkBondedReviveEscrowComponent.ReservedState.INVALID) {
            return quarantine(escrow);
        }
        for (BondedCompanionReviveCost cost : costs) {
            int reserved = escrow.reservedQuantity(cost.itemId());
            if (reserved < 0 || reserved > cost.quantity()) {
                return quarantine(escrow);
            }
            if (reserved < cost.quantity()) {
                transfer.reserveRemaining(source, escrow, cost.itemId(),
                        cost.quantity() - reserved);
            }
        }
        if (!escrow.hasExactReservedCharge()) {
            return restoreInsufficient(escrow);
        }
        escrow.setPhase(TameworkBondedReviveEscrowComponent.Phase.RESERVED);
        store.putComponent(actorRef(), escrowType, escrow);
        BondedCompanionActionContext.ChargeReceipt receipt = receipts.escrow(
                escrow.operationId(), costs,
                true, false, replayed);
        return durability.saveActor().thenApply(saved -> saved.saved()
                ? receipt : receipts.quarantined(escrow.operationId()));
    }

    private CompletionStage<BondedCompanionActionContext.ChargeReceipt>
            restoreInsufficient(
                    TameworkBondedReviveEscrowComponent escrow
            ) {
        return settlements.refundInCurrentFlight(escrow)
                .thenApply(ignored -> null);
    }

    private CompletionStage<BondedCompanionActionContext.ChargeReceipt>
            quarantine(TameworkBondedReviveEscrowComponent escrow) {
        escrow.setPhase(TameworkBondedReviveEscrowComponent.Phase.QUARANTINED);
        store.putComponent(actorRef(), escrowType, escrow);
        return durability.saveActor().thenApply(ignored ->
                receipts.quarantined(escrow.operationId()));
    }

    private CombinedItemContainer sourceInventory() {
        store.assertThread();
        if (sourceOverride != null) return sourceOverride.get();
        Ref<EntityStore> actor = actorRef();
        return InventoryComponent.BACKPACK_STORAGE_HOTBAR == null
                ? null : InventoryComponent.getCombined(
                store, actor, InventoryComponent.BACKPACK_STORAGE_HOTBAR);
    }

    private TameworkBondedReviveEscrowComponent currentEscrow() {
        store.assertThread();
        return store.getComponent(actorRef(), escrowType);
    }

    private Ref<EntityStore> actorRef() {
        store.assertThread();
        Ref<EntityStore> actor = world.getEntityRef(ownerUuid);
        if (actor == null || !actor.isValid() || actor.getStore() != store) {
            throw new IllegalStateException("Bonded revive actor is unavailable");
        }
        return actor;
    }

    private boolean validRequest(
            String operationId, List<BondedCompanionReviveCost> costs) {
        if (operationId == null || operationId.isBlank() || costs == null
                || costs.isEmpty()) return false;
        try {
            TameworkBondedReviveEscrowComponent.create(
                    (short) 1, operationId, costs, 0L);
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private boolean canonical(String operationId) {
        return BondedCompanionPaymentOperationId.parse(operationId).isPresent();
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }
}
