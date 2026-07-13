package com.alechilles.alecstamework.items;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression for concurrent coop releases revoking each other's shared composite-index epoch. */
class ManagedCoopLifecycleMutationGateTest {
    @Test
    void releaseReadinessDoesNotCloseTheSharedCaptureSerializationLease() {
        ManagedCoopLifecycleMutationGate gate =
                new ManagedCoopLifecycleMutationGate(() -> false);

        assertFalse(gate.releaseReady());
        assertNotNull(gate.tryAcquire("runtime-capture:profile"));
    }

    @Test
    void onlyExactLeaseCanReopenLifecycleGate() {
        ManagedCoopLifecycleMutationGate gate = new ManagedCoopLifecycleMutationGate();
        ManagedCoopLifecycleMutationGate.Lease first = gate.tryAcquire("release:first");

        assertNotNull(first);
        assertEquals("release:first", first.owner());
        assertTrue(gate.occupied());
        assertNull(gate.tryAcquire("release:second"));

        gate.release(first);

        assertFalse(gate.occupied());
        assertNotNull(gate.tryAcquire("release:second"));
    }
}
