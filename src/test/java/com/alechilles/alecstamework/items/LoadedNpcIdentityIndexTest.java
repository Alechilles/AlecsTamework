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
    private static final UUID OTHER_UUID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final LoadedNpcIdentityIndex.ProjectionKey PROJECTION_KEY =
            new LoadedNpcIdentityIndex.ProjectionKey(
                    "profile-a",
                    "operation-a",
                    "MANAGED_COOP_RELEASE",
                    "slot-a",
                    NPC_UUID,
                    1L
            );

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
    void exactProjectionProbeSharesTheUuidCompletenessBarrier() {
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();

        assertEquals(
                LoadedNpcIdentityIndex.ProjectionProbeStatus.UNKNOWN,
                index.probeProjection(PROJECTION_KEY).status()
        );

        index.markInitializationComplete();

        LoadedNpcIdentityIndex.ProjectionProbe probe = index.probeProjection(PROJECTION_KEY);
        assertEquals(LoadedNpcIdentityIndex.ProjectionProbeStatus.ABSENT, probe.status());
        assertTrue(probe.matches().isEmpty());
        assertThrows(
                UnsupportedOperationException.class,
                () -> probe.matches().add(observation(NPC_UUID, WORLD_A, PROJECTION_KEY))
        );
    }

    @Test
    void duplicateProjectionMarkersRemainDistinctWithinOneStore() {
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();
        LoadedNpcIdentityIndex.LoadedNpcObservation first = observation(
                NPC_UUID, WORLD_A, PROJECTION_KEY
        );
        LoadedNpcIdentityIndex.LoadedNpcObservation second = observation(
                OTHER_UUID, WORLD_A, PROJECTION_KEY
        );

        index.recordAdded(second);
        index.recordAdded(first);
        index.recordAdded(first);

        LoadedNpcIdentityIndex.ProjectionProbe duplicate = index.probeProjection(PROJECTION_KEY);
        assertEquals(
                LoadedNpcIdentityIndex.ProjectionProbeStatus.MULTIPLE_MATCHES,
                duplicate.status()
        );
        assertEquals(List.of(NPC_UUID, OTHER_UUID), duplicate.matches().stream()
                .map(LoadedNpcIdentityIndex.LoadedNpcObservation::componentUuid)
                .toList());
        assertEquals(
                LoadedNpcIdentityIndex.ProbeStatus.ONE_LOCATION,
                index.probe(NPC_UUID).status()
        );

        index.recordRemoved(new LoadedNpcIdentityIndex.LoadedNpcObservation(
                NPC_UUID, NPC_UUID, WORLD_A, null
        ));

        LoadedNpcIdentityIndex.ProjectionProbe remaining = index.probeProjection(PROJECTION_KEY);
        assertEquals(LoadedNpcIdentityIndex.ProjectionProbeStatus.ONE_MATCH, remaining.status());
        assertEquals(OTHER_UUID, remaining.matches().getFirst().componentUuid());
    }

    @Test
    void markerRefreshReplacesTheSameEntityInsteadOfInventingAnotherMatch() {
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();
        LoadedNpcIdentityIndex.ProjectionKey replacementKey =
                new LoadedNpcIdentityIndex.ProjectionKey(
                        "profile-a", "operation-b", "RECOVERY", null, null, 0L
                );
        index.recordAdded(observation(NPC_UUID, WORLD_A, PROJECTION_KEY));

        index.recordAdded(observation(NPC_UUID, WORLD_A, replacementKey));
        index.markInitializationComplete();

        assertEquals(
                LoadedNpcIdentityIndex.ProjectionProbeStatus.ABSENT,
                index.probeProjection(PROJECTION_KEY).status()
        );
        assertEquals(
                LoadedNpcIdentityIndex.ProjectionProbeStatus.ONE_MATCH,
                index.probeProjection(replacementKey).status()
        );
    }

    @Test
    void locationReplacementAtomicallyReconcilesProjectionObservations() {
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();
        index.recordAdded(observation(NPC_UUID, WORLD_A, PROJECTION_KEY));

        index.replaceLocationObservations(
                WORLD_A,
                List.of(observation(OTHER_UUID, WORLD_A, PROJECTION_KEY))
        );
        index.markInitializationComplete();

        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.ABSENT, index.probe(NPC_UUID).status());
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.ONE_LOCATION, index.probe(OTHER_UUID).status());
        assertEquals(
                OTHER_UUID,
                index.probeProjection(PROJECTION_KEY).matches().getFirst().componentUuid()
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> index.replaceLocationObservations(
                        WORLD_A,
                        List.of(observation(NPC_UUID, WORLD_B, PROJECTION_KEY))
                )
        );
    }

    @Test
    void everyLocationMutationAndCompletenessTransitionAdvancesSnapshotRevision() {
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();
        long revision = index.snapshot().mutationRevision();

        index.recordAdded(NPC_UUID, WORLD_A);
        assertEquals(++revision, index.snapshot().mutationRevision());
        index.recordRemoved(NPC_UUID, WORLD_A);
        assertEquals(++revision, index.snapshot().mutationRevision());
        index.recordAdded(observation(NPC_UUID, WORLD_A, PROJECTION_KEY));
        assertEquals(++revision, index.snapshot().mutationRevision());
        index.recordRemoved(observation(NPC_UUID, WORLD_A, PROJECTION_KEY));
        assertEquals(++revision, index.snapshot().mutationRevision());
        index.clearLocation(WORLD_A);
        assertEquals(++revision, index.snapshot().mutationRevision());
        index.replaceLocation(WORLD_A, List.of(NPC_UUID));
        assertEquals(++revision, index.snapshot().mutationRevision());
        index.replaceLocationObservations(
                WORLD_A, List.of(observation(NPC_UUID, WORLD_A, PROJECTION_KEY))
        );
        assertEquals(++revision, index.snapshot().mutationRevision());
        long locationRevision = index.locationMutationRevision(WORLD_A);
        assertTrue(index.replaceLocationObservationsIfUnchanged(
                WORLD_A, List.of(observation(NPC_UUID, WORLD_A, PROJECTION_KEY)), locationRevision
        ));
        assertEquals(++revision, index.snapshot().mutationRevision());

        index.markInitializationComplete();
        assertEquals(++revision, index.snapshot().mutationRevision());
        index.markInitializationComplete();
        assertEquals(revision, index.snapshot().mutationRevision());
        index.markInitializationIncomplete();
        assertEquals(++revision, index.snapshot().mutationRevision());
        index.markInitializationIncomplete();
        assertEquals(revision, index.snapshot().mutationRevision());
    }

    @Test
    void atomicSnapshotIsImmutableCompleteAndDeterministicallyOrdered() {
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();
        index.recordAdded(observation(OTHER_UUID, WORLD_B, PROJECTION_KEY));
        index.recordAdded(observation(NPC_UUID, WORLD_A, PROJECTION_KEY));

        LoadedNpcIdentitySnapshot incomplete = index.snapshot();

        assertFalse(incomplete.initializationComplete());
        assertEquals(List.of(NPC_UUID, OTHER_UUID), incomplete.observations().stream()
                .map(LoadedNpcIdentityIndex.LoadedNpcObservation::componentUuid)
                .toList());
        assertThrows(UnsupportedOperationException.class,
                () -> incomplete.observations().add(observation(NPC_UUID, WORLD_A, PROJECTION_KEY)));

        index.markInitializationComplete();

        assertFalse(index.isMutationRevisionCurrent(incomplete.mutationRevision()));
        assertTrue(index.snapshot().initializationComplete());
    }

    @Test
    void projectionRecordsRejectIncompleteOrInconsistentEvidence() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new LoadedNpcIdentityIndex.ProjectionKey(
                        " ", "operation-a", "RECOVERY", null, null, 0L
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new LoadedNpcIdentityIndex.ProjectionKey(
                        "profile-a", "operation-a", "RECOVERY", null, null, -1L
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new LoadedNpcIdentityIndex.LoadedNpcObservation(
                        null, null, WORLD_A, null
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new LoadedNpcIdentityIndex.ProjectionProbe(
                        PROJECTION_KEY,
                        LoadedNpcIdentityIndex.ProjectionProbeStatus.ONE_MATCH,
                        List.of()
                )
        );
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

    private static LoadedNpcIdentityIndex.LoadedNpcObservation observation(
            UUID componentUuid,
            LoadedNpcIdentityIndex.Location location,
            LoadedNpcIdentityIndex.ProjectionKey projectionKey) {
        return new LoadedNpcIdentityIndex.LoadedNpcObservation(
                componentUuid, componentUuid, location, projectionKey
        );
    }
}
