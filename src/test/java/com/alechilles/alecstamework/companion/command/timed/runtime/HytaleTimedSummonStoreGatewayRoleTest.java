package com.alechilles.alecstamework.companion.command.timed.runtime;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Regression coverage for frozen timed-snapshot role fencing. */
class HytaleTimedSummonStoreGatewayRoleTest {
    @Test
    void roleSwapAtSnapshotFreezeStillIdentifiesTheExactLiveRole() {
        UUID alias = UUID.fromString("00000000-0000-0000-0000-000000000001");

        assertNull(TimedSummonStoreSourceEvidence.mismatch(
                alias, alias, alias, alias,
                "tamed_nordicdrake_flying",
                "Tamed_NordicDrake_Flying"
        ));
    }

    @Test
    void laterRoleSwapAfterSnapshotFreezeBlocksRetirement() {
        UUID alias = UUID.fromString(
                "00000000-0000-0000-0000-000000000001"
        );

        assertEquals("role", TimedSummonStoreSourceEvidence.mismatch(
                alias, alias, alias, alias,
                "Tamed_NordicDrake",
                "Tamed_NordicDrake_Flying"
        ));
    }
}
