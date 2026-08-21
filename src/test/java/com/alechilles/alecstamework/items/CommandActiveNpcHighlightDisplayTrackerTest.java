package com.alechilles.alecstamework.items;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for one-shot active-NPC highlight delivery. */
class CommandActiveNpcHighlightDisplayTrackerTest {
    private static final UUID PLAYER_UUID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NPC_UUID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID PROXY_UUID =
            UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void unchangedRenderedTargetDoesNotRenewEarly() {
        CommandActiveNpcHighlightDisplayTracker<String> tracker =
                new CommandActiveNpcHighlightDisplayTracker<>();
        Object store = new Object();

        tracker.reconcile(store, PLAYER_UUID, "flute-a", List.of("cow-a"));
        assertTrue(tracker.beginProxyCreation(store, PLAYER_UUID, "cow-a", NPC_UUID));
        assertTrue(tracker.recordProxy(
                store, PLAYER_UUID, "cow-a", NPC_UUID, PROXY_UUID
        ));
        assertTrue(tracker.needsEmission(store, PLAYER_UUID, "cow-a", 42));

        tracker.recordEmission(store, PLAYER_UUID, "cow-a", 42);

        tracker.reconcile(store, PLAYER_UUID, "flute-a", List.of("cow-a"));
        assertFalse(tracker.needsEmission(store, PLAYER_UUID, "cow-a", 42));
    }

    @Test
    void stableTargetDoesNotEmitAgainWhileTheSameProxyExists() {
        CommandActiveNpcHighlightDisplayTracker<String> tracker =
                new CommandActiveNpcHighlightDisplayTracker<>();
        Object store = new Object();

        tracker.reconcile(store, PLAYER_UUID, "flute-a", List.of("cow-a"));
        tracker.beginProxyCreation(store, PLAYER_UUID, "cow-a", NPC_UUID);
        tracker.recordProxy(store, PLAYER_UUID, "cow-a", NPC_UUID, PROXY_UUID);
        tracker.recordEmission(store, PLAYER_UUID, "cow-a", 42);

        assertFalse(tracker.needsEmission(store, PLAYER_UUID, "cow-a", 42));
    }

    @Test
    void changedRosterReturnsTheStaleProxyForRemoval() {
        CommandActiveNpcHighlightDisplayTracker<String> tracker =
                new CommandActiveNpcHighlightDisplayTracker<>();
        Object store = new Object();

        tracker.reconcile(store, PLAYER_UUID, "flute-a", List.of("cow-a"));
        tracker.beginProxyCreation(store, PLAYER_UUID, "cow-a", NPC_UUID);
        tracker.recordProxy(store, PLAYER_UUID, "cow-a", NPC_UUID, PROXY_UUID);

        assertEquals(
                List.of(PROXY_UUID),
                tracker.reconcile(store, PLAYER_UUID, "flute-a", List.of("cow-b"))
        );
        assertTrue(tracker.beginProxyCreation(store, PLAYER_UUID, "cow-b", NPC_UUID));
    }

    @Test
    void removingPlayerDiscardsRenewalState() {
        CommandActiveNpcHighlightDisplayTracker<String> tracker =
                new CommandActiveNpcHighlightDisplayTracker<>();
        Object store = new Object();

        tracker.reconcile(store, PLAYER_UUID, "flute-a", List.of("cow-a"));
        tracker.beginProxyCreation(store, PLAYER_UUID, "cow-a", NPC_UUID);
        tracker.recordProxy(store, PLAYER_UUID, "cow-a", NPC_UUID, PROXY_UUID);
        tracker.recordEmission(store, PLAYER_UUID, "cow-a", 42);

        assertEquals(List.of(PROXY_UUID), tracker.remove(store, PLAYER_UUID));

        assertFalse(tracker.needsEmission(store, PLAYER_UUID, "cow-a", 42));
    }
}
