package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

/** Guards the production watermark arithmetic on coop slot entries. */
class DirectLiveCoopProduceServiceTest {
    private static final long INTERVAL = 24L * 60L * 60L * 1_000L;

    @Test
    void activeAnimalTimeKeepsDailyRoamingIntervalsEligible() {
        long watermark = 10L * INTERVAL;

        assertEquals(1, DirectLiveCoopProduceService.cyclesDue(11L * INTERVAL, watermark, INTERVAL));
    }

    @Test
    void residentWithoutWatermarkStartsFromNowWithoutProducing() {
        long next = DirectLiveCoopProduceService.advance(0L, 5L * INTERVAL, INTERVAL, cycles -> {
            fail("no free first interval");
            return 0;
        });

        assertEquals(5L * INTERVAL, next);
    }

    @Test
    void watermarkMovesOnlyByCompletedCycles() {
        long watermark = 10L * INTERVAL;

        // Three cycles are due; a full container lets two complete.
        long next = DirectLiveCoopProduceService.advance(watermark, 13L * INTERVAL + 5L, INTERVAL, cycles -> {
            assertEquals(3, cycles);
            return 2;
        });

        assertEquals(12L * INTERVAL, next);
        assertEquals(watermark, DirectLiveCoopProduceService.advance(watermark, 13L * INTERVAL, INTERVAL, cycles -> 0));
    }
}
