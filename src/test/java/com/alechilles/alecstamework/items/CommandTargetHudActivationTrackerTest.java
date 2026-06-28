package com.alechilles.alecstamework.items;

import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CommandTargetHudActivationTrackerTest {
    private static final UUID PLAYER_UUID = UUID.fromString("c5b0ce9e-75c0-41b0-a66d-5de54ebe5466");

    @Test
    void unknownPlayersAreInspectedOnceToSeedTheCache() {
        CommandTargetHudActivationTracker tracker = new CommandTargetHudActivationTracker();

        Assertions.assertTrue(tracker.shouldInspectPlayer(PLAYER_UUID, 1_000L));
    }

    @Test
    void inactivePlayersAreSkippedUntilAnInventoryEventMarksThemDirty() {
        CommandTargetHudActivationTracker tracker = new CommandTargetHudActivationTracker();
        tracker.recordResolvedHand(PLAYER_UUID, null, false, 1_000L);

        Assertions.assertFalse(tracker.shouldInspectPlayer(PLAYER_UUID, 1_100L));

        tracker.markDirty(PLAYER_UUID);

        Assertions.assertTrue(tracker.shouldInspectPlayer(PLAYER_UUID, 1_101L));
    }

    @Test
    void inactivePlayersStillGetLowFrequencySanityChecks() {
        Assertions.assertFalse(CommandTargetHudActivationTracker.shouldInspectForTests(
                false,
                false,
                1_000L,
                1_500L,
                1_000L
        ));
        Assertions.assertTrue(CommandTargetHudActivationTracker.shouldInspectForTests(
                false,
                false,
                1_000L,
                2_000L,
                1_000L
        ));
    }

    @Test
    void commandItemPlayersStayEligibleForTargetScanning() {
        CommandTargetHudActivationTracker tracker = new CommandTargetHudActivationTracker();
        tracker.recordResolvedHand(PLAYER_UUID, "Tamework:CommandFlute", true, 1_000L);

        Assertions.assertTrue(tracker.shouldInspectPlayer(PLAYER_UUID, 1_050L));
    }

    @Test
    void dirtyPlayersAreRegisteredAsInspectionCandidates() {
        CommandTargetHudActivationTracker tracker = new CommandTargetHudActivationTracker();

        tracker.markDirty(PLAYER_UUID);

        Assertions.assertTrue(tracker.candidatePlayerUuids().contains(PLAYER_UUID));
    }

    @Test
    void commandItemPlayersRemainInspectionCandidatesAfterResolution() {
        CommandTargetHudActivationTracker tracker = new CommandTargetHudActivationTracker();

        tracker.recordResolvedHand(PLAYER_UUID, "Tamework:CommandFlute", true, 1_000L);

        Assertions.assertTrue(tracker.candidatePlayerUuids().contains(PLAYER_UUID));
    }

    @Test
    void inactivePlayersAreRemovedFromInspectionCandidatesAfterResolution() {
        CommandTargetHudActivationTracker tracker = new CommandTargetHudActivationTracker();
        tracker.markDirty(PLAYER_UUID);

        tracker.recordResolvedHand(PLAYER_UUID, null, false, 1_000L);

        Assertions.assertFalse(tracker.candidatePlayerUuids().contains(PLAYER_UUID));
    }

    @Test
    void removeDropsHandStateAndInspectionCandidate() {
        CommandTargetHudActivationTracker tracker = new CommandTargetHudActivationTracker();
        tracker.recordResolvedHand(PLAYER_UUID, "Tamework:CommandFlute", true, 1_000L);

        tracker.remove(PLAYER_UUID);

        Assertions.assertFalse(tracker.candidatePlayerUuids().contains(PLAYER_UUID));
        Assertions.assertTrue(tracker.shouldInspectPlayer(PLAYER_UUID, 1_050L));
    }
}
