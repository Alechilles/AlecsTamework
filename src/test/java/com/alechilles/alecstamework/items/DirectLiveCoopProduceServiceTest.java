package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Guards the vanilla coop production rule on the slot watermark (world game time). */
class DirectLiveCoopProduceServiceTest {
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24L * HOUR;
    private static final int ROAM_START_HOUR = 6;
    /** A morning sweep at 06:00 of some day, and the same on a negative game clock. */
    private static final long MORNING = 40L * DAY + ROAM_START_HOUR * HOUR;
    private static final long NEGATIVE_MORNING = -40L * DAY + ROAM_START_HOUR * HOUR;

    @Test
    void unitsFollowWholeHoursRoundedUpToTheInterval() {
        assertEquals(0, units(HOUR - 1L, 24L));
        assertEquals(1, units(HOUR, 24L));
        assertEquals(1, units(24L * HOUR, 24L));
        assertEquals(2, units(25L * HOUR, 24L));
        assertEquals(2, units(48L * HOUR, 24L));
        assertEquals(3, units(49L * HOUR, 24L));
        // A longer configured interval stretches the same rule.
        assertEquals(1, units(48L * HOUR, 48L));
        assertEquals(2, units(49L * HOUR, 48L));
    }

    @Test
    void catchUpIsCappedPerSweep() {
        assertEquals(32, units(1_000L * DAY, 24L));
    }

    @Test
    void watermarkMovesToNowEvenWhenNothingIsDue() {
        long enteredHalfAnHourAgo = MORNING - HOUR / 2L;

        assertEquals(MORNING, advance(enteredHalfAnHourAgo, MORNING, new ArrayList<>()));
    }

    @Test
    void newResidentProducesOnceOnItsFirstMorningAndNotAgainInThatRoamWindow() {
        List<Integer> produced = new ArrayList<>();
        long enteredAtFive = MORNING - HOUR;

        long afterMorning = advance(enteredAtFive, MORNING, produced);
        // Its release keeps being refused, so later sweeps of the same roam hours see it again.
        long afterNextSweep = advance(afterMorning, MORNING + 1_000L, produced);
        long afterNoon = advance(afterNextSweep, MORNING + 6L * HOUR, produced);

        assertEquals(List.of(1), produced);
        assertEquals(MORNING, afterNoon);

        // The next morning opens a new roam window and it produces again.
        assertEquals(MORNING + DAY, advance(afterNoon, MORNING + DAY, produced));
        assertEquals(List.of(1, 1), produced);
    }

    @Test
    void negativeGameTimesFollowTheSameRule() {
        List<Integer> produced = new ArrayList<>();

        long afterMorning = advance(NEGATIVE_MORNING - 25L * HOUR, NEGATIVE_MORNING, produced);
        long afterNextSweep = advance(afterMorning, NEGATIVE_MORNING + 1_000L, produced);

        assertEquals(List.of(2), produced);
        assertEquals(NEGATIVE_MORNING, afterNextSweep);
    }

    @Test
    void residentWithoutWatermarkStartsAtNowWithoutProducing() {
        assertEquals(MORNING, DirectLiveCoopProduceService.advance(0L, MORNING, MORNING, 24L,
                units -> fail("an entry without an intake time has nothing due")));
    }

    @Test
    void watermarkAheadOfTheClockResetsToNowWithoutProducing() {
        assertEquals(MORNING, DirectLiveCoopProduceService.advance(MORNING + 3L * DAY, MORNING, MORNING, 24L,
                units -> fail("a clock set back owes nothing")));
    }

    @Test
    void alwaysRoamingCoopGetsOneWindowPerDay() {
        List<Integer> produced = new ArrayList<>();
        long midday = MORNING + 6L * HOUR;

        long first = advance(MORNING - 2L * HOUR, midday, produced);
        long sameDay = advance(first, midday + 10L * HOUR, produced);
        long nextDay = advance(sameDay, midday + DAY, produced);

        assertEquals(List.of(1, 1), produced);
        assertEquals(midday + DAY, nextDay);
    }

    private static int units(long elapsedMs, long intervalHours) {
        return DirectLiveCoopProduceService.unitsDue(MORNING - elapsedMs, MORNING, intervalHours);
    }

    private static long advance(long watermarkMs, long nowMs, List<Integer> produced) {
        return DirectLiveCoopProduceService.advance(watermarkMs, nowMs,
                DirectLiveCoopProduceService.roamWindowStartMs(nowMs, ROAM_START_HOUR), 24L, produced::add);
    }
}
