package com.alechilles.alecstamework.companion.command.timed.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for exact-role proof with tester-era case damage. */
class HytaleTimedSummonStoreGatewayRoleTest {
    @Test
    void caseOnlySnapshotDamageStillIdentifiesTheExactLiveRole() {
        // Regression: New World dismissal froze tamed_nordicdrake while the
        // exact live Hytale role remained Tamed_NordicDrake.
        assertTrue(HytaleTimedSummonStoreGateway.sameRole(
                "tamed_nordicdrake", "Tamed_NordicDrake"
        ));
    }

    @Test
    void differentRoleNeverPassesTheCompatibilityProof() {
        assertFalse(HytaleTimedSummonStoreGateway.sameRole(
                "tamed_nordicdrake", "Tamed_Chicken"
        ));
    }
}
