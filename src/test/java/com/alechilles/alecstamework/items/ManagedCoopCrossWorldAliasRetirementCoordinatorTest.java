package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.Location;
import com.alechilles.alecstamework.items.ManagedCoopCrossWorldAliasRetirementCoordinator.ProjectionObservation;
import com.alechilles.alecstamework.items.ManagedCoopCrossWorldAliasRetirementCoordinator.RequestStatus;
import com.alechilles.alecstamework.items.ManagedCoopCrossWorldAliasRetirementCoordinator.RetirementEvent;
import com.alechilles.alecstamework.items.ManagedCoopCrossWorldAliasRetirementCoordinator.RetirementRequest;
import com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Action;
import com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Decision;
import com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Observation;
import com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Two-world proof, coalescing, and invalidation coverage without live ECS objects. */
class ManagedCoopCrossWorldAliasRetirementCoordinatorTest {
    private static final UUID STALE = new UUID(0L, 1L);
    private static final UUID RETAINED = new UUID(0L, 2L);
    private static final UUID STALE_TWO = new UUID(0L, 3L);
    private static final UUID RETAINED_TWO = new UUID(0L, 4L);
    private static final String PROFILE = "profile-a";
    private static final Location STALE_LOCATION = new Location("world-a", "store-a");
    private static final Location RETAINED_LOCATION = new Location("world-b", "store-b");
    private static final Location STALE_TWO_LOCATION = new Location("world-c", "store-c");
    private static final Location RETAINED_TWO_LOCATION = new Location("world-d", "store-d");

    @Test
    void exactTwoWorldProofRetiresOnceAndCoalescesDuplicateRequests() {
        Fixture fixture = fixture();
        RetirementRequest request = request();

        assertEquals(RequestStatus.SCHEDULED, fixture.coordinator().submit(request));
        assertEquals(RequestStatus.COALESCED, fixture.coordinator().submit(request));
        assertEquals(1, fixture.runtime().queued());
        assertEquals(1, fixture.coordinator().pendingCount());

        fixture.runtime().runNext();
        assertEquals(1, fixture.runtime().queued(), "retained proof schedules the stale-world hop");
        assertTrue(fixture.runtime().retired().isEmpty());

        fixture.runtime().runNext();
        assertEquals(List.of(STALE), fixture.runtime().retired());
        assertEquals(0, fixture.coordinator().pendingCount());
        assertEquals(1, fixture.events().size());
        assertEquals(RETAINED_LOCATION, fixture.events().getFirst().retainedLocation());
    }

    @Test
    void ambiguousOrSameStoreIdentityEvidenceNeverSchedules() {
        Fixture fixture = fixture();
        fixture.identities().recordAdded(STALE, new Location("world-c", "store-c"));

        assertEquals(RequestStatus.DEFERRED, fixture.coordinator().submit(request()));
        assertEquals(0, fixture.runtime().queued());

        LoadedNpcIdentityIndex sameStore = new LoadedNpcIdentityIndex();
        sameStore.recordAdded(STALE, STALE_LOCATION);
        sameStore.recordAdded(RETAINED, STALE_LOCATION);
        FakeRuntime runtime = runtime();
        ManagedCoopCrossWorldAliasRetirementCoordinator coordinator = coordinator(
                sameStore, runtime, new AtomicBoolean(true), new ArrayList<>());

        assertEquals(RequestStatus.SAME_STORE, coordinator.submit(request()));
        assertEquals(0, runtime.queued());
    }

    @Test
    void retainedOrStalePolicyMismatchIsNonDestructive() {
        Fixture retainedMismatch = fixture();
        retainedMismatch.allowExact().set(false);
        assertEquals(RequestStatus.SCHEDULED,
                retainedMismatch.coordinator().submit(request()));
        retainedMismatch.runtime().runNext();
        assertEquals(0, retainedMismatch.runtime().queued());
        assertTrue(retainedMismatch.runtime().retired().isEmpty());

        Fixture staleMismatch = fixture();
        assertEquals(RequestStatus.SCHEDULED, staleMismatch.coordinator().submit(request()));
        staleMismatch.runtime().runNext();
        staleMismatch.allowExact().set(false);
        staleMismatch.runtime().runNext();
        assertTrue(staleMismatch.runtime().retired().isEmpty());
        assertEquals(0, staleMismatch.coordinator().pendingCount());
    }

