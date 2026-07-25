package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Regression coverage for bounded per-hotbar-slot capture source receipts. */
class TameworkCaptureSourceReceiptsComponentTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000001");
    private static final NpcAlias TARGET =
            NpcAlias.parse("20000000-0000-0000-0000-000000000001");

    @Test
    void newerAdmissionReplacesOnlyItsHotbarSlot() {
        CaptureSourceReceipt oldFirst = receipt("old-first", 0, 'a');
        CaptureSourceReceipt second = receipt("second", 1, 'b');
        CaptureSourceReceipt newFirst = receipt("new-first", 0, 'c');

        TameworkCaptureSourceReceiptsComponent component =
                new TameworkCaptureSourceReceiptsComponent()
                        .withReceipt(oldFirst)
                        .withReceipt(second)
                        .withReceipt(newFirst);

        assertEquals(newFirst, component.receiptFor(0));
        assertEquals(second, component.receiptFor(1));
        assertNull(component.receiptFor(2));
    }

    @Test
    void cloneOwnsIndependentEntryCopies() {
        CaptureSourceReceipt receipt = receipt("receipt", 2, 'd');
        TameworkCaptureSourceReceiptsComponent original =
                new TameworkCaptureSourceReceiptsComponent()
                        .withReceipt(receipt);

        TameworkCaptureSourceReceiptsComponent clone = original.clone();

        assertNotSame(original, clone);
        assertEquals(receipt, clone.receiptFor(2));
    }

    private CaptureSourceReceipt receipt(
            String key,
            int slot,
            char fingerprintDigit
    ) {
        return new CaptureSourceReceipt(
                key,
                PROFILE,
                TARGET,
                slot,
                "Tamework_Capture_Source",
                1,
                new Sha256Hash(
                        String.valueOf(fingerprintDigit).repeat(64)
                )
        );
    }
}
