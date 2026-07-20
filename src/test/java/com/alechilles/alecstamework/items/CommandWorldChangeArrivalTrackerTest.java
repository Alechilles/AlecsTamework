package com.alechilles.alecstamework.items;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for exact destination-arrival world-change intents. */
class CommandWorldChangeArrivalTrackerTest {
    @Test
    void intentIsConsumedOnlyByTheRecordedDestination() {
        CommandWorldChangeArrivalTracker tracker = new CommandWorldChangeArrivalTracker();
        UUID player = UUID.randomUUID();
        tracker.mark(player, "instance-destination");

        assertFalse(tracker.consume(player, "default"));
        assertTrue(tracker.consume(player, "instance-destination"));
        assertFalse(tracker.consume(player, "instance-destination"));
    }

    @Test
    void disconnectClearsPendingArrival() {
        CommandWorldChangeArrivalTracker tracker = new CommandWorldChangeArrivalTracker();
        UUID player = UUID.randomUUID();
        tracker.mark(player, "default");

        tracker.clear(player);

        assertFalse(tracker.consume(player, "default"));
    }
}
