package com.alechilles.alecstamework.items;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CommandTargetHudActivationTrackerTest {
    private static final UUID PLAYER_UUID = UUID.fromString("c5b0ce9e-75c0-41b0-a66d-5de54ebe5466");
    private static final List<UUID> MANY_PLAYER_UUIDS = List.of(
            UUID.fromString("f9c3d27a-2564-4ec4-a986-8a9ea345e871"),
            UUID.fromString("7a74312f-0504-4bdb-9067-1053fa303d11"),
            UUID.fromString("2058ca1a-f62d-4f0d-a665-7c2c934fcb7f"),
            UUID.fromString("378ecfe3-b639-429c-a06b-e1937e5e7c38"),
            UUID.fromString("41e2be14-26c8-42a8-8a0e-854e2cd9e8d0"),
            UUID.fromString("c9e9e36b-5d3e-48bb-b165-02223cbf8b2f"),
            UUID.fromString("58ef1868-afae-4800-9d67-73645c6c715b"),
            UUID.fromString("e108d13e-eaf3-485e-a512-6d3c36125cc9"),
            UUID.fromString("f4d9251d-a34f-491a-a614-50b873e42b89"),
            UUID.fromString("90174b04-d3ac-4dd9-bb12-e4ecbc2c15c9"),
            UUID.fromString("e83c9d72-225d-497d-b611-43836632f799"),
            UUID.fromString("9bd74bb6-7805-414a-b060-90dbdf205c13"),
            UUID.fromString("e3642b08-c69d-4287-8d0d-6f174341a8e7"),
            UUID.fromString("34092036-2487-4d85-a973-6a8ba847bd17"),
            UUID.fromString("6f827dd7-493f-4837-90fe-a394d4d54d2f"),
            UUID.fromString("9690bed1-3300-4973-bb32-93c2b7c7f736")
    );

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

    @Test
    void boundedCandidateBatchPrioritizesDirtyPlayersAndRotatesRegularPlayers() {
        CommandTargetHudActivationTracker tracker = new CommandTargetHudActivationTracker();
        UUID regularA = MANY_PLAYER_UUIDS.get(0);
        UUID regularB = MANY_PLAYER_UUIDS.get(1);
        UUID dirty = MANY_PLAYER_UUIDS.get(2);
        tracker.recordResolvedHand(regularA, "Tamework:CommandFlute", true, 1_000L);
        tracker.recordResolvedHand(regularB, "Tamework:CommandFlute", true, 1_000L);
        tracker.markDirty(dirty);

        CommandTargetHudActivationTracker.CandidateBatch first =
                tracker.selectCandidateBatch(2, null, null);
        tracker.recordResolvedHand(dirty, "Tamework:CommandFlute", true, 1_001L);
        CommandTargetHudActivationTracker.CandidateBatch second =
                tracker.selectCandidateBatch(
                        2,
                        first.nextDirtyCursor(),
                        first.nextRegularCursor()
                );

        Assertions.assertEquals(List.of(dirty, regularA), first.playerUuids());
        Assertions.assertEquals(List.of(regularB, dirty), second.playerUuids());
    }

    @Test
    void dirtyCandidateBatchRotatesBeforeTheNextFallbackSweep() {
        CommandTargetHudActivationTracker tracker = new CommandTargetHudActivationTracker();
        for (UUID playerUuid : MANY_PLAYER_UUIDS.subList(0, 5)) {
            tracker.markDirty(playerUuid);
        }

        CommandTargetHudActivationTracker.CandidateBatch first =
                tracker.selectCandidateBatch(2, null, null);
        CommandTargetHudActivationTracker.CandidateBatch second =
                tracker.selectCandidateBatch(2, first.nextDirtyCursor(), first.nextRegularCursor());

        Assertions.assertEquals(MANY_PLAYER_UUIDS.subList(0, 2), first.playerUuids());
        Assertions.assertEquals(MANY_PLAYER_UUIDS.subList(2, 4), second.playerUuids());
    }

    @Test
    void fallbackSweepDoesNotStarveTheLastDirtyPlayer() {
        CommandTargetHudActivationTracker tracker = new CommandTargetHudActivationTracker();
        List<UUID> players = java.util.stream.LongStream.rangeClosed(1L, 61L)
                .mapToObj(value -> new UUID(0L, value))
                .toList();
        players.forEach(tracker::markDirty);
        Set<UUID> processed = new HashSet<>();
        UUID dirtyCursor = null;
        UUID regularCursor = null;

        for (int pass = 0; pass < 16; pass++) {
            if (pass == 15) {
                players.forEach(tracker::markDirty);
            }
            CommandTargetHudActivationTracker.CandidateBatch batch =
                    tracker.selectCandidateBatch(4, dirtyCursor, regularCursor);
            dirtyCursor = batch.nextDirtyCursor();
            regularCursor = batch.nextRegularCursor();
            for (UUID playerUuid : batch.playerUuids()) {
                processed.add(playerUuid);
                tracker.recordResolvedHand(playerUuid, "Tamework:CommandFlute", true, pass);
            }
        }

        Assertions.assertEquals(new HashSet<>(players), processed);
    }

    @Test
    void candidateSnapshotsTolerateConcurrentEventMutations() throws Exception {
        CommandTargetHudActivationTracker tracker = new CommandTargetHudActivationTracker();
        for (UUID playerUuid : MANY_PLAYER_UUIDS) {
            tracker.markDirty(playerUuid);
        }
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(3);
        executor.submit(() -> mutateCandidatesUntilStopped(tracker, start, running, failure, 0));
        executor.submit(() -> mutateCandidatesUntilStopped(tracker, start, running, failure, 1));
        executor.submit(() -> snapshotCandidatesUntilStopped(tracker, start, running, failure));

        start.countDown();
        Thread.sleep(500L);
        running.set(false);
        executor.shutdown();
        Assertions.assertTrue(executor.awaitTermination(5L, TimeUnit.SECONDS));
        Assertions.assertNull(failure.get(), "Candidate snapshots must not race with inventory event mutations.");
    }

    private static void mutateCandidatesUntilStopped(CommandTargetHudActivationTracker tracker,
                                                     CountDownLatch start,
                                                     AtomicBoolean running,
                                                     AtomicReference<Throwable> failure,
                                                     int offset) {
        try {
            start.await();
            int index = offset;
            while (running.get() && failure.get() == null) {
                UUID playerUuid = MANY_PLAYER_UUIDS.get(Math.floorMod(index, MANY_PLAYER_UUIDS.size()));
                tracker.markDirty(playerUuid);
                if ((index & 1) == 0) {
                    tracker.recordResolvedHand(playerUuid, null, false, index);
                } else {
                    tracker.recordResolvedHand(playerUuid, "Tamework:CommandFlute", true, index);
                }
                index++;
            }
        } catch (Throwable throwable) {
            failure.compareAndSet(null, throwable);
            running.set(false);
        }
    }

    private static void snapshotCandidatesUntilStopped(CommandTargetHudActivationTracker tracker,
                                                       CountDownLatch start,
                                                       AtomicBoolean running,
                                                       AtomicReference<Throwable> failure) {
        try {
            start.await();
            while (running.get() && failure.get() == null) {
                List<UUID> candidates = tracker.candidatePlayerUuids();
                CommandTargetHudActivationTracker.CandidateBatch batch =
                        tracker.selectCandidateBatch(4, null, null);
                Assertions.assertThrows(UnsupportedOperationException.class, () -> candidates.add(PLAYER_UUID));
                Assertions.assertThrows(
                        UnsupportedOperationException.class,
                        () -> batch.playerUuids().add(PLAYER_UUID)
                );
            }
        } catch (Throwable throwable) {
            failure.compareAndSet(null, throwable);
            running.set(false);
        }
    }
}
