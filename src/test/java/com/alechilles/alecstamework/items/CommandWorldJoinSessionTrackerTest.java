package com.alechilles.alecstamework.items;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandWorldJoinSessionTrackerTest {
    @Test
    void firstWorldAfterConnectIsNotAWorldChange() {
        CommandWorldJoinSessionTracker tracker = new CommandWorldJoinSessionTracker();
        UUID player = UUID.randomUUID();

        tracker.onConnected(player);

        assertFalse(tracker.isWorldChange(player));
        assertTrue(tracker.isWorldChange(player));
    }

    @Test
    void reconnectCreatesANewInitialWorldBoundary() {
        CommandWorldJoinSessionTracker tracker = new CommandWorldJoinSessionTracker();
        UUID player = UUID.randomUUID();
        tracker.onConnected(player);
        tracker.isWorldChange(player);
        tracker.onDisconnected(player);

        tracker.onConnected(player);

        assertFalse(tracker.isWorldChange(player));
    }

    @Test
    void worldAddWithoutObservedConnectRemainsAWorldChange() {
        CommandWorldJoinSessionTracker tracker = new CommandWorldJoinSessionTracker();

        assertTrue(tracker.isWorldChange(UUID.randomUUID()));
    }
}
