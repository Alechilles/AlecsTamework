package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.BondedCompanionActionContext;
import com.alechilles.alecstamework.items.components
        .TameworkBondedReviveEscrowComponent;
import com.alechilles.alecstamework.persistence.runtime.player
        .TameworkInventoryOperationReceiptsComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
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
    private final BondedCompanionEscrowSettlementCoordinator settlements;
    private final HytaleBondedCompanionLegacyPaymentAdapter legacyPayments;

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
        this.settlements = new BondedCompanionEscrowSettlementCoordinator(
                store, escrowType, durability, transfer,
                ownerUuid,
                this::actorRef, this::sourceInventory);
        this.legacyPayments = new HytaleBondedCompanionLegacyPaymentAdapter(
                store, legacyType, durability, this::actorRef);
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
            HytaleBondedCompanionChargeReceiptPlan legacy = legacyPayments.find(
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
                    legacyPayments.findByIdentity(operationId);
            TameworkBondedReviveEscrowComponent escrow = currentEscrow();
            if (legacy != null) {
                return escrow == null && legacy.safe()
                        ? legacyIdentityReceipt(
                        operationId, legacy.compensated())
                        : quarantineReceipt(operationId);
            }
            if (escrow == null) return null;
            if (!escrow.operationId().equals(operationId)) {
                return quarantineReceipt(escrow.operationId());
            }
            boolean reserved = escrow.phase()
                    == TameworkBondedReviveEscrowComponent.Phase.RESERVED
                    && escrow.hasExactReservedCharge();
            boolean refunding = escrow.phase()
                    == TameworkBondedReviveEscrowComponent.Phase.REFUNDING
                    && escrow.reservedQuantity() >= 0
                    && escrow.reservedQuantity() <= escrow.quantity();
            if (!reserved && !refunding && !escrow.isEmptyTerminal()) {
                return quarantineReceipt(escrow.operationId());
            }
            return escrowReceipt(
                    operationId, escrow.itemId(), escrow.quantity(),
                    reserved, refunding || escrow.phase()
                            == TameworkBondedReviveEscrowComponent.Phase
                            .REFUNDED, true);
        } catch (RuntimeException | LinkageError failure) {
            return quarantineReceipt(operationId);
        }
    }

    @Override
    public CompletionStage<BondedCompanionActionContext.ChargeReceipt>
            findChargeAsync(String operationId) {
        return completed(findCharge(operationId));
    }

    @Override
    public BondedCompanionActionContext.ChargeReceipt findCharge(
            String operationId, String itemId, int quantity) {
        try {
            store.assertThread();
            HytaleBondedCompanionChargeReceiptPlan legacy = legacyPayments.find(
                    operationId, itemId, quantity);
            TameworkBondedReviveEscrowComponent escrow = currentEscrow();
            if (legacy != null && legacy.hasEvidence()) {
                return escrow == null
                        ? legacyReceipt(operationId, itemId, quantity,
                        legacy.hasCompensatedEvidence())
                        : quarantineReceipt(operationId);
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
                    return escrowReceipt(
                            operationId, itemId, quantity, reserved,
                            refunding || escrow.phase()
                                    == TameworkBondedReviveEscrowComponent.Phase
                                    .REFUNDED, true);
                }
            }
            return quarantineReceipt(operationId);
        } catch (RuntimeException | LinkageError failure) {
            return quarantineReceipt(operationId);
        }
    }

    @Override
    public CompletionStage<BondedCompanionActionContext.ChargeReceipt>
            findChargeAsync(String operationId, String itemId, int quantity) {
        return completed(findCharge(operationId, itemId, quantity));
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
        try {
            store.assertThread();
            if (!validRequest(operationId, itemId, quantity)) {
                return completed(null);
            }
            HytaleBondedCompanionChargeReceiptPlan legacy = legacyPayments.find(
                    operationId, itemId, quantity);
            if (legacy != null && legacy.hasEvidence()) {
                return completed(legacyReceipt(
                        operationId, itemId, quantity,
                        legacy.hasCompensatedEvidence()));
            }
            CombinedItemContainer source = sourceInventory();
            if (source == null || source.getCapacity() <= 0) {
                return completed(null);
            }
            TameworkBondedReviveEscrowComponent escrow = currentEscrow();
            boolean replayed = escrow != null;
            if (escrow == null) {
                escrow = TameworkBondedReviveEscrowComponent.create(
                        source.getCapacity(), operationId, itemId, quantity,
                        System.currentTimeMillis());
                store.putComponent(actorRef(), escrowType, escrow);
            }
            if (!escrow.matches(operationId, itemId, quantity)
                    && escrow.isEmptyTerminal()) {
                String staleOperationId = escrow.operationId();
                return settlements.removeTerminal(escrow).thenCompose(removed ->
                        removed ? durability.resumeOnWorldThread(
                                () -> consumeExactAsync(
                                        operationId, itemId, quantity),
                                () -> quarantineReceipt(operationId))
                                : completed(quarantineReceipt(
                                staleOperationId)));
            }
            if (!escrow.matches(operationId, itemId, quantity)) {
                return completed(quarantineReceipt(
                        escrow.operationId()));
            }
            return prepare(source, escrow, replayed);
        } catch (RuntimeException | LinkageError failure) {
            return completed(quarantineReceipt(operationId));
        }
    }

    private CompletionStage<BondedCompanionActionContext.ChargeReceipt> prepare(
            CombinedItemContainer source,
            TameworkBondedReviveEscrowComponent escrow,
            boolean replayed
    ) {
        if (escrow.phase()
                == TameworkBondedReviveEscrowComponent.Phase.QUARANTINED
                || escrow.phase()
                == TameworkBondedReviveEscrowComponent.Phase.COMMITTED) {
            return completed(quarantineReceipt(escrow.operationId()));
        }
        if (escrow.phase()
                == TameworkBondedReviveEscrowComponent.Phase.REFUNDED) {
            return settlements.removeTerminal(escrow).thenApply(ignored -> null);
        }
        if (escrow.phase()
                == TameworkBondedReviveEscrowComponent.Phase.REFUNDING) {
            return refundEscrow(escrow).thenApply(ignored -> null);
        }
        int reserved = escrow.reservedQuantity();
        if (reserved < 0 || reserved > escrow.quantity()) {
            return quarantine(escrow);
        }
        if (reserved < escrow.quantity()) {
            transfer.reserveRemaining(
                    source, escrow, escrow.quantity() - reserved);
            reserved = escrow.reservedQuantity();
        }
        if (reserved != escrow.quantity()) {
            return restoreInsufficient(escrow);
        }
        escrow.setPhase(TameworkBondedReviveEscrowComponent.Phase.RESERVED);
        store.putComponent(actorRef(), escrowType, escrow);
        BondedCompanionActionContext.ChargeReceipt receipt = escrowReceipt(
                escrow.operationId(), escrow.itemId(), escrow.quantity(),
                true, false, replayed);
        return durability.saveActor().thenApply(saved -> saved.saved()
                ? receipt : quarantineReceipt(escrow.operationId()));
    }

    private CompletionStage<BondedCompanionActionContext.ChargeReceipt>
            restoreInsufficient(
                    TameworkBondedReviveEscrowComponent escrow
            ) {
        return refundEscrow(escrow).thenApply(ignored -> null);
    }

    private CompletionStage<BondedCompanionActionContext.ChargeReceipt>
            quarantine(TameworkBondedReviveEscrowComponent escrow) {
        escrow.setPhase(TameworkBondedReviveEscrowComponent.Phase.QUARANTINED);
        store.putComponent(actorRef(), escrowType, escrow);
        return durability.saveActor().thenApply(ignored ->
                quarantineReceipt(escrow.operationId()));
    }

    private CompletionStage<Boolean> settle(
            String operationId,
            String itemId,
            int quantity,
            boolean consume
    ) {
        return durability.resumeOnWorldThread(() -> {
            TameworkBondedReviveEscrowComponent escrow = currentEscrow();
            if (escrow == null) return completed(true);
            if (!escrow.matches(operationId, itemId, quantity)) {
                return completed(false);
            }
            if (consume) {
                if (escrow.phase()
                        == TameworkBondedReviveEscrowComponent.Phase.REFUNDED
                        || escrow.phase()
                        == TameworkBondedReviveEscrowComponent.Phase.QUARANTINED) {
                    return completed(false);
                }
                if (escrow.phase()
                        != TameworkBondedReviveEscrowComponent.Phase.COMMITTED) {
                    if (escrow.phase()
                            != TameworkBondedReviveEscrowComponent.Phase.RESERVED
                            || !escrow.hasExactReservedCharge()) {
                        return completed(false);
                    }
                    escrow.getInventory().clear();
                    if (escrow.reservedQuantity() != 0) {
                        return completed(false);
                    }
                    escrow.setPhase(
                            TameworkBondedReviveEscrowComponent.Phase.COMMITTED);
                    store.putComponent(actorRef(), escrowType, escrow);
                }
            } else {
                if (escrow.phase()
                        == TameworkBondedReviveEscrowComponent.Phase.COMMITTED
                        || escrow.phase()
                        == TameworkBondedReviveEscrowComponent.Phase.QUARANTINED) {
                    return completed(false);
                }
                if (escrow.phase()
                        == TameworkBondedReviveEscrowComponent.Phase.REFUNDED) {
                    return settlements.removeTerminal(escrow);
                }
                return refundEscrow(escrow);
            }
            return settlements.removeTerminal(escrow);
        }, () -> false);
    }

    private CompletionStage<Boolean> refundEscrow(
            TameworkBondedReviveEscrowComponent escrow) {
        return settlements.refund(escrow);
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

    private boolean validRequest(String operationId, String itemId, int quantity) {
        return operationId != null && !operationId.isBlank()
                && itemId != null && !itemId.isBlank() && quantity > 0;
    }

    private BondedCompanionActionContext.ChargeReceipt escrowReceipt(
            String operationId,
            String itemId,
            int quantity,
            boolean claimPrepared,
            boolean compensationPending,
            boolean replayed
    ) {
        return BondedCompanionChargeReceipts.escrow(
                operationId,
                itemId,
                quantity,
                claimPrepared,
                compensationPending,
                replayed,
                () -> settle(operationId, itemId, quantity, false),
                () -> settle(operationId, itemId, quantity, true));
    }

    private BondedCompanionActionContext.ChargeReceipt legacyReceipt(
            String operationId,
            String itemId,
            int quantity,
            boolean compensated
    ) {
        return BondedCompanionChargeReceipts.legacy(
                operationId,
                compensated,
                () -> legacyPayments.release(
                        operationId, itemId, quantity));
    }

    private BondedCompanionActionContext.ChargeReceipt legacyIdentityReceipt(
            String operationId,
            boolean compensated
    ) {
        return BondedCompanionChargeReceipts.legacy(
                operationId, compensated,
                () -> legacyPayments.releaseByIdentity(operationId));
    }

    private BondedCompanionActionContext.ChargeReceipt quarantineReceipt(
            String operationId) {
        return BondedCompanionChargeReceipts.quarantined(operationId);
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
