package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NeedsResourceMovementDiagnosticsTest {

    @Test
    void formatsMovementTransitionWithStableFields() {
        assertEquals(
                "Needs seek movement: npc=npc-1 role=Tamed_Wolf type=Water stage=nav_defer detail=path_deferred target=[1.00,2.00,3.00] current=<none> progress=n/a",
                NeedsResourceMovementDiagnostics.formatMessage(
                        "npc-1",
                        "Tamed_Wolf",
                        "Water",
                        "nav_defer",
                        "path_deferred",
                        "[1.00,2.00,3.00]"
                )
        );
    }
}
