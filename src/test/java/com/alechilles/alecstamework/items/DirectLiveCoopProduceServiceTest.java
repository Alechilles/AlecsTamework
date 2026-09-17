package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Guards against resetting output progress whenever a resident leaves and returns to its coop. */
class DirectLiveCoopProduceServiceTest {
    @Test
    void activeAnimalTimeKeepsDailyRoamingIntervalsEligible() {
        long interval = 24L * 60L * 60L * 1_000L;
        long watermark = 10L * interval;

        assertEquals(1, DirectLiveCoopProduceService.cyclesDue(11L * interval, watermark, interval));
    }
}
