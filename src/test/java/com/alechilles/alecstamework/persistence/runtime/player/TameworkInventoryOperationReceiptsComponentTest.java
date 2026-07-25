package com.alechilles.alecstamework.persistence.runtime.player;

import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Bounded, conflict-safe behavior for shared player inventory receipts. */
class TameworkInventoryOperationReceiptsComponentTest {

    @Test
    void exactReplayIsIdempotentAndConflictsFailClosed() {
        InventoryOperationReceipt receipt = receipt(1);
        TameworkInventoryOperationReceiptsComponent component =
                new TameworkInventoryOperationReceiptsComponent()
                        .withReceipt(receipt)
                        .withReceipt(receipt);

        assertEquals(receipt, component.receiptFor(receipt.receiptKey()));
        assertThrows(
                IllegalStateException.class,
                () -> component.withReceipt(new InventoryOperationReceipt(
                        receipt.receiptKey(),
                        receipt.operationId(),
                        receipt.operationKind(),
                        Sha256Hash.ofUtf8("different"),
                        receipt.installedAtMs()
                ))
        );
    }

    @Test
    void removalAndClonePreserveIndependentExactEvidence() {
        InventoryOperationReceipt first = receipt(1);
        InventoryOperationReceipt second = receipt(2);
        TameworkInventoryOperationReceiptsComponent original =
                new TameworkInventoryOperationReceiptsComponent()
                        .withReceipt(second)
                        .withReceipt(first);
        TameworkInventoryOperationReceiptsComponent clone =
                original.clone().withoutReceipt(first.receiptKey());

        assertEquals(first, original.receiptFor(first.receiptKey()));
        assertNull(clone.receiptFor(first.receiptKey()));
        assertEquals(second, clone.receiptFor(second.receiptKey()));
    }

    @Test
    void unresolvedReceiptsAreNeverEvictedAtCapacity() {
        TameworkInventoryOperationReceiptsComponent component =
                new TameworkInventoryOperationReceiptsComponent();
        for (int index = 0;
             index < TameworkInventoryOperationReceiptsComponent.MAX_RECEIPTS;
             index++) {
            component = component.withReceipt(receipt(index));
        }
        TameworkInventoryOperationReceiptsComponent full = component;

        assertThrows(
                IllegalStateException.class,
                () -> full.withReceipt(receipt(
                        TameworkInventoryOperationReceiptsComponent
                                .MAX_RECEIPTS
                ))
        );
    }

    private InventoryOperationReceipt receipt(int ordinal) {
        return new InventoryOperationReceipt(
                "receipt-" + ordinal,
                new OperationId(new UUID(0, ordinal + 1L)),
                new OperationKind("paid_revival"),
                Sha256Hash.ofUtf8("plan-" + ordinal),
                ordinal % 2 == 0 ? -1_000L - ordinal : ordinal
        );
    }
}
