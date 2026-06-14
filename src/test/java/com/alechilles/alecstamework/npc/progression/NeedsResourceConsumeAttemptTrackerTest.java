package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NeedsResourceConsumeAttemptTrackerTest {
    private static final long NOW_MS = 10_000L;

    @BeforeEach
    void setUp() {
        NeedsResourceConsumeAttemptTracker.clearForTests();
    }

    @AfterEach
    void tearDown() {
        NeedsResourceConsumeAttemptTracker.clearForTests();
    }

    @Test
    void successfulAttemptIsVisibleForShortPostConsumeWindow() {
        UUID npc = UUID.randomUUID();

        NeedsResourceConsumeAttemptTracker.record(npc, "Water", true, NOW_MS);

        assertTrue(NeedsResourceConsumeAttemptTracker.wasSuccessful(npc, "Water", NOW_MS + 500L));
    }

    @Test
    void failedAttemptDoesNotAllowRepeatConsumeLoop() {
        UUID npc = UUID.randomUUID();

        NeedsResourceConsumeAttemptTracker.record(npc, "FoodContainer", false, NOW_MS);

        assertFalse(NeedsResourceConsumeAttemptTracker.wasSuccessful(npc, "FoodContainer", NOW_MS + 500L));
    }

    @Test
    void staleSuccessfulAttemptIsIgnored() {
        UUID npc = UUID.randomUUID();

        NeedsResourceConsumeAttemptTracker.record(npc, "Water", true, NOW_MS);

        assertFalse(NeedsResourceConsumeAttemptTracker.wasSuccessful(npc, "Water", NOW_MS + 6_000L));
    }

    @Test
    void waterAndFoodAttemptsAreTrackedIndependently() {
        UUID npc = UUID.randomUUID();

        NeedsResourceConsumeAttemptTracker.record(npc, "Water", true, NOW_MS);
        NeedsResourceConsumeAttemptTracker.record(npc, "FoodContainer", false, NOW_MS);

        assertTrue(NeedsResourceConsumeAttemptTracker.wasSuccessful(npc, "Water", NOW_MS + 500L));
        assertFalse(NeedsResourceConsumeAttemptTracker.wasSuccessful(npc, "FoodContainer", NOW_MS + 500L));
    }
}
