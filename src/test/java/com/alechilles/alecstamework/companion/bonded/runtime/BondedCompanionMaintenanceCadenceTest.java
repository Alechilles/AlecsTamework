package com.alechilles.alecstamework.companion.bonded.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BondedCompanionMaintenanceCadenceTest {
    private static final long SECOND = 1_000_000_000L;

    /** Regression: an idle world must not repeat durable recovery every five seconds. */
    @Test
    void idleWorldBacksOffWhileActiveWorldKeepsFastRecovery() {
        BondedCompanionMaintenanceCadence cadence =
                new BondedCompanionMaintenanceCadence();

        var idleClaim = cadence.claimWorld("idle", 0L);
        assertNotNull(idleClaim);
        cadence.completeWorld(idleClaim, 0L, false);
        assertNull(cadence.claimWorld("idle", 5L * SECOND));
        assertNotNull(cadence.claimWorld("idle", 30L * SECOND));

        var activeClaim = cadence.claimWorld("active", 0L);
        assertNotNull(activeClaim);
        cadence.completeWorld(activeClaim, 0L, true);
        assertNotNull(cadence.claimWorld("active", 5L * SECOND));
    }

    /** Regression: global retention remains process-wide and independent of world backoff. */
    @Test
    void globalMaintenanceRemainsOnFiveSecondProcessCadence() {
        BondedCompanionMaintenanceCadence cadence =
                new BondedCompanionMaintenanceCadence();

        assertNotNull(cadence.claimWorld("idle", 0L));
        assertTrue(cadence.claimGlobal(0L));
        assertFalse(cadence.claimGlobal(1L * SECOND));
        assertFalse(cadence.claimGlobal(4L * SECOND));
        assertTrue(cadence.claimGlobal(5L * SECOND));
    }
}
