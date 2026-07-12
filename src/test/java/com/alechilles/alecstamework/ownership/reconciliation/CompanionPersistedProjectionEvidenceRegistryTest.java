package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.LoadedNpcObservation;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.Location;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.ProjectionKey;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionPersistedProjectionEvidenceRegistryTest {
    private static final ProjectionKey PROJECTION = new ProjectionKey(
            "profile", "operation", "kind", "slot", new UUID(1L, 2L), 1L);

    @Test
    void onlyTheCurrentScanEpochCanPublishSealedEvidence() {
        CompanionPersistedProjectionEvidenceRegistry registry =
                new CompanionPersistedProjectionEvidenceRegistry();
        CompanionPopulationEvidenceSet evidence =
                new CompanionPopulationEvidenceSet(List.of());
        Authorities authorities = bind(registry);

        registry.begin("epoch-one");
        registry.begin("epoch-two");

        assertFalse(registry.publishSealed(
                "epoch-one", evidence, authorities.loadedRevision(), authorities.liveRevision()));
        assertTrue(registry.publishSealed(
                "epoch-two", evidence, authorities.loadedRevision(), authorities.liveRevision()));
        assertEquals(
                CompanionPersistedProjectionEvidenceRegistry.State.SEALED,
                registry.snapshot().state()
        );
        assertEquals("epoch-two", registry.snapshot().scanEpoch());
    }

    @Test
    void matchingFailureRevokesPreviouslyPublishedRecoveryAuthority() {
        CompanionPersistedProjectionEvidenceRegistry registry =
                new CompanionPersistedProjectionEvidenceRegistry();
        CompanionPopulationEvidenceSet evidence =
                new CompanionPopulationEvidenceSet(List.of());
        Authorities authorities = bind(registry);
        registry.begin("epoch");
        assertTrue(registry.publishSealed(
                "epoch", evidence, authorities.loadedRevision(), authorities.liveRevision()));

        assertTrue(registry.degrade("epoch", "content-changed"));

        assertEquals(
                CompanionPersistedProjectionEvidenceRegistry.State.DEGRADED,
                registry.snapshot().state()
        );
        assertEquals("content-changed", registry.snapshot().detail());
        assertTrue(registry.snapshot().evidenceSet().evidence().isEmpty());
        assertFalse(registry.degrade("stale-epoch", "ignored"));
    }

    @Test
    void sealRejectsLoadedOrLiveEvidenceChangedAfterTheFinalScanCheck() {
        CompanionPersistedProjectionEvidenceRegistry registry =
                new CompanionPersistedProjectionEvidenceRegistry();
        Authorities authorities = bind(registry);
        registry.begin("epoch-loaded");
        authorities.loadedIndex().recordAdded(observation(new UUID(3L, 4L)));

        assertFalse(registry.publishSealed(
                "epoch-loaded", new CompanionPopulationEvidenceSet(List.of()),
                authorities.loadedRevision(), authorities.liveRevision()));

        CompanionPersistedProjectionEvidenceRegistry liveRegistry =
                new CompanionPersistedProjectionEvidenceRegistry();
        Authorities liveAuthorities = bind(liveRegistry);
        liveRegistry.begin("epoch-live");
        liveAuthorities.liveEvidence().advance();

        assertFalse(liveRegistry.publishSealed(
                "epoch-live", new CompanionPopulationEvidenceSet(List.of()),
                liveAuthorities.loadedRevision(), liveAuthorities.liveRevision()));
    }

    @Test
    void markerAddAndUnloadAfterSealInvalidatesPreviouslyStableAbsence() {
        CompanionPersistedProjectionEvidenceRegistry registry =
                new CompanionPersistedProjectionEvidenceRegistry();
        Authorities authorities = bind(registry);
        registry.begin("epoch");
        assertTrue(registry.publishSealed(
                "epoch", new CompanionPopulationEvidenceSet(List.of()),
                authorities.loadedRevision(), authorities.liveRevision()));
        CompanionPersistedProjectionEvidenceRegistry.ProjectionCurrentness stable =
                registry.projectionCurrentness(PROJECTION);
        LoadedNpcObservation observation = observation(new UUID(5L, 6L));

        authorities.loadedIndex().recordAdded(observation);
        authorities.loadedIndex().recordRemoved(observation);

        assertEquals(
                CompanionPersistedProjectionEvidenceRegistry.ProjectionStatus.STALE_ABSENT,
                registry.projectionCurrentness(PROJECTION).status());
        assertFalse(registry.current(
                stable.evidenceRevision(), stable.loadedIdentityRevision()));
    }

    @Test
    void exactLoadedMarkerAtSealRemainsObservedAfterUnload() {
        CompanionPersistedProjectionEvidenceRegistry registry =
                new CompanionPersistedProjectionEvidenceRegistry();
        LoadedNpcIdentityIndex loadedIndex = new LoadedNpcIdentityIndex();
        LoadedNpcObservation observation = observation(new UUID(7L, 8L));
        loadedIndex.recordAdded(observation);
        loadedIndex.markInitializationComplete();
        CompanionLiveEvidenceRevision liveEvidence = new CompanionLiveEvidenceRevision();
        registry.bindLoadedIdentityIndex(loadedIndex);
        registry.bindLiveEvidenceRevision(liveEvidence);
        long expectedLoaded = loadedIndex.snapshot().mutationRevision();
        registry.begin("epoch");
        assertTrue(registry.publishSealed(
                "epoch", new CompanionPopulationEvidenceSet(List.of()),
                expectedLoaded, liveEvidence.capture()));

        loadedIndex.recordRemoved(observation);

        assertEquals(
                CompanionPersistedProjectionEvidenceRegistry.ProjectionStatus.OBSERVED,
                registry.projectionCurrentness(PROJECTION).status());
    }

    @Test
    void liveInventoryOrNpcMutationAfterSealInvalidatesAbsenceAuthority() {
        CompanionPersistedProjectionEvidenceRegistry registry =
                new CompanionPersistedProjectionEvidenceRegistry();
        Authorities authorities = bind(registry);
        registry.begin("epoch");
        assertTrue(registry.publishSealed(
                "epoch", new CompanionPopulationEvidenceSet(List.of()),
                authorities.loadedRevision(), authorities.liveRevision()));
        CompanionPersistedProjectionEvidenceRegistry.ProjectionCurrentness stable =
                registry.projectionCurrentness(PROJECTION);

        authorities.liveEvidence().advance();

        assertEquals(
                CompanionPersistedProjectionEvidenceRegistry.ProjectionStatus.STALE_ABSENT,
                registry.projectionCurrentness(PROJECTION).status());
        assertFalse(registry.current(
                stable.evidenceRevision(), stable.loadedIdentityRevision()));
    }

    private static Authorities bind(CompanionPersistedProjectionEvidenceRegistry registry) {
        LoadedNpcIdentityIndex loadedIndex = new LoadedNpcIdentityIndex();
        loadedIndex.markInitializationComplete();
        CompanionLiveEvidenceRevision liveEvidence = new CompanionLiveEvidenceRevision();
        registry.bindLoadedIdentityIndex(loadedIndex);
        registry.bindLiveEvidenceRevision(liveEvidence);
        return new Authorities(
                loadedIndex,
                liveEvidence,
                loadedIndex.snapshot().mutationRevision(),
                liveEvidence.capture());
    }

    private static LoadedNpcObservation observation(UUID entityUuid) {
        return new LoadedNpcObservation(
                entityUuid,
                entityUuid,
                new Location("world", "store"),
                PROJECTION);
    }

    private record Authorities(
            LoadedNpcIdentityIndex loadedIndex,
            CompanionLiveEvidenceRevision liveEvidence,
            long loadedRevision,
            long liveRevision) {
    }
}
