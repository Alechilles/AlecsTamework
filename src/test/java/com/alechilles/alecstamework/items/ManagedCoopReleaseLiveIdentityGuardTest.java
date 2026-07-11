package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.Location;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.Probe;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.ProbeStatus;
import com.alechilles.alecstamework.items.ManagedCoopReleaseLiveIdentityGuard.AliasEvidence;
import com.alechilles.alecstamework.items.ManagedCoopReleaseLiveIdentityGuard.ProjectionRead;
import com.alechilles.alecstamework.items.ManagedCoopReleaseRuntimeAdapter.LiveIdentityDecision;
import com.alechilles.alecstamework.items.ManagedCoopReleaseRuntimeAdapter.LiveIdentityRequest;
import com.alechilles.alecstamework.items.ManagedCoopReleaseRuntimeAdapter.LiveIdentityStatus;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedCoopReleaseLiveIdentityGuardTest {
    private static final UUID SOURCE = uuid(1);
    private static final UUID PLANNED = uuid(2);
    private static final UUID HISTORICAL = uuid(3);
    private static final ManagedCoopAuthorityKey AUTHORITY =
            new ManagedCoopAuthorityKey("world", 10, 20, 30);
    private static final Location LOCATION = new Location("world", "store-a");
    private ComponentRegistry<EntityStore> registry;
    private Store<EntityStore> store;

    @BeforeEach
    void setUp() {
        registry = new ComponentRegistry<>();
        store = registry.addStore(null, null);
    }

    @AfterEach
    void tearDown() {
        registry.removeStore(store);
        registry.shutdown();
    }

    @Test
    void clearRequiresAuthoritativeAbsenceForPlannedAndEveryProfileAlias() {
        AtomicInteger projectionReads = new AtomicInteger();
        ManagedCoopReleaseLiveIdentityGuard guard = guard(
                AliasEvidence.trusted(List.of(SOURCE, HISTORICAL)),
                Map.of(
                        SOURCE, probe(SOURCE, ProbeStatus.ABSENT),
                        HISTORICAL, probe(HISTORICAL, ProbeStatus.ABSENT),
                        PLANNED, probe(PLANNED, ProbeStatus.ABSENT)
                ),
                (owningStore, planned, location) -> {
                    projectionReads.incrementAndGet();
                    return ProjectionRead.unavailable("must_not_read");
                }
        );

        LiveIdentityDecision decision = guard.inspect(request(), store);

        assertEquals(LiveIdentityStatus.CLEAR_TO_SPAWN, decision.status());
        assertEquals(0, projectionReads.get());
    }

    @Test
    void anyLiveHistoricalOrSourceAliasIsConflict() {
        ManagedCoopReleaseLiveIdentityGuard guard = guard(
                AliasEvidence.trusted(List.of(SOURCE, HISTORICAL)),
                Map.of(
                        SOURCE, probe(SOURCE, ProbeStatus.ABSENT),
                        HISTORICAL, probe(HISTORICAL, ProbeStatus.ONE_LOCATION, LOCATION),
                        PLANNED, probe(PLANNED, ProbeStatus.ABSENT)
                ),
                unavailableReader()
        );

        LiveIdentityDecision decision = guard.inspect(request(), store);

        assertEquals(LiveIdentityStatus.CONFLICT, decision.status());
        assertTrue(decision.detail().contains(HISTORICAL.toString()));
    }

    @Test
    void incompleteOrMalformedLoadedEvidenceReturnsLookupFailed() {
        ManagedCoopReleaseLiveIdentityGuard unknown = guard(
                AliasEvidence.trusted(List.of(SOURCE)),
                Map.of(
                        SOURCE, probe(SOURCE, ProbeStatus.UNKNOWN),
                        PLANNED, probe(PLANNED, ProbeStatus.ABSENT)
                ),
                unavailableReader()
        );
        ManagedCoopReleaseLiveIdentityGuard malformed = guard(
                AliasEvidence.trusted(List.of(SOURCE)),
                Map.of(
                        SOURCE, probe(SOURCE, ProbeStatus.ABSENT),
                        PLANNED, probe(PLANNED, ProbeStatus.ONE_LOCATION)
                ),
                unavailableReader()
        );

        assertEquals(LiveIdentityStatus.LOOKUP_FAILED,
                unknown.inspect(request(), store).status());
        assertEquals(LiveIdentityStatus.LOOKUP_FAILED,
                malformed.inspect(request(), store).status());
    }

    @Test
    void exactPlannedUuidAndEveryMarkerFieldReturnsMatching() {
        TameworkProjectionIdentityComponent marker = marker(
                "operation-a", "profile-a",
                TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_RELEASE,
                AUTHORITY.slotKey(2), SOURCE, 1L);
        ManagedCoopReleaseLiveIdentityGuard guard = matchingGuard(marker);

        LiveIdentityDecision decision = guard.inspect(request(), store);

        assertEquals(LiveIdentityStatus.MATCHING_MARKED_PROJECTION, decision.status());
        assertEquals(PLANNED, decision.observedTargetUuid());
    }

    @Test
    void mismatchInAnyMarkerFieldIsConflict() {
        List<TameworkProjectionIdentityComponent> mismatches = List.of(
                marker("other-operation", "profile-a", kind(), slot(), SOURCE, 1L),
                marker("operation-a", "other-profile", kind(), slot(), SOURCE, 1L),
                marker("operation-a", "profile-a", "RECOVERY", slot(), SOURCE, 1L),
                marker("operation-a", "profile-a", kind(), "other-slot", SOURCE, 1L),
                marker("operation-a", "profile-a", kind(), slot(), HISTORICAL, 1L),
                marker("operation-a", "profile-a", kind(), slot(), SOURCE, 2L)
        );

        for (TameworkProjectionIdentityComponent mismatch : mismatches) {
            LiveIdentityDecision decision = matchingGuard(mismatch).inspect(request(), store);
            assertEquals(LiveIdentityStatus.CONFLICT, decision.status());
        }
    }

    @Test
    void unavailableProjectionReadFailsLookupAndOtherStoreIsConflict() {
        ManagedCoopReleaseLiveIdentityGuard unavailable = matchingGuard(
                ProjectionRead.unavailable("projection_unloaded"));
        ManagedCoopReleaseLiveIdentityGuard conflict = matchingGuard(
                ProjectionRead.conflict("planned_uuid_loaded_in_other_store"));

        LiveIdentityDecision unavailableDecision = unavailable.inspect(request(), store);
        LiveIdentityDecision conflictDecision = conflict.inspect(request(), store);

        assertEquals(LiveIdentityStatus.LOOKUP_FAILED, unavailableDecision.status());
        assertEquals(LiveIdentityStatus.CONFLICT, conflictDecision.status());
    }

    @Test
    void productionResidentIndexEvidenceIncludesSourceAliasAndHonorsTrust() {
        ManagedCoopResidentIndex residents = new ManagedCoopResidentIndex();
        assertTrue(residents.rebuild(
                ManagedCoopReadResult.loaded(List.of(authority())),
                ManagedCoopReadResult.loaded(List.of(releasingResident()))
        ).rebuilt());
        LoadedNpcIdentityIndex loaded = new LoadedNpcIdentityIndex();
        loaded.markInitializationComplete();
        AtomicBoolean compositeTrust = new AtomicBoolean(true);
        ManagedCoopReleaseLiveIdentityGuard guard =
                new ManagedCoopReleaseLiveIdentityGuard(loaded, residents, compositeTrust::get);

        assertEquals(LiveIdentityStatus.CLEAR_TO_SPAWN,
                guard.inspect(request(), store).status());
        loaded.recordAdded(SOURCE, LOCATION);
        assertEquals(LiveIdentityStatus.CONFLICT,
                guard.inspect(request(), store).status());
        loaded.recordRemoved(SOURCE, LOCATION);
        compositeTrust.set(false);
        assertEquals(LiveIdentityStatus.LOOKUP_FAILED,
                guard.inspect(request(), store).status());
        compositeTrust.set(true);
        residents.revokeTrust();
        assertEquals(LiveIdentityStatus.LOOKUP_FAILED,
                guard.inspect(request(), store).status());
    }

    private ManagedCoopReleaseLiveIdentityGuard matchingGuard(
            TameworkProjectionIdentityComponent marker) {
        return matchingGuard(ProjectionRead.found(marker));
    }

    private ManagedCoopReleaseLiveIdentityGuard matchingGuard(ProjectionRead read) {
        return guard(
                AliasEvidence.trusted(List.of(SOURCE)),
                Map.of(
                        SOURCE, probe(SOURCE, ProbeStatus.ABSENT),
                        PLANNED, probe(PLANNED, ProbeStatus.ONE_LOCATION, LOCATION)
                ),
                (owningStore, planned, location) -> read
        );
    }

    private ManagedCoopReleaseLiveIdentityGuard guard(
            AliasEvidence aliases,
            Map<UUID, Probe> probes,
            ManagedCoopReleaseLiveIdentityGuard.ProjectionReader reader) {
        return new ManagedCoopReleaseLiveIdentityGuard(
                request -> aliases,
                npcUuid -> probes.get(npcUuid),
                reader
        );
    }

    private ManagedCoopReleaseLiveIdentityGuard.ProjectionReader unavailableReader() {
        return (owningStore, planned, location) -> ProjectionRead.unavailable("unavailable");
    }

    private LiveIdentityRequest request() {
        return new LiveIdentityRequest(
                "operation-a", "profile-a", kind(), PLANNED,
                slot(), SOURCE, 1L
        );
    }

    private AuthorityRecord authority() {
        return new AuthorityRecord(
                AUTHORITY.authorityId(), AUTHORITY, "coop-a", AuthorityState.TWORK_MANAGED,
                true, 1, -100L, -90L, null
        );
    }

    private ResidentRecord releasingResident() {
        return new ResidentRecord(
                "resident-a", AUTHORITY, "coop-a", 2, "profile-a", "tamed_test",
                SOURCE, SOURCE, null, "{}", "a".repeat(64), 1,
                ResidentState.RELEASING, 1L, true, -100L, 0L, -100L, -90L
        );
    }

    private TameworkProjectionIdentityComponent marker(String operationId,
                                                       String profileId,
                                                       String projectionKind,
                                                       String slotKey,
                                                       UUID source,
                                                       long generation) {
        return new TameworkProjectionIdentityComponent(
                profileId, operationId, projectionKind, slotKey, source, generation);
    }

    private Probe probe(UUID uuid, ProbeStatus status, Location... locations) {
        return new Probe(uuid, status, List.of(locations));
    }

    private String kind() {
        return TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_RELEASE;
    }

    private String slot() {
        return AUTHORITY.slotKey(2);
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
