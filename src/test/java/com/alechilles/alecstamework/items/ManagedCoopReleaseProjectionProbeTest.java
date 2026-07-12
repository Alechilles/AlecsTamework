package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.LoadedNpcObservation;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.Location;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.Probe;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.ProbeStatus;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.ProjectionKey;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.ProjectionProbe;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.ProjectionProbeStatus;
import com.alechilles.alecstamework.items.ManagedCoopReleaseProjectionProbe.Outcome;
import com.alechilles.alecstamework.items.ManagedCoopReleaseProjectionProbe.ProjectionRead;
import com.alechilles.alecstamework.items.ManagedCoopReleaseProjectionProbe.Result;
import com.alechilles.alecstamework.items.ManagedCoopReleaseRuntimeAdapter.LiveIdentityRequest;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedCoopReleaseProjectionProbeTest {
    private static final UUID SOURCE = uuid(1);
    private static final UUID PLANNED = uuid(2);
    private static final UUID ALTERNATE = uuid(3);
    private ComponentRegistry<EntityStore> registry;
    private Store<EntityStore> store;
    private Location location;

    @BeforeEach
    void setUp() {
        registry = new ComponentRegistry<>();
        store = registry.addStore(null, null);
        location = LoadedNpcLocationResolver.resolve(store);
    }

    @AfterEach
    void tearDown() {
        registry.removeStore(store);
        registry.shutdown();
    }

    @Test
    void exactIndexedAndLiveMarkerIsPresent() {
        ProjectionKey key = key();
        ManagedCoopReleaseProjectionProbe probe = probe(
                planned(ProbeStatus.ONE_LOCATION, location),
                markers(key, ProjectionProbeStatus.ONE_MATCH,
                        observation(PLANNED, location, key)),
                ProjectionRead.found(marker()));

        Result result = probe.probe(request(), store);

        assertEquals(Outcome.PRESENT, result.outcome());
        assertEquals(PLANNED, result.observedUuid());
    }

    @Test
    void completeUuidAndMarkerAbsenceIsAbsentWithoutEcsRead() {
        AtomicInteger reads = new AtomicInteger();
        ProjectionKey key = key();
        ManagedCoopReleaseProjectionProbe probe = new ManagedCoopReleaseProjectionProbe(
                ignored -> planned(ProbeStatus.ABSENT),
                ignored -> markers(key, ProjectionProbeStatus.ABSENT),
                (owningStore, plannedUuid, indexedLocation) -> {
                    reads.incrementAndGet();
                    return ProjectionRead.unavailable("must_not_read");
                });

        Result result = probe.probe(request(), store);

        assertEquals(Outcome.ABSENT, result.outcome());
        assertEquals(0, reads.get());
    }

    @Test
    void alternateOperationMarkerIsAmbiguousEvenWhenPlannedUuidIsAbsent() {
        ProjectionKey key = key();
        ManagedCoopReleaseProjectionProbe probe = probe(
                planned(ProbeStatus.ABSENT),
                markers(key, ProjectionProbeStatus.ONE_MATCH,
                        observation(ALTERNATE, location, key)),
                ProjectionRead.unavailable("must_not_read"));

        Result result = probe.probe(request(), store);

        assertEquals(Outcome.AMBIGUOUS, result.outcome());
        assertTrue(result.detail().contains("unexpected_identity"));
    }

    @Test
    void exactPlusAlternateMarkerAndWrongExactMarkerAreAmbiguous() {
        ProjectionKey key = key();
        Outcome duplicate = ManagedCoopReleaseProjectionProbe.classify(
                ProbeStatus.ONE_LOCATION, true,
                ProjectionProbeStatus.MULTIPLE_MATCHES, true, 1, 1);
        Outcome wrongMarker = ManagedCoopReleaseProjectionProbe.classify(
                ProbeStatus.ONE_LOCATION, true,
                ProjectionProbeStatus.ONE_MATCH, false, 1, 0);

        assertEquals(Outcome.AMBIGUOUS, duplicate);
        assertEquals(Outcome.AMBIGUOUS, wrongMarker);
    }

    @Test
    void incompleteOrCrossStoreEvidenceIsAlwaysAmbiguous() {
        assertEquals(Outcome.AMBIGUOUS, ManagedCoopReleaseProjectionProbe.classify(
                ProbeStatus.UNKNOWN, false,
                ProjectionProbeStatus.UNKNOWN, false, 0, 0));
        assertEquals(Outcome.AMBIGUOUS, ManagedCoopReleaseProjectionProbe.classify(
                ProbeStatus.ONE_LOCATION, false,
                ProjectionProbeStatus.ONE_MATCH, true, 1, 0));
        assertEquals(Outcome.AMBIGUOUS, ManagedCoopReleaseProjectionProbe.classify(
                ProbeStatus.MULTIPLE_LOCATIONS, false,
                ProjectionProbeStatus.ONE_MATCH, true, 1, 0));
    }

    private ManagedCoopReleaseProjectionProbe probe(
            Probe planned,
            ProjectionProbe markers,
            ProjectionRead exactRead) {
        return new ManagedCoopReleaseProjectionProbe(
                ignored -> planned,
                ignored -> markers,
                (owningStore, plannedUuid, indexedLocation) -> exactRead);
    }

    private Probe planned(ProbeStatus status, Location... locations) {
        return new Probe(PLANNED, status, List.of(locations));
    }

    private ProjectionProbe markers(ProjectionKey key,
                                    ProjectionProbeStatus status,
                                    LoadedNpcObservation... observations) {
        return new ProjectionProbe(key, status, List.of(observations));
    }

    private LoadedNpcObservation observation(UUID uuid,
                                             Location observationLocation,
                                             ProjectionKey key) {
        return new LoadedNpcObservation(uuid, uuid, observationLocation, key);
    }

    private LiveIdentityRequest request() {
        return new LiveIdentityRequest(
                "operation-a", "profile-a",
                TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_RELEASE,
                PLANNED, "world:10:20:30:2", SOURCE, 1L);
    }

    private ProjectionKey key() {
        return ManagedCoopReleaseProjectionProbe.projectionKey(request());
    }

    private TameworkProjectionIdentityComponent marker() {
        LiveIdentityRequest request = request();
        return new TameworkProjectionIdentityComponent(
                request.profileId(), request.operationId(), request.projectionKind(),
                request.authoritySlotKey(), request.sourceNpcUuid(),
                request.operationGeneration());
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
