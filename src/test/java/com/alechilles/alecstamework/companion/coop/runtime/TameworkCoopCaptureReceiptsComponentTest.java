package com.alechilles.alecstamework.companion.coop.runtime;

import com.alechilles.alecstamework.companion.coop.CoopCaptureReceipt;
import com.alechilles.alecstamework.companion.coop.CoopSlotKey;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Regression coverage for bounded per-slot physical coop receipts. */
class TameworkCoopCaptureReceiptsComponentTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000001");
    private static final NpcAlias SOURCE =
            NpcAlias.parse("20000000-0000-0000-0000-000000000001");

    @Test
    void replayReplacesOneSlotWithoutGrowingReceiptHistory() {
        CoopSlotKey first = slot(0);
        CoopSlotKey second = slot(1);
        CoopCaptureReceipt oldFirst = receipt(
                "60000000-0000-0000-0000-000000000001",
                first,
                "receipt-old"
        );
        CoopCaptureReceipt newFirst = receipt(
                "60000000-0000-0000-0000-000000000002",
                first,
                "receipt-new"
        );
        CoopCaptureReceipt secondReceipt = receipt(
                "60000000-0000-0000-0000-000000000003",
                second,
                "receipt-second"
        );

        TameworkCoopCaptureReceiptsComponent component =
                new TameworkCoopCaptureReceiptsComponent()
                        .withReceipt(oldFirst)
                        .withReceipt(secondReceipt)
                        .withReceipt(newFirst);

        assertEquals(2, component.receiptCount());
        assertEquals(newFirst, component.receiptFor(first));
        assertEquals(secondReceipt, component.receiptFor(second));
    }

    @Test
    void cloneOwnsIndependentEntryCopies() {
        CoopSlotKey first = slot(0);
        TameworkCoopCaptureReceiptsComponent original =
                new TameworkCoopCaptureReceiptsComponent().withReceipt(
                        receipt(
                                "60000000-0000-0000-0000-000000000001",
                                first,
                                "receipt"
                        )
                );

        TameworkCoopCaptureReceiptsComponent clone = original.clone();

        assertNotSame(original, clone);
        assertEquals(original.receiptFor(first), clone.receiptFor(first));
    }

    @Test
    void oneBlockComponentCannotMixDifferentPhysicalCoops() {
        TameworkCoopCaptureReceiptsComponent component =
                new TameworkCoopCaptureReceiptsComponent().withReceipt(
                        receipt(
                                "60000000-0000-0000-0000-000000000001",
                                slot(0),
                                "receipt"
                        )
                );
        CoopSlotKey otherBlock =
                new CoopSlotKey("world", "coop", 11, 64, 20, 1);

        assertThrows(
                IllegalStateException.class,
                () -> component.withReceipt(receipt(
                        "60000000-0000-0000-0000-000000000002",
                        otherBlock,
                        "other"
                ))
        );
    }

    private CoopCaptureReceipt receipt(
            String operationId,
            CoopSlotKey slot,
            String receiptKey
    ) {
        return new CoopCaptureReceipt(
                OperationId.parse(operationId),
                PROFILE,
                SOURCE,
                slot,
                receiptKey
        );
    }

    private CoopSlotKey slot(int residentSlot) {
        return new CoopSlotKey(
                "world", "coop", 10, 64, 20, residentSlot
        );
    }
}
