package com.alechilles.alecstamework.items;

import static com.alechilles.alecstamework.items
        .HytaleBondedCompanionEscrowInventoryTest.ITEM;
import static com.alechilles.alecstamework.items
        .HytaleBondedCompanionEscrowInventoryTest.OTHER;
import static com.alechilles.alecstamework.items
        .HytaleBondedCompanionEscrowInventoryTest.operation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.BondedCompanionActionContext;
import com.alechilles.alecstamework.api.BondedCompanionReviveCost;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionPaymentRecoveryService;
import com.alechilles.alecstamework.persistence.operation
        .BondedCompanionPaymentOperationId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Focused ordered-recipe coverage for the bonded multi-item escrow. */
class HytaleBondedCompanionMultiItemEscrowTest {
    private static final List<BondedCompanionReviveCost> COSTS = List.of(
            new BondedCompanionReviveCost(ITEM, 2),
            new BondedCompanionReviveCost(OTHER, 4));

    @Test
    void reservesEveryOrderedRecipeLineInOneDurableEscrow() throws Exception {
        try (var fixture = funded(4)) {
            BondedCompanionActionContext.ChargeReceipt receipt = fixture.inventory
                    .consumeExactAsync(operation(900), COSTS)
                    .toCompletableFuture().join();

            assertNotNull(receipt);
            assertEquals(COSTS, fixture.escrow().costs());
            assertTrue(fixture.escrow().hasExactReservedCharge());
            assertEquals(0, fixture.sourceQuantity(ITEM));
            assertEquals(0, fixture.sourceQuantity(OTHER));
        }
    }

    @Test
    void legacySingletonViewsFailClosedForMultiLineEscrow() throws Exception {
        try (var fixture = funded(4)) {
            String operation = operation(902);
            fixture.inventory.consumeExactAsync(operation, COSTS)
                    .toCompletableFuture().join();

            assertEquals(0, fixture.inventory.availableQuantity(
                    operation, ITEM, 2));
            assertEquals(List.of(2, 4), fixture.inventory.availableQuantities(
                    operation, COSTS));
            assertTrue(fixture.inventory.findCharge(operation, ITEM, 2)
                    .quarantined());
        }
    }

    @Test
    void reorderedRecipeCannotSettleExistingFrozenEscrow() throws Exception {
        try (var fixture = funded(4)) {
            String operation = operation(903);
            fixture.inventory.consumeExactAsync(operation, COSTS)
                    .toCompletableFuture().join();

            BondedCompanionActionContext.ChargeReceipt mismatched =
                    fixture.inventory.consumeExactAsync(operation, List.of(
                            COSTS.get(1), COSTS.getFirst()))
                            .toCompletableFuture().join();

            assertTrue(mismatched.quarantined());
            assertFalse(mismatched.completeAsync().toCompletableFuture().join());
            assertTrue(fixture.escrow().matches(operation, COSTS));
        }
    }

    @Test
    void reorderedReceiptCannotAuthorizeRecoveryRevive() throws Exception {
        try (var fixture = funded(4)) {
            String operation = operation(904);
            fixture.inventory.consumeExactAsync(operation, COSTS)
                    .toCompletableFuture().join();
            BondedCompanionActionContext.ChargeReceipt reordered = fixture.inventory
                    .consumeExactAsync(operation, List.of(
                            COSTS.get(1), COSTS.getFirst()))
                    .toCompletableFuture().join();
            var store = new HytaleBondedCompanionEscrowInventoryTest.RecoveryStore(
                    HytaleBondedCompanionEscrowInventoryTest.RecoveryResult.MISSING,
                    false, 904);

            BondedCompanionPaymentRecoveryService.Outcome outcome =
                    new BondedCompanionPaymentRecoveryService(store.store,
                            () -> -5_000L).recover(
                            BondedCompanionPaymentOperationId.parse(operation)
                                    .orElseThrow(), recovered(reordered))
                            .toCompletableFuture().join();

            assertEquals(BondedCompanionPaymentRecoveryService.Outcome.QUARANTINED,
                    outcome);
            assertEquals(0, store.revives);
        }
    }

    @Test
    void insufficientSecondLineRestoresFirstWithoutReceipt() throws Exception {
        try (var fixture = funded(3)) {
            BondedCompanionActionContext.ChargeReceipt receipt = fixture.inventory
                    .consumeExactAsync(operation(901), COSTS)
                    .toCompletableFuture().join();

            assertNull(receipt);
            assertEquals(2, fixture.sourceQuantity(ITEM));
            assertEquals(3, fixture.sourceQuantity(OTHER));
            assertNull(fixture.escrow());
        }
    }

    @Test
    void freshShortageDoesNotMutateOrDependOnARefundSave() throws Exception {
        try (var fixture = funded(3)) {
            fixture.failNextSave();

            BondedCompanionActionContext.ChargeReceipt receipt = fixture.inventory
                    .consumeExactAsync(operation(905), COSTS)
                    .toCompletableFuture().join();

            assertNull(receipt);
            assertEquals(2, fixture.sourceQuantity(ITEM));
            assertEquals(3, fixture.sourceQuantity(OTHER));
            assertNull(fixture.escrow());
            assertEquals(0, fixture.saveCalls());
        }
    }

    private static HytaleBondedCompanionEscrowInventoryTest.Fixture funded(
            int secondQuantity
    ) throws Exception {
        var fixture = new HytaleBondedCompanionEscrowInventoryTest.Fixture();
        fixture.setSourceSlot((short) 0, ITEM, 2);
        fixture.setSourceSlot((short) 1, OTHER, secondQuantity);
        return fixture;
    }

    private static BondedCompanionActionContext.Inventory recovered(
            BondedCompanionActionContext.ChargeReceipt receipt
    ) {
        return new BondedCompanionActionContext.Inventory() {
            @Override public int availableQuantity(String itemId) {
                return 0;
            }

            @Override public BondedCompanionActionContext.ChargeReceipt
                    findCharge(String operationId) {
                return receipt;
            }

            @Override public BondedCompanionActionContext.ChargeReceipt consumeExact(
                    String operationId, String itemId, int quantity
            ) {
                return null;
            }
        };
    }
}
