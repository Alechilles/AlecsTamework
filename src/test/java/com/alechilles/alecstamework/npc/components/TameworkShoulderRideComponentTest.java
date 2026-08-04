package com.alechilles.alecstamework.npc.components;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies shoulder-state snapshot compatibility across prototype saves. */
class TameworkShoulderRideComponentTest {
    @Test
    void legacyMarkerDoesNotClaimToHaveCapturedPhysicalState() {
        TameworkShoulderRideComponent legacy =
                new TameworkShoulderRideComponent(UUID.randomUUID());

        assertFalse(legacy.hasCapturedState());
        assertFalse(legacy.clone().hasCapturedState());
    }

    @Test
    void currentMarkerCapturesPhysicalStateForRestoration() {
        TameworkShoulderRideComponent current =
                new TameworkShoulderRideComponent(UUID.randomUUID(),
                        true, false, true, false);

        assertTrue(current.hasCapturedState());
        assertTrue(current.clone().hasCapturedState());
    }
}