    @Test
    void retainedLocationChangeAfterProofInvalidatesTheStaleHop() {
        Fixture fixture = fixture();
        assertEquals(RequestStatus.SCHEDULED, fixture.coordinator().submit(request()));
        fixture.runtime().runNext();

        fixture.identities().recordRemoved(RETAINED, RETAINED_LOCATION);
        fixture.identities().recordAdded(RETAINED, new Location("world-c", "store-c"));
        fixture.runtime().runNext();

        assertTrue(fixture.runtime().retired().isEmpty());
        assertEquals(0, fixture.coordinator().pendingCount());
    }

    @Test
    void lifecycleInvalidationCancelsQueuedProofAndCloseRejectsNewWork() {
        Fixture fixture = fixture();
        assertEquals(RequestStatus.SCHEDULED, fixture.coordinator().submit(request()));

        fixture.coordinator().invalidateWorld("world-b");
        assertEquals(0, fixture.coordinator().pendingCount());
        fixture.runtime().runNext();
        assertEquals(0, fixture.runtime().queued());
        assertTrue(fixture.runtime().retired().isEmpty());

        assertEquals(RequestStatus.SCHEDULED, fixture.coordinator().submit(request()));
        fixture.coordinator().invalidateAll();
        fixture.runtime().runNext();
        assertTrue(fixture.runtime().retired().isEmpty());

        fixture.coordinator().close();
        assertEquals(RequestStatus.CLOSED, fixture.coordinator().submit(request()));
    }

    @Test
    void npcAndWorldInvalidationAreSurgicalAndUnrelatedRequestStillConverges() {
        LoadedNpcIdentityIndex identities = new LoadedNpcIdentityIndex();
        identities.recordAdded(STALE, STALE_LOCATION);
        identities.recordAdded(RETAINED, RETAINED_LOCATION);
        identities.recordAdded(STALE_TWO, STALE_TWO_LOCATION);
        identities.recordAdded(RETAINED_TWO, RETAINED_TWO_LOCATION);
        FakeRuntime runtime = runtime();
        runtime.put(STALE_TWO_LOCATION, Observation.of(STALE_TWO, null));
        runtime.put(RETAINED_TWO_LOCATION, Observation.of(RETAINED_TWO, null));
        ManagedCoopCrossWorldAliasRetirementCoordinator coordinator =
                new ManagedCoopCrossWorldAliasRetirementCoordinator(
                        identities,
                        ManagedCoopCrossWorldAliasRetirementCoordinatorTest::twoPairDecision,
                        runtime,
                        ignored -> {
                        });

        assertEquals(RequestStatus.SCHEDULED, coordinator.submit(request()));
        assertEquals(RequestStatus.SCHEDULED, coordinator.submit(new RetirementRequest(
                STALE_TWO, RETAINED_TWO, "profile-b", null)));
        coordinator.invalidateNpc(new UUID(0L, 99L));
        coordinator.invalidateWorld("unrelated-world");
        assertEquals(2, coordinator.pendingCount());

        coordinator.invalidateNpc(STALE);
        assertEquals(1, coordinator.pendingCount());
        assertEquals(RequestStatus.SCHEDULED, coordinator.submit(request()));
        assertEquals(2, coordinator.pendingCount());
        coordinator.invalidateWorld(STALE_LOCATION.worldName());
        assertEquals(1, coordinator.pendingCount());
        runtime.runAll();

        assertEquals(List.of(STALE_TWO), runtime.retired());
        assertEquals(0, coordinator.pendingCount());
    }

    private static Fixture fixture() {
        LoadedNpcIdentityIndex identities = new LoadedNpcIdentityIndex();
        identities.recordAdded(STALE, STALE_LOCATION);
        identities.recordAdded(RETAINED, RETAINED_LOCATION);
        FakeRuntime runtime = runtime();
        AtomicBoolean allowExact = new AtomicBoolean(true);
        ArrayList<RetirementEvent> events = new ArrayList<>();
        return new Fixture(
                coordinator(identities, runtime, allowExact, events),
                identities,
                runtime,
                allowExact,
                events);
    }

