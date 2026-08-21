package com.alechilles.alecstamework.items;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for renewable active-NPC highlight delivery. */
class CommandActiveNpcHighlightDisplayTrackerTest {
    private static final long RENEWAL_INTERVAL_MS = 2_400L;
    private static final UUID PLAYER_UUID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void unchangedRenderedTargetDoesNotRenewEarly() {
        CommandActiveNpcHighlightDisplayTracker<String> tracker =
                new CommandActiveNpcHighlightDisplayTracker<>(RENEWAL_INTERVAL_MS);
        Object store = new Object();

        tracker.reconcile(store, PLAYER_UUID, "flute-a", List.of("cow-a"));
        assertTrue(tracker.needsEmission(store, PLAYER_UUID, "cow-a", 42, 1_000L));

        tracker.recordEmission(store, PLAYER_UUID, "cow-a", 42, 1_000L);

        tracker.reconcile(store, PLAYER_UUID, "flute-a", List.of("cow-a"));
        assertFalse(tracker.needsEmission(store, PLAYER_UUID, "cow-a", 42, 1_100L));
    }

    @Test
    void stableTargetNeedsRenewalAfterTheOverlapInterval() {
        CommandActiveNpcHighlightDisplayTracker<String> tracker =
                new CommandActiveNpcHighlightDisplayTracker<>(RENEWAL_INTERVAL_MS);
        Object store = new Object();

        tracker.reconcile(store, PLAYER_UUID, "flute-a", List.of("cow-a"));
        tracker.recordEmission(store, PLAYER_UUID, "cow-a", 42, 1_000L);

        assertFalse(tracker.needsEmission(store, PLAYER_UUID, "cow-a", 42, 3_399L));
        assertTrue(tracker.needsEmission(store, PLAYER_UUID, "cow-a", 42, 3_400L));
    }

    @Test
    void changedRosterResetsRenewalStateAndAllowsNewEmission() {
        CommandActiveNpcHighlightDisplayTracker<String> tracker =
                new CommandActiveNpcHighlightDisplayTracker<>(RENEWAL_INTERVAL_MS);
        Object store = new Object();

        tracker.reconcile(store, PLAYER_UUID, "flute-a", List.of("cow-a"));
        tracker.recordEmission(store, PLAYER_UUID, "cow-a", 42, 1_000L);

        tracker.reconcile(store, PLAYER_UUID, "flute-a", List.of("cow-b"));
        assertTrue(tracker.needsEmission(store, PLAYER_UUID, "cow-b", 43, 1_100L));
    }

    @Test
    void removingPlayerDiscardsRenewalState() {
        CommandActiveNpcHighlightDisplayTracker<String> tracker =
                new CommandActiveNpcHighlightDisplayTracker<>(RENEWAL_INTERVAL_MS);
        Object store = new Object();

        tracker.reconcile(store, PLAYER_UUID, "flute-a", List.of("cow-a"));
        tracker.recordEmission(store, PLAYER_UUID, "cow-a", 42, 1_000L);

        tracker.remove(store, PLAYER_UUID);

        assertFalse(tracker.needsEmission(store, PLAYER_UUID, "cow-a", 42, 3_000L));
    }
}
