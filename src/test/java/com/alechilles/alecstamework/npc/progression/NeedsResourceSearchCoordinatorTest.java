package com.alechilles.alecstamework.npc.progression;

import static com.alechilles.alecstamework.npc.progression.NeedsResourceSearchCoordinator.Lookup.Status.DEFERRED;
import static com.alechilles.alecstamework.npc.progression.NeedsResourceSearchCoordinator.Lookup.Status.HIT;
import static com.alechilles.alecstamework.npc.progression.NeedsResourceSearchCoordinator.Lookup.Status.MISS;
import static com.alechilles.alecstamework.performance.RuntimePressureDomain.NEEDS_RESOURCE_SEARCH;
import static com.alechilles.alecstamework.performance.RuntimePressureLevel.NORMAL;
import static com.alechilles.alecstamework.performance.RuntimePressureLevel.WARM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.performance.TameworkRuntimePressureService;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.TestEntityComponentStore;
import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NeedsResourceSearchCoordinatorTest {
    private static final long NOW_MS = 1_000L;

    private final NeedsResourceSearchCoordinator coordinator =
            NeedsResourceSearchCoordinator.getInstance();

    @AfterEach
    void clearPressure() {
        TameworkRuntimePressureService.getInstance().clearForTests();
    }

    @Test
    void equivalentRequestsDeduplicateAndPreserveWaiterOrder() {
        try (TestEntityComponentStore store = newStore()) {
            UUID firstNpc = uuid(1);
            UUID secondNpc = uuid(2);
            NeedsResourceSearchCoordinator.Request request = request(1);
            CountingExecutor executor = new CountingExecutor(hitSnapshot(1_500L));
            coordinator.clear(store);

            assertEquals(DEFERRED, coordinator.lookupOrEnqueue(store, firstNpc, request, NOW_MS).status());
            assertEquals(DEFERRED, coordinator.lookupOrEnqueue(store, secondNpc, request, NOW_MS).status());
            assertEquals(1, coordinator.pendingCountForTests(store));

            assertEquals(1, coordinator.processOne(store, 8L, NOW_MS + 50L, executor));

            assertEquals(1, executor.calls());
            assertEquals(List.of(firstNpc, secondNpc), executor.waiters().get(0));
            assertEquals(HIT, coordinator.lookupOrEnqueue(store, firstNpc, request, NOW_MS + 51L).status());
            assertEquals(0, coordinator.pendingCountForTests(store));
        }
    }

    @Test
    void cacheHitDoesNotEnqueueOrRecordAnotherSearch() {
        try (TestEntityComponentStore store = newStore()) {
            NeedsResourceSearchCoordinator.Request request = request(2);
            CountingExecutor executor = new CountingExecutor(hitSnapshot(1_500L));
            coordinator.clear(store);

            coordinator.lookupOrEnqueue(store, uuid(3), request, NOW_MS);
            coordinator.processOne(store, 8L, NOW_MS, executor);
            assertEquals(1, executor.calls());

            TameworkRuntimePressureService pressure = TameworkRuntimePressureService.getInstance();
            pressure.clearForTests();
            for (int index = 0; index < 127; index++) {
                pressure.recordWork(NEEDS_RESOURCE_SEARCH, 1_000L, NOW_MS);
            }
            assertEquals(HIT, coordinator.lookupOrEnqueue(store, uuid(4), request, NOW_MS + 1L).status());
            assertEquals(0, coordinator.pendingCountForTests(store));
            assertEquals(1, executor.calls());
            assertEquals(NORMAL, pressure.level(NEEDS_RESOURCE_SEARCH, NOW_MS));
        }
    }

    @Test
    void pendingRequestCapDefersOnlyNewKeysAndExistingKeysStillDeduplicate() {
        try (TestEntityComponentStore store = newStore()) {
            coordinator.clear(store);
            for (int index = 0; index < NeedsResourceSearchCoordinator.MAX_PENDING_REQUESTS; index++) {
                assertEquals(
                        DEFERRED,
                        coordinator.lookupOrEnqueue(store, uuid(10_000L + index), request(index), NOW_MS).status()
                );
            }
            assertEquals(NeedsResourceSearchCoordinator.MAX_PENDING_REQUESTS, coordinator.pendingCountForTests(store));

            NeedsResourceSearchCoordinator.Request existing = request(0);
            assertEquals(DEFERRED, coordinator.lookupOrEnqueue(store, uuid(99_000L), existing, NOW_MS).status());
            assertEquals(NeedsResourceSearchCoordinator.MAX_PENDING_REQUESTS, coordinator.pendingCountForTests(store));

            assertEquals(DEFERRED, coordinator.lookupOrEnqueue(store, uuid(99_001L), request(100_000), NOW_MS).status());
            assertEquals(NeedsResourceSearchCoordinator.MAX_PENDING_REQUESTS, coordinator.pendingCountForTests(store));
        }
    }

    @Test
    void oneThousandDistinctRequestsUseOneSearchPerEligibleTick() {
        try (TestEntityComponentStore store = newStore()) {
            coordinator.clear(store);
            CountingExecutor executor = new CountingExecutor(hitSnapshot(1_500L));
            for (int index = 0; index < 1_000; index++) {
                coordinator.lookupOrEnqueue(store, uuid(200_000L + index), request(index), NOW_MS);
            }

            assertEquals(1, coordinator.processOne(store, 8L, NOW_MS, executor));
            assertEquals(1, executor.calls());
            assertEquals(1, coordinator.processOne(store, 16L, NOW_MS, executor));
            assertEquals(2, executor.calls());
        }
    }

    @Test
    void repeatedProcessCallsCannotAttemptTwoSearchesOnOneWorldTick() {
        try (TestEntityComponentStore store = newStore()) {
            coordinator.clear(store);
            CountingExecutor executor = new CountingExecutor(hitSnapshot(1_500L));
            coordinator.lookupOrEnqueue(store, uuid(250), request(250), NOW_MS);
            coordinator.lookupOrEnqueue(store, uuid(251), request(251), NOW_MS);

            assertEquals(1, coordinator.processOne(store, -8L, NOW_MS, executor));
            assertEquals(0, coordinator.processOne(store, -8L, NOW_MS, executor));
            assertEquals(1, executor.calls());
            assertEquals(1, coordinator.pendingCountForTests(store));
        }
    }

    @Test
    void oldestPendingKeysCompleteFirst() {
        try (TestEntityComponentStore store = newStore()) {
            coordinator.clear(store);
            CountingExecutor executor = new CountingExecutor(hitSnapshot(1_500L));
            NeedsResourceSearchCoordinator.Request oldest = request(300);
            NeedsResourceSearchCoordinator.Request middle = request(301);
            NeedsResourceSearchCoordinator.Request newest = request(302);
            coordinator.lookupOrEnqueue(store, uuid(300), oldest, NOW_MS);
            coordinator.lookupOrEnqueue(store, uuid(301), middle, NOW_MS);
            coordinator.lookupOrEnqueue(store, uuid(302), newest, NOW_MS);

            coordinator.processOne(store, 8L, NOW_MS, executor);
            coordinator.processOne(store, 16L, NOW_MS, executor);
            coordinator.processOne(store, 24L, NOW_MS, executor);

            assertEquals(List.of(oldest, middle, newest), executor.requests());
        }
    }

    @Test
    void pressureIneligibleTickLeavesOldestRequestPending() {
        try (TestEntityComponentStore store = newStore()) {
            coordinator.clear(store);
            TameworkRuntimePressureService pressure = TameworkRuntimePressureService.getInstance();
            for (int index = 0; index < 128; index++) {
                pressure.recordWork(NEEDS_RESOURCE_SEARCH, 100_000L, NOW_MS);
            }
            assertEquals(WARM, pressure.level(NEEDS_RESOURCE_SEARCH, NOW_MS));

            CountingExecutor executor = new CountingExecutor(hitSnapshot(1_500L));
            coordinator.lookupOrEnqueue(store, uuid(400), request(400), NOW_MS);

            assertEquals(0, coordinator.processOne(store, 1L, NOW_MS, executor));
            assertEquals(0, executor.calls());
            assertEquals(1, coordinator.pendingCountForTests(store));
            assertEquals(1, coordinator.processOne(store, 2L, NOW_MS, executor));
            assertEquals(1, executor.calls());
        }
    }

    @Test
    void hitAndMissResultsExpireBeforeARequestIsRetried() {
        try (TestEntityComponentStore store = newStore()) {
            coordinator.clear(store);
            NeedsResourceSearchCoordinator.Request hitRequest = request(500);
            CountingExecutor hitExecutor = new CountingExecutor(hitSnapshot(100L));
            coordinator.lookupOrEnqueue(store, uuid(500), hitRequest, NOW_MS);
            coordinator.processOne(store, 8L, NOW_MS, hitExecutor);

            assertEquals(HIT, coordinator.lookupOrEnqueue(store, uuid(501), hitRequest, NOW_MS + 99L).status());
            assertEquals(DEFERRED, coordinator.lookupOrEnqueue(store, uuid(501), hitRequest, NOW_MS + 100L).status());
            assertEquals(1, coordinator.processOne(store, 16L, NOW_MS + 100L, hitExecutor));

            NeedsResourceSearchCoordinator.Request missRequest = request(501);
            CountingExecutor missExecutor = new CountingExecutor(missSnapshot(100L));
            coordinator.lookupOrEnqueue(store, uuid(502), missRequest, NOW_MS);
            coordinator.processOne(store, 24L, NOW_MS, missExecutor);

            NeedsResourceSearchCoordinator.Lookup miss =
                    coordinator.lookupOrEnqueue(store, uuid(503), missRequest, NOW_MS + 99L);
            assertEquals(MISS, miss.status());
            assertNotNull(miss.snapshot());
            assertFalse(miss.snapshot().hasCandidates());
            assertEquals(DEFERRED, coordinator.lookupOrEnqueue(store, uuid(503), missRequest, NOW_MS + 100L).status());
        }
    }

    @Test
    void searchExceptionStoresShortMissAndAllowsRetry() {
        try (TestEntityComponentStore store = newStore()) {
            coordinator.clear(store);
            UUID npc = uuid(600);
            NeedsResourceSearchCoordinator.Request request = request(600);
            CountingExecutor executor = new CountingExecutor(hitSnapshot(1_500L));
            executor.failNext = true;

            coordinator.lookupOrEnqueue(store, npc, request, NOW_MS);
            assertEquals(1, coordinator.processOne(store, 1L, NOW_MS, executor));
            assertEquals(1, executor.calls());
            assertEquals(0, coordinator.pendingCountForTests(store));

            long retryAt = NOW_MS + new NeedsResourceSearchAdmissionPolicy().deferredTtlMs(npc);
            assertEquals(MISS, coordinator.lookupOrEnqueue(store, npc, request, retryAt - 1L).status());
            assertEquals(DEFERRED, coordinator.lookupOrEnqueue(store, npc, request, retryAt).status());
            assertEquals(1, coordinator.pendingCountForTests(store));
            assertEquals(1, coordinator.processOne(store, 8L, retryAt, executor));
            assertEquals(2, executor.calls());
            assertEquals(HIT, coordinator.lookupOrEnqueue(store, npc, request, retryAt + 1L).status());
        }
    }

    @Test
    void nullSearchResultConsumesTickBudgetAndCanRetryAfterShortMiss() {
        try (TestEntityComponentStore store = newStore()) {
            coordinator.clear(store);
            UUID firstNpc = uuid(650);
            UUID secondNpc = uuid(651);
            NeedsResourceSearchCoordinator.Request firstRequest = request(650);
            NeedsResourceSearchCoordinator.Request secondRequest = request(651);
            CountingExecutor executor = new CountingExecutor(hitSnapshot(1_500L));
            executor.returnNullNext = true;

            coordinator.lookupOrEnqueue(store, firstNpc, firstRequest, NOW_MS);
            coordinator.lookupOrEnqueue(store, secondNpc, secondRequest, NOW_MS);

            assertEquals(1, coordinator.processOne(store, -8L, NOW_MS, executor));
            assertEquals(0, coordinator.processOne(store, -8L, NOW_MS, executor));
            assertEquals(1, executor.calls());
            assertEquals(1, coordinator.pendingCountForTests(store));

            assertEquals(1, coordinator.processOne(store, -7L, NOW_MS, executor));
            long retryAt = NOW_MS + new NeedsResourceSearchAdmissionPolicy().deferredTtlMs(firstNpc);
            assertEquals(MISS, coordinator.lookupOrEnqueue(store, firstNpc, firstRequest, retryAt - 1L).status());
            assertEquals(DEFERRED, coordinator.lookupOrEnqueue(store, firstNpc, firstRequest, retryAt).status());
            assertEquals(1, coordinator.processOne(store, 0L, retryAt, executor));
            assertEquals(HIT, coordinator.lookupOrEnqueue(store, firstNpc, firstRequest, retryAt + 1L).status());
        }
    }

    @Test
    void foodIdentityUsesCollisionSafeNormalizedAreaKeys() {
        try (TestEntityComponentStore store = newStore()) {
            coordinator.clear(store);
            NeedsResourceSearchCoordinator.Request aa = request(
                    "food_container", 900, List.of("Aa"));
            NeedsResourceSearchCoordinator.Request bb = request(
                    "food_container", 900, List.of("BB"));
            CountingExecutor executor = new CountingExecutor(hitSnapshot(1_500L));

            coordinator.lookupOrEnqueue(store, uuid(900), aa, NOW_MS);
            coordinator.processOne(store, 8L, NOW_MS, executor);

            assertEquals(DEFERRED, coordinator.lookupOrEnqueue(store, uuid(901), bb, NOW_MS).status());
            assertEquals(1, coordinator.pendingCountForTests(store));
        }
    }

    @Test
    void equivalentFoodIdsSharePendingAndCachedResults() {
        try (TestEntityComponentStore store = newStore()) {
            coordinator.clear(store);
            NeedsResourceSearchCoordinator.Request first = request(
                    "food_container", 901, List.of(" Food_Beef ", "FOOD_WHEAT", "food_beef"));
            NeedsResourceSearchCoordinator.Request equivalent = request(
                    "food_container", 901, List.of("food_wheat", "Food_Beef"));
            CountingExecutor executor = new CountingExecutor(hitSnapshot(1_500L));

            assertEquals(DEFERRED, coordinator.lookupOrEnqueue(store, uuid(902), first, NOW_MS).status());
            assertEquals(DEFERRED, coordinator.lookupOrEnqueue(store, uuid(903), equivalent, NOW_MS).status());
            assertEquals(1, coordinator.pendingCountForTests(store));
            coordinator.processOne(store, 8L, NOW_MS, executor);

            assertEquals(HIT, coordinator.lookupOrEnqueue(store, uuid(904), equivalent, NOW_MS + 1L).status());
            assertEquals(1, executor.calls());
        }
    }

    @Test
    void changingItemIdListCannotSplitRequestAndAreaKeyIdentity() {
        try (TestEntityComponentStore store = newStore()) {
            coordinator.clear(store);
            ChangingItemIds changingItemIds = new ChangingItemIds("Food_Beef", "Food_Wheat");
            NeedsResourceSearchCoordinator.Request request = request(
                    "food_container", 902, changingItemIds);
            CountingExecutor executor = new CountingExecutor(hitSnapshot(1_500L));

            assertEquals(List.of("food_beef"), request.itemIds());
            assertEquals(request.itemIds(), request.areaKey().normalizedItemIds());
            coordinator.lookupOrEnqueue(store, uuid(905), request, NOW_MS);
            assertEquals(1, coordinator.processOne(store, 8L, NOW_MS, executor));
            assertEquals(List.of("food_beef"), executor.requests().get(0).itemIds());

            NeedsResourceSearchCoordinator.Request equivalent = request(
                    "food_container", 902, List.of("FOOD_BEEF"));
            NeedsResourceSearchCoordinator.Request different = request(
                    "food_container", 902, List.of("FOOD_WHEAT"));
            assertEquals(HIT, coordinator.lookupOrEnqueue(
                    store, uuid(906), equivalent, NOW_MS + 1L).status());
            assertEquals(DEFERRED, coordinator.lookupOrEnqueue(
                    store, uuid(907), different, NOW_MS + 1L).status());
        }
    }

    @Test
    void clearRemovesOnlyTheSelectedStoreState() {
        try (TestEntityComponentStore firstStore = newStore();
             TestEntityComponentStore secondStore = newStore()) {
            coordinator.clear(firstStore);
            coordinator.clear(secondStore);
            NeedsResourceSearchCoordinator.Request request = request(700);
            CountingExecutor executor = new CountingExecutor(hitSnapshot(1_500L));
            coordinator.lookupOrEnqueue(firstStore, uuid(700), request, NOW_MS);
            coordinator.lookupOrEnqueue(secondStore, uuid(701), request, NOW_MS);
            assertEquals(1, coordinator.processOne(firstStore, 8L, NOW_MS, executor));
            assertEquals(HIT, coordinator.lookupOrEnqueue(
                    firstStore, uuid(702), request, NOW_MS + 1L).status());

            coordinator.clear(firstStore);

            assertEquals(0, coordinator.pendingCountForTests(firstStore));
            assertEquals(1, coordinator.pendingCountForTests(secondStore));
            assertEquals(DEFERRED, coordinator.lookupOrEnqueue(
                    firstStore, uuid(703), request, NOW_MS + 1L).status());
            assertEquals(1, coordinator.pendingCountForTests(firstStore));
        }
    }

    @Test
    void requestCopiesItemIdsAndDoesNotRetainMutableInput() {
        try (TestEntityComponentStore store = newStore()) {
            coordinator.clear(store);
            List<String> itemIds = new ArrayList<>(List.of("Food_Beef", "Food_Wheat"));
            NeedsResourceSearchCoordinator.Request request = request(800, itemIds);
            itemIds.clear();
            assertEquals(List.of("food_beef", "food_wheat"), request.itemIds());

            coordinator.lookupOrEnqueue(store, uuid(800), request, NOW_MS);
            assertEquals(1, coordinator.pendingCountForTests(store));
        }
    }

    private static TestEntityComponentStore newStore() {
        return new TestEntityComponentStore(new EntityStore(null));
    }

    private static UUID uuid(long leastSignificantBits) {
        return new UUID(0L, leastSignificantBits);
    }

    private static NeedsResourceSearchCoordinator.Request request(int index) {
        return request(index, List.of());
    }

    private static NeedsResourceSearchCoordinator.Request request(int index, List<String> itemIds) {
        return request("water", index, itemIds);
    }

    private static NeedsResourceSearchCoordinator.Request request(
            String resourceKind, int index, List<String> itemIds) {
        return NeedsResourceSearchCoordinator.Request.forArea(
                resourceKind,
                "test-world",
                index * 4.0,
                64.0,
                0.0,
                16.0,
                1,
                3.0,
                itemIds
        );
    }

    private static NeedsResourceCandidates.Snapshot hitSnapshot(long ttlMs) {
        return new NeedsResourceCandidates.Snapshot(
                List.of(new NeedsResourceCandidates.Candidate(1, 64, 1, 2.0)),
                true,
                true,
                ttlMs
        );
    }

    private static NeedsResourceCandidates.Snapshot missSnapshot(long ttlMs) {
        return new NeedsResourceCandidates.Snapshot(List.of(), false, false, ttlMs);
    }

    private static final class CountingExecutor implements NeedsResourceSearchCoordinator.SearchExecutor {
        private final Deque<NeedsResourceCandidates.Snapshot> results = new ArrayDeque<>();
        private final List<NeedsResourceSearchCoordinator.Request> requests = new ArrayList<>();
        private final List<List<UUID>> waiters = new ArrayList<>();
        private boolean failNext;
        private boolean returnNullNext;

        private CountingExecutor(NeedsResourceCandidates.Snapshot... snapshots) {
            for (NeedsResourceCandidates.Snapshot snapshot : snapshots) {
                results.add(snapshot);
            }
        }

        @Override
        public NeedsResourceCandidates.Snapshot search(
                Store<EntityStore> store,
                NeedsResourceSearchCoordinator.Request request,
                List<UUID> waiterIds) {
            requests.add(request);
            waiters.add(List.copyOf(waiterIds));
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("test search failure");
            }
            if (returnNullNext) {
                returnNullNext = false;
                return null;
            }
            NeedsResourceCandidates.Snapshot result = results.peekFirst();
            if (results.size() > 1) {
                results.removeFirst();
            }
            return result;
        }

        private int calls() {
            return requests.size();
        }

        private List<NeedsResourceSearchCoordinator.Request> requests() {
            return List.copyOf(requests);
        }

        private List<List<UUID>> waiters() {
            return List.copyOf(waiters);
        }
    }

    private static final class ChangingItemIds extends AbstractList<String> {
        private final String firstValue;
        private final String secondValue;
        private int reads;

        private ChangingItemIds(String firstValue, String secondValue) {
            this.firstValue = firstValue;
            this.secondValue = secondValue;
        }

        @Override
        public String get(int index) {
            if (index != 0) {
                throw new IndexOutOfBoundsException(index);
            }
            return reads++ == 0 ? firstValue : secondValue;
        }

        @Override
        public int size() {
            return 1;
        }
    }
}
