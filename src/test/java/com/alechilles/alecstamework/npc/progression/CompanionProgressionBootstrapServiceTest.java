package com.alechilles.alecstamework.npc.progression;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for progression bootstrap timestamp classification. */
class CompanionProgressionBootstrapServiceTest {
    @Test
    void migratedActiveDeadlineAlwaysReplacesStaleWindowMetadata() {
        assertTrue(CompanionProgressionBootstrapService.shouldReconstructCooldownTiming(
                true,
                true,
                1_700_000_000_000L,
                Long.MAX_VALUE
        ));
        assertFalse(CompanionProgressionBootstrapService.shouldReconstructCooldownTiming(
                true,
                false,
                1_700_000_000_000L,
                Long.MAX_VALUE
        ));
    }
}
