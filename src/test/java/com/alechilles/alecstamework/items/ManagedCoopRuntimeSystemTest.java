package com.alechilles.alecstamework.items;

import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies UTC roam-hour extraction without rejecting negative Hytale world epochs. */
class ManagedCoopRuntimeSystemTest {

    @Test
    void resolvesNegativeWorldEpochAndFallbackWithoutPositiveTimestampGate() {
        Instant negativeGameTime = Instant.parse("0001-01-01T22:15:00Z");

        assertEquals(22, ManagedCoopRuntimeSystem.resolveGameHour(negativeGameTime, 0L));
        assertEquals(0, ManagedCoopRuntimeSystem.resolveGameHour(null, 0L));
    }
}
