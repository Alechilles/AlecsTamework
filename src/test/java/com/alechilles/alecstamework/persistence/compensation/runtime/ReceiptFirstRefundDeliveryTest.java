package com.alechilles.alecstamework.persistence.compensation.runtime;

import com.alechilles.alecstamework.persistence.compensation.RefundClaim;
import com.alechilles.alecstamework.persistence.compensation.RefundItem;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for receipt-first exact refund delivery and crash replay. */
class ReceiptFirstRefundDeliveryTest {
    private static final OperationId OPERATION = OperationId.parse(
            "10000000-0000-0000-0000-000000000001"
    );
    private final ReceiptFirstRefundDelivery delivery =
            new ReceiptFirstRefundDelivery();

    @Test
    void existingExactReceiptsConfirmWithoutAddingAgain() {
        RefundClaim claim = claim(
                new RefundItem("Ingredient_Stick", 3),
                new RefundItem("Ingredient_Fibre", 2)
        );
        FakeInventory inventory = new FakeInventory();
        inventory.seed(claim, 0, "Ingredient_Stick", 3);
        inventory.seed(claim, 1, "Ingredient_Fibre", 2);

        LiveOperationResult result =
                delivery.applyOrResolve(claim, inventory);

        assertEquals(
                LiveOperationResult.Status.CONFIRMED,
                result.status()
        );
        assertEquals(0, inventory.adds.size());
    }

    @Test
    void absentReceiptIsNeverTreatedAsCompletion() {
        RefundClaim claim = claim(
                new RefundItem("Ingredient_Stick", 3)
        );
        FakeInventory inventory = new FakeInventory();
        inventory.mode = AddMode.REJECT;

        LiveOperationResult result =
                delivery.applyOrResolve(claim, inventory);

        assertEquals(
                LiveOperationResult.Status.RETRYABLE,
                result.status()
        );
        assertEquals("refund_inventory_unavailable", result.code());
        assertEquals(1, inventory.adds.size());
    }

    @Test
    void addsExactIdsQuantitiesAndDeterministicLineReceipts() {
        RefundClaim claim = claim(
                new RefundItem("Ingredient_Stick", 3),
                new RefundItem("Ingredient_Fibre", 2)
        );
        FakeInventory inventory = new FakeInventory();

        LiveOperationResult result =
                delivery.applyOrResolve(claim, inventory);

        assertEquals(
                LiveOperationResult.Status.CONFIRMED,
                result.status()
        );
        assertEquals(List.of(
                new Add(
                        "Ingredient_Stick",
                        3,
                        claim.receiptKey() + "/line/0"
                ),
                new Add(
                        "Ingredient_Fibre",
                        2,
                        claim.receiptKey() + "/line/1"
                )
        ), inventory.adds);
    }

    @Test
    void resumesAnExactlyReceiptedPartialLineWithoutDuplicatingIt() {
        RefundClaim claim = claim(
                new RefundItem("Ingredient_Stick", 3)
        );
        FakeInventory inventory = new FakeInventory();
        inventory.seed(claim, 0, "Ingredient_Stick", 1);

        LiveOperationResult result =
                delivery.applyOrResolve(claim, inventory);

        assertEquals(
                LiveOperationResult.Status.CONFIRMED,
                result.status()
        );
        assertEquals(List.of(new Add(
                "Ingredient_Stick",
                2,
                claim.receiptKey() + "/line/0"
        )), inventory.adds);
    }

    @Test
    void crashAfterAppliedWriteConfirmsFromImmediateReadback() {
        RefundClaim claim = claim(
                new RefundItem("Ingredient_Stick", 3)
        );
        FakeInventory inventory = new FakeInventory();
        inventory.mode = AddMode.APPLY_THEN_THROW;

        LiveOperationResult result =
                delivery.applyOrResolve(claim, inventory);

        assertEquals(
                LiveOperationResult.Status.CONFIRMED,
                result.status()
        );
        assertEquals(3, inventory.lines.values().iterator().next().quantity);
    }

    @Test
    void ambiguousPartialWriteFailsClosed() {
        RefundClaim claim = claim(
                new RefundItem("Ingredient_Stick", 3)
        );
        FakeInventory inventory = new FakeInventory();
        inventory.mode = AddMode.PARTIAL;

        LiveOperationResult result =
                delivery.applyOrResolve(claim, inventory);

        assertEquals(
                LiveOperationResult.Status.UNKNOWN,
                result.status()
        );
        assertEquals("refund_write_ambiguous", result.code());
    }

    @Test
    void conflictingReceiptFailsClosedWithoutAdding() {
        RefundClaim claim = claim(
                new RefundItem("Ingredient_Stick", 3)
        );
        FakeInventory inventory = new FakeInventory();
        inventory.seed(claim, 0, "Ingredient_Fibre", 3);

        LiveOperationResult result =
                delivery.applyOrResolve(claim, inventory);

        assertEquals(
                LiveOperationResult.Status.UNKNOWN,
                result.status()
        );
        assertEquals("refund_receipt_conflict", result.code());
        assertTrue(inventory.adds.isEmpty());
    }

    private RefundClaim claim(RefundItem... items) {
        return new RefundClaim(
                OPERATION,
                UUID.fromString(
                        "20000000-0000-0000-0000-000000000001"
                ),
                "world",
                List.of(items),
                "capture_source",
                "refund:" + OPERATION,
                -700,
                null,
                null
        );
    }

    private enum AddMode {
        APPLY,
        REJECT,
        PARTIAL,
        APPLY_THEN_THROW
    }

    private static final class FakeInventory
            implements ReceiptFirstRefundDelivery.ReceiptInventory {
        private final Map<String, Line> lines = new HashMap<>();
        private final List<Add> adds = new ArrayList<>();
        private AddMode mode = AddMode.APPLY;

        private void seed(
                RefundClaim claim,
                int line,
                String itemId,
                int quantity
        ) {
            lines.put(
                    ReceiptFirstRefundDelivery.lineReceipt(claim, line),
                    new Line(itemId, quantity)
            );
        }

        @Override
        public ReceiptFirstRefundDelivery.ReceiptObservation observe(
                String expectedItemId,
                String receipt
        ) {
            Line line = lines.get(receipt);
            return line == null
                    ? ReceiptFirstRefundDelivery.ReceiptObservation
                            .readable(0, false)
                    : ReceiptFirstRefundDelivery.ReceiptObservation
                            .readable(
                                    line.quantity,
                                    !expectedItemId.equals(line.itemId)
                            );
        }

        @Override
        public ReceiptFirstRefundDelivery.AddResult add(
                String itemId,
                int quantity,
                String receipt
        ) {
            adds.add(new Add(itemId, quantity, receipt));
            if (mode == AddMode.REJECT) {
                return ReceiptFirstRefundDelivery.AddResult.REJECTED;
            }
            int written = mode == AddMode.PARTIAL ? 1 : quantity;
            Line before = lines.get(receipt);
            lines.put(
                    receipt,
                    new Line(
                            itemId,
                            (before == null ? 0 : before.quantity) + written
                    )
            );
            if (mode == AddMode.APPLY_THEN_THROW) {
                throw new IllegalStateException("simulated crash seam");
            }
            return ReceiptFirstRefundDelivery.AddResult.APPLIED;
        }
    }

    private record Line(String itemId, int quantity) {
    }

    private record Add(String itemId, int quantity, String receipt) {
    }
}
