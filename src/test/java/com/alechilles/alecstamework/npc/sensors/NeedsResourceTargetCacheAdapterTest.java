package com.alechilles.alecstamework.npc.sensors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.npc.progression.NeedsResourceCandidates;
import com.alechilles.alecstamework.npc.progression.NeedsResourceHotPathDiagnostics;
import com.alechilles.alecstamework.npc.progression.NeedsResourceSearchCoordinator;
import com.alechilles.alecstamework.npc.progression.NeedsResourceTargetStateStore;
import com.alechilles.alecstamework.performance.TameworkRuntimePressureService;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NeedsResourceTargetCacheAdapterTest {
    @AfterEach
    void clearReservations() {
        NeedsResourceTargetCacheAdapter.clearAllTargetsForTests();
        com.alechilles.alecstamework.npc.progression.PositionTargetReservationCache.clearForTests();
        com.alechilles.alecstamework.npc.progression.PositionTargetRejectCache.clearForTests();
        TameworkRuntimePressureService.getInstance().clearForTests();
        NeedsResourceHotPathDiagnostics.resetForTests();
    }

    @Test
    void validatedTargetLeaseRenewsWhileTheNpcStillUsesIt() {
        NeedsResourceTargetCacheAdapter adapter = new NeedsResourceTargetCacheAdapter();
        UUID activeNpc = new UUID(0L, 101L);
        UUID idleNpc = new UUID(0L, 102L);
        Vector3d activeTarget = new Vector3d(2.5, 64.5, 2.5);
        Vector3d idleTarget = new Vector3d(3.5, 64.5, 3.5);
        adapter.adoptTarget(activeNpc, "world-a", "water", activeTarget, 1.5, 8.0, 2, 1_000L);
        adapter.adoptTarget(idleNpc, "world-a", "water", idleTarget, 1.5, 8.0, 2, 1_000L);

        assertTrue(adapter.promoteTarget(activeNpc, "world-a", "water", activeTarget, 1_001L));
        assertTrue(adapter.promoteTarget(idleNpc, "world-a", "water", idleTarget, 1_001L));
        assertNotNull(adapter.resolveLocal(
                activeNpc, "world-a", "water", 0.0, 64.0, 0.0, 9_000L
        ));

        NeedsResourceTargetCacheAdapter.Result renewed = adapter.resolveLocal(
                activeNpc, "world-a", "water", 0.0, 64.0, 0.0, 12_000L
        );

        assertNotNull(renewed);
        assertFalse(renewed.preflightRequired());
        assertNull(adapter.resolveLocal(
                idleNpc, "world-a", "water", 0.0, 64.0, 0.0, 12_000L
        ));
    }

    @Test
    void fastConsumeTargetExpiresWithItsFastModeMarker() {
        try (TestEntityComponentStore store = newStore()) {
            NeedsResourceSearchCoordinator coordinator = NeedsResourceSearchCoordinator.getInstance();
            coordinator.clear(store);
            NeedsResourceSearchCoordinator.Request request = request("world-a");
            UUID npc = new UUID(0L, 104L);
            warm(coordinator, store, npc, request, oneCandidateSnapshot(), 1_000L);
            NeedsResourceTargetCacheAdapter adapter = new NeedsResourceTargetCacheAdapter(coordinator);

            NeedsResourceTargetCacheAdapter.Result fast = adapter.resolve(
                    store, npc, request, "world-a", 0.0, 64.0, 0.0,
                    request.radius(), request.verticalRadius(), true, 1_001L
            );

            assertTrue(fast.fastConsume());
            assertTrue(NeedsResourceTargetCacheAdapter.isFastConsumeTargetForTests(
                    npc, "world-a", "water", fast.target(), 2_000L
            ));
            assertNull(adapter.resolveLocal(
                    npc, "world-a", "water", 0.0, 64.0, 0.0, 2_502L
            ));
            assertFalse(NeedsResourceTargetCacheAdapter.isFastConsumeTargetForTests(
                    npc, "world-a", "water", fast.target(), 2_502L
            ));
            coordinator.clear(store);
        }
    }

    @Test
    void repeatedDeferredRequestWaitsForTheCoordinatorRetryWindow() {
        try (TestEntityComponentStore store = newStore()) {
            NeedsResourceSearchCoordinator coordinator = NeedsResourceSearchCoordinator.getInstance();
            coordinator.clear(store);
            NeedsResourceHotPathDiagnostics.setEnabledForTests(true);
            NeedsResourceTargetCacheAdapter adapter = new NeedsResourceTargetCacheAdapter(coordinator);
            NeedsResourceSearchCoordinator.Request request = request("world-a");
            UUID npc = new UUID(0L, 103L);

            assertEquals(NeedsResourceTargetCacheAdapter.Status.DEFERRED,
                    adapter.resolve(store, npc, request, "world-a", 0.0, 64.0, 0.0,
                            request.radius(), request.verticalRadius(), false, 1_000L).status());
            assertEquals(NeedsResourceTargetCacheAdapter.Status.DEFERRED,
                    adapter.resolve(store, npc, request, "world-a", 0.0, 64.0, 0.0,
                            request.radius(), request.verticalRadius(), false, 1_020L).status());
            assertEquals(NeedsResourceTargetCacheAdapter.Status.DEFERRED,
                    adapter.resolve(store, npc, request, "world-a", 0.0, 64.0, 0.0,
                            request.radius(), request.verticalRadius(), false, 1_051L).status());

            NeedsResourceHotPathDiagnostics.Snapshot snapshot =
                    NeedsResourceHotPathDiagnostics.snapshot();
            assertEquals(2L, snapshot.coordinatorLookups());
            assertEquals(1L, snapshot.coordinatorRetriesSuppressed());
            coordinator.clear(store);
        }
    }

    @Test
    void reservationsSkipOnlyTheCurrentNpcCandidate() {
        NeedsResourceCandidates.Snapshot snapshot = new NeedsResourceCandidates.Snapshot(
                List.of(
                        new NeedsResourceCandidates.Candidate(2, 64, 2, 1.5),
                        new NeedsResourceCandidates.Candidate(4, 64, 2, 1.5)
                ),
                true,
                false,
                10_000L
        );
        UUID firstNpc = new UUID(0L, 1L);
        UUID secondNpc = new UUID(0L, 2L);

        NeedsResourceTargetCacheAdapter.Selection first = NeedsResourceTargetCacheAdapter.selectCandidate(
                snapshot,
                firstNpc,
                "test-world",
                "water",
                0.0,
                64.0,
                0.0,
                8.0,
                1,
                1_000L
        );
        assertNotNull(first.candidate());
        assertEquals(2, first.candidate().x());
        NeedsResourceTargetCacheAdapter.reserveTarget(
                firstNpc,
                "test-world",
                "water",
                new Vector3d(first.candidate().x() + 0.5, first.candidate().y() + 0.5, first.candidate().z() + 0.5),
                1_000L
        );

        NeedsResourceTargetCacheAdapter.Selection second = NeedsResourceTargetCacheAdapter.selectCandidate(
                snapshot,
                secondNpc,
                "test-world",
                "water",
                0.0,
                64.0,
                0.0,
                8.0,
                1,
                1_000L
        );
        assertNotNull(second.candidate());
        assertEquals(4, second.candidate().x());
    }

    @Test
    void cachedTargetRemainsUsableAfterNpcMovesOneBlock() {
        Vector3d target = new Vector3d(8.5, 64.5, 0.5);
        assertNotNull(target);
        org.junit.jupiter.api.Assertions.assertTrue(NeedsResourceTargetCacheAdapter.cachedTargetUsableForTests(
                target, new Vector3d(1.0, 64.0, 0.0), 12.0, 2));
    }

    @Test
    void warmSharedSnapshotReturnsTargetWithoutAnotherSearch() {
        try (TestEntityComponentStore store = newStore()) {
            NeedsResourceSearchCoordinator coordinator = NeedsResourceSearchCoordinator.getInstance();
            coordinator.clear(store);
            NeedsResourceSearchCoordinator.Request request = NeedsResourceSearchCoordinator.Request.forArea(
                    "water", "test-world", 0.0, 64.0, 0.0, 8.0, 2, 3.0, List.of());
            NeedsResourceCandidates.Snapshot snapshot = new NeedsResourceCandidates.Snapshot(
                    List.of(new NeedsResourceCandidates.Candidate(2, 64, 2, 1.5)),
                    true,
                    false,
                    10_000L
            );
            AtomicInteger searchCalls = new AtomicInteger();
            NeedsResourceSearchCoordinator.SearchExecutor executor = (ignoredStore, ignoredRequest, ignoredWaiters) -> {
                searchCalls.incrementAndGet();
                return snapshot;
            };
            UUID npc = new UUID(0L, 3L);
            assertEquals(NeedsResourceSearchCoordinator.Lookup.Status.DEFERRED,
                    coordinator.lookupOrEnqueue(store, npc, request, 1_000L).status());
            assertEquals(1, coordinator.processOne(store, 1L, 1_000L, executor));

            NeedsResourceTargetCacheAdapter adapter = new NeedsResourceTargetCacheAdapter(coordinator);
            NeedsResourceTargetCacheAdapter.Result result = adapter.resolve(
                    store,
                    npc,
                    request,
                    "test-world",
                    0.0,
                    64.0,
                    0.0,
                    request.radius(),
                    request.verticalRadius(),
                    false,
                    1_001L
            );

            assertEquals(NeedsResourceTargetCacheAdapter.Status.TARGET, result.status());
            assertNotNull(result.target());
            assertEquals(1, searchCalls.get());
            coordinator.clear(store);
        }
    }

    @Test
    void reservedLocalTargetFallsThroughToAnotherSharedCandidate() {
        try (TestEntityComponentStore store = newStore()) {
            NeedsResourceSearchCoordinator coordinator = NeedsResourceSearchCoordinator.getInstance();
            coordinator.clear(store);
            NeedsResourceSearchCoordinator.Request request = request("world-a");
            NeedsResourceCandidates.Snapshot snapshot = twoCandidateSnapshot();
            UUID npc = new UUID(0L, 4L);
            UUID otherNpc = new UUID(0L, 5L);
            warm(coordinator, store, npc, request, snapshot, 1_000L);
            NeedsResourceTargetCacheAdapter adapter = new NeedsResourceTargetCacheAdapter(coordinator);

            NeedsResourceTargetCacheAdapter.Result first = adapter.resolve(
                    store, npc, request, "world-a", 0.0, 64.0, 0.0,
                    request.radius(), request.verticalRadius(), false, 1_001L
            );
            assertEquals(NeedsResourceTargetCacheAdapter.Status.TARGET, first.status());
            assertEquals(2, (int) Math.floor(first.target().x));

            NeedsResourceTargetCacheAdapter.releaseTarget(npc, "world-a", "water", first.target());
            assertTrue(NeedsResourceTargetCacheAdapter.reserveTarget(
                    otherNpc, "world-a", "water", first.target(), 1_100L
            ));

            NeedsResourceTargetCacheAdapter.Result fallback = adapter.resolve(
                    store, npc, request, "world-a", 0.0, 64.0, 0.0,
                    request.radius(), request.verticalRadius(), false, 1_101L
            );
            assertEquals(NeedsResourceTargetCacheAdapter.Status.TARGET, fallback.status());
            assertEquals(4, (int) Math.floor(fallback.target().x));
            coordinator.clear(store);
        }
    }

    @Test
    void mixedRejectedAndReservedCandidatesRefreshOnlyRejectedCandidate() {
        try (TestEntityComponentStore store = newStore()) {
            NeedsResourceSearchCoordinator coordinator = NeedsResourceSearchCoordinator.getInstance();
            coordinator.clear(store);
            NeedsResourceSearchCoordinator.Request request = request("world-a");
            NeedsResourceCandidates.Snapshot snapshot = twoCandidateSnapshot();
            UUID npc = new UUID(0L, 6L);
            UUID otherNpc = new UUID(0L, 7L);
            warm(coordinator, store, npc, request, snapshot, 1_000L);
            Vector3d rejected = new Vector3d(2.5, 64.5, 2.5);
            Vector3d reserved = new Vector3d(4.5, 64.5, 2.5);
            assertTrue(NeedsResourceTargetCacheAdapter.rejectTarget(npc, "water", rejected, 30.0, 1_001L));
            assertTrue(NeedsResourceTargetCacheAdapter.reserveTarget(
                    otherNpc, "world-a", "water", reserved, 1_001L
            ));

            NeedsResourceTargetCacheAdapter.Result result = new NeedsResourceTargetCacheAdapter(coordinator).resolve(
                    store, npc, request, "world-a", 0.0, 64.0, 0.0,
                    request.radius(), request.verticalRadius(), false, 1_002L
            );
            assertEquals(NeedsResourceTargetCacheAdapter.Status.DEFERRED, result.status());
            NeedsResourceSearchCoordinator.Lookup remaining = coordinator.lookupOrEnqueue(
                    store, new UUID(0L, 8L), request, 1_003L
            );
            assertEquals(NeedsResourceSearchCoordinator.Lookup.Status.HIT, remaining.status());
            assertEquals(List.of(4), remaining.snapshot().candidates().stream()
                    .map(NeedsResourceCandidates.Candidate::x)
                    .toList());
            coordinator.clear(store);
        }
    }

    @Test
    void worldTransferDoesNotReuseLocalTarget() {
        try (TestEntityComponentStore store = newStore()) {
            NeedsResourceSearchCoordinator coordinator = NeedsResourceSearchCoordinator.getInstance();
            coordinator.clear(store);
            UUID npc = new UUID(0L, 9L);
            NeedsResourceSearchCoordinator.Request firstRequest = request("world-a");
            warm(coordinator, store, npc, firstRequest, oneCandidateSnapshot(), 1_000L);
            NeedsResourceTargetCacheAdapter adapter = new NeedsResourceTargetCacheAdapter(coordinator);
            assertEquals(NeedsResourceTargetCacheAdapter.Status.TARGET,
                    adapter.resolve(store, npc, firstRequest, "world-a", 0.0, 64.0, 0.0,
                            firstRequest.radius(), firstRequest.verticalRadius(), false, 1_001L).status());

            NeedsResourceSearchCoordinator.Request secondRequest = request("world-b");
            NeedsResourceTargetCacheAdapter.Result result = adapter.resolve(
                    store, npc, secondRequest, "world-b", 0.0, 64.0, 0.0,
                    secondRequest.radius(), secondRequest.verticalRadius(), false, 1_002L
            );
            assertEquals(NeedsResourceTargetCacheAdapter.Status.DEFERRED, result.status());
            assertNull(result.target());
            coordinator.clear(store);
        }
    }

    @Test
    void adoptedTargetKeepsItsApproachRadiusInLocalCache() {
        try (TestEntityComponentStore store = newStore()) {
            NeedsResourceSearchCoordinator coordinator = NeedsResourceSearchCoordinator.getInstance();
            coordinator.clear(store);
            NeedsResourceSearchCoordinator.Request request = request("world-a");
            UUID npc = new UUID(0L, 10L);
            Vector3d target = new Vector3d(1.5, 64.5, 1.5);
            NeedsResourceTargetCacheAdapter adapter = new NeedsResourceTargetCacheAdapter(coordinator);
            NeedsResourceTargetCacheAdapter.Result adopted = adapter.adoptTarget(
                    npc, "world-a", "water", target, 0.75, request.radius(),
                    request.verticalRadius(), 1_000L
            );
            assertEquals(NeedsResourceTargetCacheAdapter.Status.TARGET, adopted.status());
            assertTrue(adopted.preflightRequired());

            NeedsResourceTargetCacheAdapter.Result result = adapter.resolve(
                    store, npc, request, "world-a", 0.0, 64.0, 0.0,
                    request.radius(), request.verticalRadius(), false, 1_001L
            );
            assertEquals(NeedsResourceTargetCacheAdapter.Status.TARGET, result.status());
            assertEquals(0.75, result.approachRadius(), 0.000001);
            assertTrue(result.preflightRequired());
            assertTrue(adapter.promoteTarget(npc, "world-a", "water", target));
            NeedsResourceTargetCacheAdapter.Result validated = adapter.resolveLocal(
                    npc, "world-a", "water", 0.0, 64.0, 0.0, 1_002L
            );
            assertNotNull(validated);
            assertFalse(validated.preflightRequired());
            coordinator.clear(store);
        }
    }

    @Test
    void localWarmHitRequiresPromotionAndReturnsDefensiveCopies() {
        NeedsResourceTargetCacheAdapter adapter = new NeedsResourceTargetCacheAdapter();
        UUID npc = new UUID(0L, 12L);
        Vector3d target = new Vector3d(2.5, 64.5, 2.5);
        NeedsResourceTargetCacheAdapter.Result adopted = adapter.adoptTarget(
                npc, "world-a", "water", target, 1.5, 8.0, 2, 1_000L
        );
        assertEquals(NeedsResourceTargetCacheAdapter.Status.TARGET, adopted.status());
        assertTrue(adopted.preflightRequired());

        NeedsResourceTargetCacheAdapter.Result warm = adapter.resolveLocal(
                npc,
                "world-a",
                "water",
                1.0,
                64.0,
                0.0,
                1_001L
        );

        assertNotNull(warm);
        assertEquals(NeedsResourceTargetCacheAdapter.Status.TARGET, warm.status());
        assertTrue(warm.preflightRequired());
        warm.target().x = 99.0;
        NeedsResourceTargetCacheAdapter.Result defensiveCopy = adapter.resolveLocal(
                npc, "world-a", "water", 1.0, 64.0, 0.0, 1_002L
        );
        assertNotNull(defensiveCopy);
        assertNotSame(warm, defensiveCopy);
        assertEquals(2.5, defensiveCopy.target().x, 0.000001);
        assertTrue(adapter.promoteTarget(npc, "world-a", "water", target));
        NeedsResourceTargetCacheAdapter.Result validated = adapter.resolveLocal(
                npc, "world-a", "water", 1.0, 64.0, 0.0, 1_003L
        );
        assertNotNull(validated);
        assertFalse(validated.preflightRequired());
    }

    @Test
    void releaseAndRejectInvalidateLocalTargetsImmediately() {
        NeedsResourceTargetCacheAdapter adapter = new NeedsResourceTargetCacheAdapter();
        UUID releasedNpc = new UUID(0L, 16L);
        UUID rejectedNpc = new UUID(0L, 17L);
        Vector3d releasedTarget = new Vector3d(2.5, 64.5, 2.5);
        Vector3d rejectedTarget = new Vector3d(3.5, 64.5, 3.5);
        adapter.adoptTarget(releasedNpc, "world-a", "water", releasedTarget, 1.5, 8.0, 2, 1_000L);
        adapter.adoptTarget(rejectedNpc, "world-a", "water", rejectedTarget, 1.5, 8.0, 2, 1_000L);

        NeedsResourceTargetCacheAdapter.releaseTarget(
                releasedNpc, "world-a", "water", releasedTarget
        );
        NeedsResourceTargetCacheAdapter.rejectTarget(
                rejectedNpc, "water", rejectedTarget, 4.0, 1_001L
        );

        assertNull(adapter.resolveLocal(
                releasedNpc, "world-a", "water", 0.0, 64.0, 0.0, 1_002L
        ));
        assertNull(adapter.resolveLocal(
                rejectedNpc, "world-a", "water", 0.0, 64.0, 0.0, 1_002L
        ));
    }

    @Test
    void worldClearRemovesLocalRecentAndFastTargetsOnlyForThatWorld() {
        NeedsResourceTargetCacheAdapter adapter = new NeedsResourceTargetCacheAdapter();
        UUID removedNpc = new UUID(0L, 13L);
        UUID retainedNpc = new UUID(0L, 14L);
        Vector3d removedTarget = new Vector3d(2.5, 64.5, 2.5);
        Vector3d retainedTarget = new Vector3d(3.5, 64.5, 3.5);
        adapter.adoptTarget(removedNpc, "world-a", "water", removedTarget, 1.5, 8.0, 2, 1_000L);
        adapter.adoptTarget(retainedNpc, "world-b", "water", retainedTarget, 1.5, 8.0, 2, 1_000L);
        adapter.rememberRecentTarget(removedNpc, "world-a", "water", removedTarget, 1_000L);
        NeedsResourceTargetCacheAdapter.rememberFastConsumeTargetForTests(
                removedNpc, "world-a", "water", removedTarget, 2_500L
        );

        NeedsResourceTargetCacheAdapter.clearWorld("world-a");

        assertNull(adapter.resolveLocal(removedNpc, "world-a", "water", 0.0, 64.0, 0.0, 1_001L));
        assertNull(adapter.resolveRecentTarget(
                removedNpc, "world-a", "water", new Vector3d(), 1_001L
        ));
        assertFalse(NeedsResourceTargetCacheAdapter.isFastConsumeTargetForTests(
                removedNpc, "world-a", "water", removedTarget, 1_001L
        ));
        assertNotNull(adapter.resolveLocal(retainedNpc, "world-b", "water", 0.0, 64.0, 0.0, 1_001L));
    }

    @Test
    void localTargetCacheStaysBounded() {
        NeedsResourceTargetCacheAdapter adapter = new NeedsResourceTargetCacheAdapter();
        int maxEntries = NeedsResourceTargetCacheAdapter.MAX_LOCAL_TARGETS;
        for (int i = 0; i < maxEntries + 25; i++) {
            adapter.adoptTarget(
                    new UUID(1L, i),
                    "world-a",
                    "water",
                    new Vector3d(i, 64.5, 0.5),
                    1.5,
                    maxEntries + 32.0,
                    2,
                    1_000L + i
            );
        }
        assertTrue(adapter.localTargetCountForTests() <= maxEntries);
    }

    @Test
    void concurrentTargetAndRecentAdmissionStaysWithinHardCapacity() throws Exception {
        NeedsResourceTargetStateStore stateStore = new NeedsResourceTargetStateStore();
        int workers = 16;
        int entriesPerWorker = 1_024;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int worker = 0; worker < workers; worker++) {
            final int workerId = worker;
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                for (int entry = 0; entry < entriesPerWorker; entry++) {
                    UUID npc = new UUID(workerId + 10L, entry);
                    Vector3d target = new Vector3d(entry + 0.5, 64.5, workerId + 0.5);
                    stateStore.cache(npc, "world-a", "water", target, "water", 1.5,
                            12.0, 2, 10_000L + entry, false,
                            NeedsResourceTargetStateStore.PathState.VALIDATED);
                    stateStore.rememberRecentTarget(npc, "world-a", "water", target, 1_000L);
                }
                return null;
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        for (Future<?> future : futures) {
            future.get();
        }
        assertTrue(stateStore.size("water") <= NeedsResourceTargetStateStore.MAX_ENTRIES_PER_RESOURCE);
        assertTrue(stateStore.recentSize("water") <= NeedsResourceTargetStateStore.MAX_ENTRIES_PER_RESOURCE);
    }

    @Test
    void pendingKeepaliveExtendsTargetLifetimeWithoutValidation() {
        NeedsResourceTargetCacheAdapter adapter = new NeedsResourceTargetCacheAdapter();
        UUID npc = new UUID(3L, 8L);
        Vector3d target = new Vector3d(2.5, 64.5, 2.5);
        NeedsResourceTargetCacheAdapter.Result adopted = adapter.adoptTarget(
                npc, "world-a", "water", target, 1.5, 8.0, 2, 1_000L
        );
        assertTrue(adopted.preflightRequired());

        assertTrue(adapter.keepPendingTarget(npc, "world-a", "water", target, 2_400L));
        NeedsResourceTargetCacheAdapter.Result retained = adapter.resolveLocal(
                npc, "world-a", "water", 0.0, 64.0, 0.0, 3_800L
        );
        assertNotNull(retained);
        assertTrue(retained.preflightRequired());
    }

    @Test
    void fastConsumeTargetCacheStaysBounded() {
        int maxEntries = NeedsResourceTargetCacheAdapter.MAX_FAST_CONSUME_TARGETS;
        for (int i = 0; i < maxEntries + 25; i++) {
            NeedsResourceTargetCacheAdapter.rememberFastConsumeTargetForTests(
                    new UUID(2L, i),
                    "world-a",
                    "water",
                    new Vector3d(i, 64.5, 0.5),
                    2_500L + i
            );
        }
        assertTrue(NeedsResourceTargetCacheAdapter.fastConsumeTargetCountForTests() <= maxEntries);
    }

    @Test
    void consumeRangeHitPreservesFastModeMarker() {
        try (TestEntityComponentStore store = newStore()) {
            NeedsResourceSearchCoordinator coordinator = NeedsResourceSearchCoordinator.getInstance();
            coordinator.clear(store);
            NeedsResourceSearchCoordinator.Request request = request("world-a");
            UUID npc = new UUID(0L, 15L);
            NeedsResourceCandidates.Snapshot snapshot = new NeedsResourceCandidates.Snapshot(
                    List.of(), true, true, 10_000L
            );
            warm(coordinator, store, npc, request, snapshot, 1_000L);

            NeedsResourceTargetCacheAdapter.Result result = new NeedsResourceTargetCacheAdapter(coordinator).resolve(
                    store, npc, request, "world-a", 0.0, 64.0, 0.0,
                    request.radius(), request.verticalRadius(), true, 1_001L
            );

            assertTrue(result.fastConsume(), result.toString());
            assertFalse(result.preflightRequired());
            assertTrue(NeedsResourceTargetCacheAdapter.isFastConsumeTargetForTests(
                    npc, "world-a", "water", result.target(), 1_002L
            ));
            coordinator.clear(store);
        }
    }

    @Test
    void clearingAnUncachedTargetIsSafe() {
        NeedsResourceTargetCacheAdapter adapter = new NeedsResourceTargetCacheAdapter();
        assertDoesNotThrow(() -> adapter.clearTarget(
                new UUID(0L, 11L),
                "world-a",
                "water",
                new Vector3d(1.5, 64.5, 1.5)
        ));
    }

    @Test
    void sharedStateReleaseRemovesOnlyMatchingNpcResourceAndWorldTarget() {
        NeedsResourceTargetStateStore stateStore = new NeedsResourceTargetStateStore();
        UUID releasedNpc = new UUID(3L, 1L);
        UUID retainedNpc = new UUID(3L, 2L);
        Vector3d waterTarget = new Vector3d(2.5, 64.5, 2.5);
        Vector3d foodTarget = new Vector3d(4.5, 64.5, 4.5);

        stateStore.cache(releasedNpc, "world-a", "water", waterTarget, "water", 1.5,
                8.0, 2, 10_000L, false, NeedsResourceTargetStateStore.PathState.VALIDATED);
        stateStore.cache(retainedNpc, "world-a", "water", waterTarget, "water", 1.5,
                8.0, 2, 10_000L, false, NeedsResourceTargetStateStore.PathState.VALIDATED);
        stateStore.cache(releasedNpc, "world-a", "food_container", foodTarget, "food", 1.5,
                8.0, 2, 10_000L, false, NeedsResourceTargetStateStore.PathState.VALIDATED);

        assertTrue(stateStore.clear(releasedNpc, "world-a", "water", waterTarget, false));
        assertNull(stateStore.resolve(releasedNpc, "world-a", "water", 0.0, 64.0, 0.0, 1_001L));
        assertNotNull(stateStore.resolve(retainedNpc, "world-a", "water", 0.0, 64.0, 0.0, 1_001L));
        assertNotNull(stateStore.resolve(releasedNpc, "world-a", "food_container", 0.0, 64.0, 0.0, 1_001L));
    }

    @Test
    void rejectionWithoutWorldIdentityClearsMatchingBlockAcrossWorlds() {
        NeedsResourceTargetStateStore stateStore = new NeedsResourceTargetStateStore();
        UUID npc = new UUID(3L, 3L);
        UUID otherWorldNpc = new UUID(3L, 6L);
        Vector3d target = new Vector3d(2.5, 64.5, 2.5);
        stateStore.cache(npc, "world-a", "water", target, "water", 1.5,
                8.0, 2, 10_000L, false, NeedsResourceTargetStateStore.PathState.VALIDATED);
        stateStore.cache(otherWorldNpc, "world-b", "water", target, "water", 1.5,
                8.0, 2, 10_000L, false, NeedsResourceTargetStateStore.PathState.VALIDATED);

        assertTrue(stateStore.clear(npc, null, "water", target, true));
        assertNull(stateStore.resolve(npc, "world-a", "water", 0.0, 64.0, 0.0, 1_001L));
        assertNotNull(stateStore.resolve(otherWorldNpc, "world-b", "water", 0.0, 64.0, 0.0, 1_001L));
    }

    @Test
    void pendingTargetIsReturnedForPreflightThenPromotedOnlyWhenBlockMatches() {
        NeedsResourceTargetStateStore stateStore = new NeedsResourceTargetStateStore();
        UUID npc = new UUID(3L, 4L);
        Vector3d target = new Vector3d(2.5, 64.5, 2.5);
        stateStore.cache(npc, "world-a", "water", target, "water", 1.5,
                8.0, 2, 10_000L, false, NeedsResourceTargetStateStore.PathState.PENDING);

        NeedsResourceTargetStateStore.TargetState pending = stateStore.resolve(
                npc, "world-a", "water", 0.0, 64.0, 0.0, 1_001L);
        assertNotNull(pending);
        assertEquals(NeedsResourceTargetStateStore.PathState.PENDING, pending.pathState());
        assertFalse(stateStore.promote(npc, "world-a", "water", new Vector3d(3.5, 64.5, 2.5)));
        assertEquals(NeedsResourceTargetStateStore.PathState.PENDING,
                stateStore.resolve(npc, "world-a", "water", 0.0, 64.0, 0.0, 1_002L).pathState());

        assertTrue(stateStore.promote(npc, "world-a", "water", target));
        NeedsResourceTargetStateStore.TargetState validated = stateStore.resolve(
                npc, "world-a", "water", 0.0, 64.0, 0.0, 1_003L);
        assertNotNull(validated);
        assertEquals(NeedsResourceTargetStateStore.PathState.VALIDATED, validated.pathState());
    }

    @Test
    void adapterExposesPendingPreflightThenValidatedLocalResult() {
        NeedsResourceTargetStateStore stateStore = NeedsResourceTargetStateStore.shared();
        NeedsResourceTargetCacheAdapter adapter = new NeedsResourceTargetCacheAdapter();
        UUID npc = new UUID(3L, 7L);
        Vector3d target = new Vector3d(2.5, 64.5, 2.5);
        stateStore.cache(npc, "world-a", "water", target, "resource_target_search_shared", 1.5,
                8.0, 2, 10_000L, false, NeedsResourceTargetStateStore.PathState.PENDING);

        NeedsResourceTargetCacheAdapter.Result pending = adapter.resolveLocal(
                npc, "world-a", "water", 0.0, 64.0, 0.0, 1_001L);
        assertNotNull(pending);
        assertTrue(pending.preflightRequired());
        assertTrue(adapter.promoteTarget(npc, "world-a", "water", target));

        NeedsResourceTargetCacheAdapter.Result validated = adapter.resolveLocal(
                npc, "world-a", "water", 0.0, 64.0, 0.0, 1_002L);
        assertNotNull(validated);
        assertFalse(validated.preflightRequired());
    }

    @Test
    void clearingWithDifferentBlockDoesNotChangeCachedTarget() {
        NeedsResourceTargetStateStore stateStore = new NeedsResourceTargetStateStore();
        UUID npc = new UUID(3L, 5L);
        Vector3d target = new Vector3d(2.5, 64.5, 2.5);
        stateStore.cache(npc, "world-a", "water", target, "water", 1.5,
                8.0, 2, 10_000L, false, NeedsResourceTargetStateStore.PathState.PENDING);

        assertFalse(stateStore.clear(npc, "world-a", "water", new Vector3d(2.5, 64.5, 3.5), false));
        NeedsResourceTargetStateStore.TargetState retained = stateStore.resolve(
                npc, "world-a", "water", 0.0, 64.0, 0.0, 1_001L);
        assertNotNull(retained);
        assertEquals(NeedsResourceTargetStateStore.PathState.PENDING, retained.pathState());
    }

    private static NeedsResourceSearchCoordinator.Request request(String worldName) {
        return NeedsResourceSearchCoordinator.Request.forArea(
                "water", worldName, 0.0, 64.0, 0.0, 8.0, 2, 3.0, List.of());
    }

    private static NeedsResourceCandidates.Snapshot oneCandidateSnapshot() {
        return new NeedsResourceCandidates.Snapshot(
                List.of(new NeedsResourceCandidates.Candidate(2, 64, 2, 1.5)),
                true,
                false,
                10_000L
        );
    }

    private static NeedsResourceCandidates.Snapshot twoCandidateSnapshot() {
        return new NeedsResourceCandidates.Snapshot(
                List.of(
                        new NeedsResourceCandidates.Candidate(2, 64, 2, 1.5),
                        new NeedsResourceCandidates.Candidate(4, 64, 2, 1.5)
                ),
                true,
                false,
                10_000L
        );
    }

    private static void warm(NeedsResourceSearchCoordinator coordinator,
                             TestEntityComponentStore store,
                             UUID npc,
                             NeedsResourceSearchCoordinator.Request request,
                             NeedsResourceCandidates.Snapshot snapshot,
                             long nowMs) {
        assertEquals(NeedsResourceSearchCoordinator.Lookup.Status.DEFERRED,
                coordinator.lookupOrEnqueue(store, npc, request, nowMs).status());
        assertEquals(1, coordinator.processOne(store, 1L, nowMs,
                (ignoredStore, ignoredRequest, ignoredWaiters) -> snapshot));
    }

    private static TestEntityComponentStore newStore() {
        return new TestEntityComponentStore(new EntityStore(null));
    }
}
