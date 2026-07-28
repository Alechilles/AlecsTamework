package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.companion.capture.runtime.CaptureReleaseWorldAttempt.InventoryStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Exact hotbar selection tests for captured-artifact release recovery. */
class HytaleCompanionCaptureReleaseAttemptGatewayTest {

    @Test
    void uniquelyMovedSourceIsSelectedWhereItCurrentlyExists() {
        var selection =
                HytaleCaptureReleaseInventoryGateway.selectMatches(
                        List.of(
                                InventoryStatus.CONFLICT,
                                InventoryStatus.CONFLICT,
                                InventoryStatus.SOURCE
                        )
                );

        assertEquals(InventoryStatus.SOURCE, selection.status());
        assertEquals(2, selection.slot());
    }

    @Test
    void uniquelyMovedReceiptIsSelectedWhereItCurrentlyExists() {
        var selection =
                HytaleCaptureReleaseInventoryGateway.selectMatches(
                        List.of(
                                InventoryStatus.RECEIPT,
                                InventoryStatus.CONFLICT,
                                InventoryStatus.CONFLICT
                        )
                );

        assertEquals(InventoryStatus.RECEIPT, selection.status());
        assertEquals(0, selection.slot());
    }

    @Test
    void duplicateExactSourcesAreAmbiguous() {
        var selection =
                HytaleCaptureReleaseInventoryGateway.selectMatches(
                        List.of(
                                InventoryStatus.SOURCE,
                                InventoryStatus.CONFLICT,
                                InventoryStatus.SOURCE
                        )
                );

        assertEquals(InventoryStatus.CONFLICT, selection.status());
        assertEquals(-1, selection.slot());
    }

    @Test
    void simultaneousSourceAndReceiptAreAmbiguous() {
        var selection =
                HytaleCaptureReleaseInventoryGateway.selectMatches(
                        List.of(
                                InventoryStatus.SOURCE,
                                InventoryStatus.RECEIPT
                        )
                );

        assertEquals(InventoryStatus.CONFLICT, selection.status());
        assertEquals(-1, selection.slot());
    }

    @Test
    void noExactArtifactIsAmbiguous() {
        var selection =
                HytaleCaptureReleaseInventoryGateway.selectMatches(
                        List.of(
                                InventoryStatus.CONFLICT,
                                InventoryStatus.CONFLICT
                        )
                );

        assertEquals(InventoryStatus.CONFLICT, selection.status());
        assertEquals(-1, selection.slot());
    }
}