    private static ManagedCoopCrossWorldAliasRetirementCoordinator coordinator(
            LoadedNpcIdentityIndex identities,
            FakeRuntime runtime,
            AtomicBoolean allowExact,
            List<RetirementEvent> events) {
        return new ManagedCoopCrossWorldAliasRetirementCoordinator(
                identities,
                observation -> decision(observation, allowExact.get()),
                runtime,
                events::add);
    }

    private static FakeRuntime runtime() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.put(STALE_LOCATION, Observation.of(STALE, null));
        runtime.put(RETAINED_LOCATION, Observation.of(RETAINED, null));
        return runtime;
    }

    private static Decision decision(Observation observation, boolean exact) {
        if (observation.npcUuid().equals(RETAINED)) {
            return new Decision(
                    exact ? Action.ALLOW : Action.DEFER,
                    exact ? Reason.DEPLOYED_RELEASE_PROJECTION : Reason.INVALID_DEPLOYED_MARKER,
                    exact ? PROFILE : "profile-b",
                    null,
                    null,
                    exact ? STALE : null);
        }
        return new Decision(
                exact ? Action.SUPPRESS : Action.DEFER,
                exact ? Reason.HISTORICAL_RESIDENT_ALIAS : Reason.DEPLOYED_IDENTITY_MISMATCH,
                exact ? PROFILE : "profile-b",
                null,
                exact ? RETAINED : null,
                null);
    }

    private static Decision twoPairDecision(Observation observation) {
        if (observation.npcUuid().equals(RETAINED_TWO)) {
            return new Decision(
                    Action.ALLOW, Reason.DEPLOYED_RELEASE_PROJECTION,
                    "profile-b", null, null, STALE_TWO);
        }
        if (observation.npcUuid().equals(STALE_TWO)) {
            return new Decision(
                    Action.SUPPRESS, Reason.HISTORICAL_RESIDENT_ALIAS,
                    "profile-b", null, RETAINED_TWO, null);
        }
        return decision(observation, true);
    }

    private static RetirementRequest request() {
        return new RetirementRequest(STALE, RETAINED, PROFILE, null);
    }

    private record Fixture(ManagedCoopCrossWorldAliasRetirementCoordinator coordinator,
                           LoadedNpcIdentityIndex identities,
                           FakeRuntime runtime,
                           AtomicBoolean allowExact,
                           List<RetirementEvent> events) {
    }

    private static final class FakeRuntime
            implements ManagedCoopCrossWorldAliasRetirementCoordinator.RuntimeGateway {
        private final Map<Location, Map<UUID, Observation>> observations = new HashMap<>();
        private final ArrayDeque<Scheduled> scheduled = new ArrayDeque<>();
        private final ArrayList<UUID> retired = new ArrayList<>();

        private void put(Location location, Observation observation) {
            observations.computeIfAbsent(location, ignored -> new HashMap<>())
                    .put(observation.npcUuid(), observation);
        }

        @Override
        public boolean execute(Location location, Runnable action) {
            scheduled.addLast(new Scheduled(location, action));
            return true;
        }

        @Override
        public ProjectionObservation observe(Location location, UUID npcUuid) {
            Observation observation = observations.getOrDefault(location, Map.of()).get(npcUuid);
            return observation != null ? new ProjectionObservation(observation) : null;
        }

        @Override
        public boolean markToDespawn(Location location, Observation observation) {
            Observation current = observations.getOrDefault(location, Map.of())
                    .get(observation.npcUuid());
            if (!observation.equals(current)) {
                return false;
            }
            retired.add(observation.npcUuid());
            return true;
        }

        private void runNext() {
            Scheduled next = scheduled.removeFirst();
            next.action().run();
        }

        private int queued() {
            return scheduled.size();
        }

        private void runAll() {
            int remaining = 20;
            while (!scheduled.isEmpty() && remaining-- > 0) {
                runNext();
            }
            assertTrue(scheduled.isEmpty(), "scheduled proof pipeline did not converge");
        }

        private List<UUID> retired() {
            return List.copyOf(retired);
        }
    }

    private record Scheduled(Location location, Runnable action) {
    }
}
