package com.alechilles.alecstamework.companion.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionChangeTrackerTest {
    private static final long DISCRETE = 2_000L;
    private static final long DRIFT = 60_000L;

    private static CompanionChangeTracker tracker() {
        return new CompanionChangeTracker(DISCRETE, DRIFT, 0L);
    }

    @Test
    void firstSightMarksOnceSoAnEarlierUnsavedChangeIsSaved() {
        CompanionChangeTracker t = tracker();
        assertTrue(t.observe(1_000L, 11, 22));
        assertFalse(t.observe(3_100L, 11, 22));
    }

    @Test
    void aDiscreteChangeMarksAtTheNextDiscreteCheck() {
        CompanionChangeTracker t = tracker();
        t.observe(0L, 11, 22);
        assertFalse(t.observe(1_000L, 12, 22), "not due yet");
        assertTrue(t.observe(2_000L, 12, 22));
        assertFalse(t.observe(4_000L, 12, 22));
    }

    @Test
    void aDriftOnlyChangeWaitsForTheDriftCheck() {
        CompanionChangeTracker t = tracker();
        t.observe(0L, 11, 22);
        assertFalse(t.observe(2_000L, 11, 23));
        assertFalse(t.observe(30_000L, 11, 24));
        assertTrue(t.observe(60_000L, 11, 24));
        assertFalse(t.observe(62_000L, 11, 24));
    }

    @Test
    void aDiscreteSaveAlsoCoversPendingDrift() {
        CompanionChangeTracker t = tracker();
        t.observe(0L, 11, 22);
        assertTrue(t.observe(2_000L, 12, 25));
        assertFalse(t.observe(60_000L, 12, 25), "drift was saved with the discrete change");
    }

    @Test
    void staggerDelaysOnlyTheFirstChecks() {
        CompanionChangeTracker t = new CompanionChangeTracker(DISCRETE, DRIFT, 1_500L);
        t.observe(0L, 11, 22);
        assertFalse(t.observe(2_000L, 12, 22));
        assertTrue(t.observe(3_500L, 12, 22));
    }

    @Test
    void isDueOnlyWhenACheckIsDue() {
        CompanionChangeTracker t = tracker();
        assertTrue(t.isDue(0L));
        t.observe(0L, 1, 1);
        assertFalse(t.isDue(1_999L));
        assertTrue(t.isDue(2_000L));
    }
}
