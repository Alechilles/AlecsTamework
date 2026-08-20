package com.alechilles.alecstamework.items;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for persistent active-NPC highlight delivery. */
class CommandActiveNpcHighlightDisplayTrackerTest {
    private static final UUID PLAYER_UUID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void unchangedRenderedTargetNeedsOnlyOneEmission() {
        CommandActiveNpcHighlightDisplayTracker<String> tracker =
                new CommandActiveNpcHighlightDisplayTracker<>();
        Object store = new Object();

        assertFalse(tracker.reconcile(store, PLAYER_UUID, "flute-a", List.of("cow-a")));
        assertTrue(tracker.needsEmission(store, PLAYER_UUID, "cow-a", 42));

        tracker.recordEmission(store, PLAYER_UUID, "cow-a", 42);

        assertFalse(tracker.reconcile(store, PLAYER_UUID, "flute-a", List.of("cow-a")));
        assertFalse(tracker.needsEmission(store, PLAYER_UUID, "cow-a", 42));
    }

    @Test
    void changedRosterRequestsOneResetAndAllowsNewEmission() {
        CommandActiveNpcHighlightDisplayTracker<String> tracker =
                new CommandActiveNpcHighlightDisplayTracker<>();
        Object store = new Object();

        tracker.reconcile(store, PLAYER_UUID, "flute-a", List.of("cow-a"));
        tracker.recordEmission(store, PLAYER_UUID, "cow-a", 42);

        assertTrue(tracker.reconcile(store, PLAYER_UUID, "flute-a", List.of("cow-b")));
        assertTrue(tracker.needsEmission(store, PLAYER_UUID, "cow-b", 43));
        assertFalse(tracker.reconcile(store, PLAYER_UUID, "flute-a", List.of("cow-b")));
    }

    @Test
    void removingPlayerReportsWhetherVisibleParticlesNeedCancellation() {
        CommandActiveNpcHighlightDisplayTracker<String> tracker =
                new CommandActiveNpcHighlightDisplayTracker<>();
        Object store = new Object();

        tracker.reconcile(store, PLAYER_UUID, "flute-a", List.of("cow-a"));
        assertFalse(tracker.remove(store, PLAYER_UUID));

        tracker.reconcile(store, PLAYER_UUID, "flute-a", List.of("cow-a"));
        tracker.recordEmission(store, PLAYER_UUID, "cow-a", 42);

        assertTrue(tracker.remove(store, PLAYER_UUID));
        assertFalse(tracker.remove(store, PLAYER_UUID));
    }
}
