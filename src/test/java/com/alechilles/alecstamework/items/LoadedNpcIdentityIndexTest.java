package com.alechilles.alecstamework.items;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for loaded-NPC identity completeness and duplicate evidence. */
class LoadedNpcIdentityIndexTest {
    private static final UUID NPC_UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final LoadedNpcIdentityIndex.Location WORLD_A =
            new LoadedNpcIdentityIndex.Location("world-a", "store-a");
    private static final LoadedNpcIdentityIndex.Location WORLD_B =
            new LoadedNpcIdentityIndex.Location("world-b", "store-b");

    @Test
    void absenceIsUnknownUntilStoreBootstrapCompletes() {
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();

        LoadedNpcIdentityIndex.Probe unknown = index.probe(NPC_UUID);

        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.UNKNOWN, unknown.status());
        assertFalse(unknown.isKnownLive());
        assertEquals(0, unknown.locationCount());

        index.recordAdded(NPC_UUID, WORLD_A);
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.ONE_LOCATION, index.probe(NPC_UUID).status());
        index.recordRemoved(NPC_UUID, WORLD_A);
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.UNKNOWN, index.probe(NPC_UUID).status());

        index.recordAdded(
                UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
                WORLD_A
        );
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.UNKNOWN, index.probe(NPC_UUID).status());

        index.markInitializationComplete();

        LoadedNpcIdentityIndex.Probe absent = index.probe(NPC_UUID);
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.ABSENT, absent.status());
        assertFalse(absent.isKnownLive());
    }

    @Test
    void duplicateAddAndRemoveReplayIsIdempotent() {
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();

        index.recordAdded(NPC_UUID, WORLD_A);
        index.recordAdded(NPC_UUID, WORLD_A);
        index.markInitializationComplete();

        LoadedNpcIdentityIndex.Probe present = index.probe(NPC_UUID);
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.ONE_LOCATION, present.status());
        assertEquals(1, present.locationCount());
        assertEquals(List.of("world-a [store-a]"), present.locationNames());

        index.recordRemoved(NPC_UUID, WORLD_A);
        index.recordRemoved(NPC_UUID, WORLD_A);

        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.ABSENT, index.probe(NPC_UUID).status());
    }

    @Test
    void sameUuidInTwoWorldStoresIsReportedAsConflict() {
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();

        index.recordAdded(NPC_UUID, WORLD_B);
        index.recordAdded(NPC_UUID, WORLD_A);

        LoadedNpcIdentityIndex.Probe probe = index.probe(NPC_UUID);
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.MULTIPLE_LOCATIONS, probe.status());
        assertTrue(probe.isKnownLive());
        assertTrue(probe.hasLocationConflict());
        assertEquals(2, probe.locationCount());
        assertEquals(2, probe.worldCount());
        assertEquals(List.of("world-a", "world-b"), probe.worldNames());
        assertEquals(List.of("world-a [store-a]", "world-b [store-b]"), probe.locationNames());
        assertThrows(UnsupportedOperationException.class, () -> probe.locations().add(WORLD_A));
        assertFalse(index.isInitializationComplete());
    }

    @Test
    void removalDropsOnlyExactWorldStoreEvidence() {
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();
        LoadedNpcIdentityIndex.Location sameWorldOtherStore =
                new LoadedNpcIdentityIndex.Location("world-a", "store-b");
        index.recordAdded(NPC_UUID, WORLD_A);
        index.recordAdded(NPC_UUID, sameWorldOtherStore);
        index.markInitializationComplete();

        index.recordRemoved(NPC_UUID, new LoadedNpcIdentityIndex.Location("world-a", "store-missing"));
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.MULTIPLE_LOCATIONS, index.probe(NPC_UUID).status());

        index.recordRemoved(NPC_UUID, WORLD_A);
        LoadedNpcIdentityIndex.Probe remaining = index.probe(NPC_UUID);
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.ONE_LOCATION, remaining.status());
        assertEquals(List.of("world-a [store-b]"), remaining.locationNames());
        assertEquals(1, remaining.worldCount());
    }

    @Test
    void incompleteBarrierRevokesAbsenceAndClearLocationDropsOnlyThatStore() {
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();
        UUID otherNpc = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        index.recordAdded(NPC_UUID, WORLD_A);
        index.recordAdded(otherNpc, WORLD_A);
        index.recordAdded(otherNpc, WORLD_B);
        index.markInitializationComplete();

        index.markInitializationIncomplete();
        assertEquals(
                LoadedNpcIdentityIndex.ProbeStatus.UNKNOWN,
                index.probe(UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")).status()
        );
        index.clearLocation(WORLD_A);

        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.UNKNOWN, index.probe(NPC_UUID).status());
        assertEquals(List.of("world-b [store-b]"), index.probe(otherNpc).locationNames());
    }

    @Test
    void concurrentReplayProducesDeterministicEvidence() throws Exception {
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            runConcurrently(executor, 8, () -> {
                for (int iteration = 0; iteration < 2_000; iteration++) {
                    index.recordAdded(NPC_UUID, (iteration & 1) == 0 ? WORLD_A : WORLD_B);
                    index.probe(NPC_UUID);
                }
            });

            LoadedNpcIdentityIndex.Probe afterAdds = index.probe(NPC_UUID);
            assertEquals(LoadedNpcIdentityIndex.ProbeStatus.MULTIPLE_LOCATIONS, afterAdds.status());
            assertEquals(List.of("world-a [store-a]", "world-b [store-b]"), afterAdds.locationNames());

            runConcurrently(executor, 8, () -> {
                for (int iteration = 0; iteration < 2_000; iteration++) {
                    index.recordRemoved(NPC_UUID, WORLD_A);
                    index.recordAdded(NPC_UUID, WORLD_B);
                }
            });

            LoadedNpcIdentityIndex.Probe afterRemoves = index.probe(NPC_UUID);
            assertEquals(LoadedNpcIdentityIndex.ProbeStatus.ONE_LOCATION, afterRemoves.status());
            assertEquals(List.of("world-b [store-b]"), afterRemoves.locationNames());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void runConcurrently(ExecutorService executor,
                                        int taskCount,
                                        Runnable action) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int task = 0; task < taskCount; task++) {
            futures.add(executor.submit(() -> {
                start.await();
                action.run();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
    }
}
