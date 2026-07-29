package com.alechilles.alecstamework.companion.command.timed.runtime;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression coverage for actionable STORE proof diagnostics. */
class TimedSummonStoreSourceEvidenceTest {
    @Test
    void reportsTheNpcIdentityMismatchBeforeTheCompatibleRole() {
        UUID alias = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID differentNpc = UUID.fromString("00000000-0000-0000-0000-000000000002");

        assertEquals(
                "npc-uuid",
                TimedSummonStoreSourceEvidence.mismatch(
                        alias,
                        alias,
                        differentNpc,
                        alias,
                        "Tamed_NordicDrake",
                        "Tamed_NordicDrake"
                )
        );
    }
}
