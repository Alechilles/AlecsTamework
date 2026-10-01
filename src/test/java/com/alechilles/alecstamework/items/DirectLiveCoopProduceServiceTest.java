package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.alechilles.alecstamework.companion.coop.TameworkCoopSlotsComponent;
import java.util.UUID;
import org.bson.BsonDocument;
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
    void unownedResidentWithoutWatermarkStartsOneIntervalBackSoAStayYieldsOneCycle() {
        var unowned = TameworkCoopSlotsComponent.Slot.unowned(0,
                new BsonDocument());
        long now = 5L * INTERVAL;
        long start = DirectLiveCoopProduceService.startingWatermark(unowned, now, INTERVAL);

        assertEquals(1, DirectLiveCoopProduceService.cyclesDue(now, start, INTERVAL));
        // A companion without a saved watermark starts from now instead.
        assertEquals(0L, DirectLiveCoopProduceService.startingWatermark(
                TameworkCoopSlotsComponent.Slot.companion(
                        0, UUID.randomUUID(), 1L), now, INTERVAL));
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
