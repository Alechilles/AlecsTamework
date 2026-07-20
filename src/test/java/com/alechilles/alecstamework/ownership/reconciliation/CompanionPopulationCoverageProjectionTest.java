package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex;
import com.alechilles.alecstamework.persistence.health.PersistenceCoverageRegistry;
import com.alechilles.alecstamework.persistence.health.PersistenceCoverageStatus;
import com.alechilles.alecstamework.persistence.health.PersistenceEvidenceDimension;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScope;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScopeFactory;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for population evidence publication seen in live startup diagnostics. */
class CompanionPopulationCoverageProjectionTest {
    @Test
    void completedReconciliationPublishesEveryPopulationEvidenceDimension() {
        PersistenceCoverageRegistry coverage = new PersistenceCoverageRegistry();
        PersistenceScopeFactory scopes = PersistenceScopeFactory.ephemeral();
        CompanionPopulationCoverageProjection projection =
                new CompanionPopulationCoverageProjection(coverage, scopes);

        projection.publish(readyRecords(), sealedProjection(), 20L);

        Set<PersistenceEvidenceDimension> expected = Set.of(
                PersistenceEvidenceDimension.CANONICAL_PROFILE_CATALOG,
                PersistenceEvidenceDimension.OWNER_POPULATION_CATALOG,
                PersistenceEvidenceDimension.GLOBAL_OWNER_COUNTS,
                PersistenceEvidenceDimension.PER_WORLD_OWNER_COUNTS,
                PersistenceEvidenceDimension.PHYSICAL_CLAIM_OCCUPANCY,
                PersistenceEvidenceDimension.SAVED_WORLD_ENTITIES,
                PersistenceEvidenceDimension.BASE_CONTAINER_EVIDENCE,
                PersistenceEvidenceDimension.PERSISTED_PLAYER_INVENTORIES,
                PersistenceEvidenceDimension.LIVE_PLAYER_OVERLAY,
                PersistenceEvidenceDimension.CUSTOM_CONTAINER_EVIDENCE,
                PersistenceEvidenceDimension.LOADED_PROJECTION_IDENTITIES
        );
        for (PersistenceEvidenceDimension dimension : expected) {
            PersistenceCoverageRegistry.CoverageState state =
                    coverage.snapshot().get(dimension.key());
            assertEquals(PersistenceCoverageStatus.READY, state.status(), dimension.name());
            assertTrue(state.absenceAuthoritative(), dimension.name());
        }
    }

    /** Protects immediate-login admission while a healthy background scan starts. */
    @Test
    void beginningBackgroundScanPreservesBootstrapAdmissionCatalogs() {
        PersistenceCoverageRegistry coverage = new PersistenceCoverageRegistry();
        CompanionPopulationCoverageProjection projection = new CompanionPopulationCoverageProjection(
                coverage, PersistenceScopeFactory.ephemeral()
        );
        coverage.publish(PersistenceEvidenceDimension.CANONICAL_PROFILE_CATALOG,
                true, "population-loaded", 10L);
        coverage.publish(PersistenceEvidenceDimension.OWNER_POPULATION_CATALOG,
                true, "population-loaded", 10L);

        projection.begin();

        assertEquals(PersistenceCoverageStatus.READY,
                state(coverage, PersistenceEvidenceDimension.CANONICAL_PROFILE_CATALOG).status());
        assertEquals(PersistenceCoverageStatus.READY,
                state(coverage, PersistenceEvidenceDimension.OWNER_POPULATION_CATALOG).status());
        assertEquals(PersistenceCoverageStatus.LOADING,
                state(coverage, PersistenceEvidenceDimension.GLOBAL_OWNER_COUNTS).status());
    }

    /** Protects against retaining stale admission authority after terminal scan failure. */
    @Test
    void terminalFailureInvalidatesAdmissionCatalogsAndScanEvidence() {
        PersistenceCoverageRegistry coverage = new PersistenceCoverageRegistry();
        CompanionPopulationCoverageProjection projection = new CompanionPopulationCoverageProjection(
                coverage, PersistenceScopeFactory.ephemeral()
        );
        projection.publish(readyRecords(), sealedProjection(), 20L);

        projection.publishUnavailable("terminal-scan-failed", 21L);

        for (PersistenceEvidenceDimension dimension : populationDimensions()) {
            PersistenceCoverageRegistry.CoverageState state = state(coverage, dimension);
            assertEquals(PersistenceCoverageStatus.UNAVAILABLE, state.status(), dimension.name());
            assertEquals("terminal-scan-failed", state.reason(), dimension.name());
            assertFalse(state.absenceAuthoritative(), dimension.name());
        }
    }

    @Test
    void missingOnlineOverlayCannotBeReportedReadyFromStoredInventoryCoverage() {
        PersistenceCoverageRegistry coverage = new PersistenceCoverageRegistry();
        CompanionPopulationCoverageProjection projection = new CompanionPopulationCoverageProjection(
                coverage, PersistenceScopeFactory.ephemeral()
        );
        List<CompanionPopulationCoverageRecord> records = new ArrayList<>(readyRecords());
        records.removeIf(record -> "player-saves:online".equals(record.coverageKey()));

        projection.publish(records, sealedProjection(), 21L);

        assertEquals(
                PersistenceCoverageStatus.READY,
                coverage.snapshot().get(
                        PersistenceEvidenceDimension.PERSISTED_PLAYER_INVENTORIES.key()).status()
        );
        assertEquals(
                PersistenceCoverageStatus.UNAVAILABLE,
                coverage.snapshot().get(
                        PersistenceEvidenceDimension.LIVE_PLAYER_OVERLAY.key()).status()
        );
    }

    @Test
    void partialSavedWorldCoverageAuthorizesOnlyTheCompletedWorldScope() {
        PersistenceCoverageRegistry coverage = new PersistenceCoverageRegistry();
        PersistenceScopeFactory scopes = PersistenceScopeFactory.ephemeral();
        CompanionPopulationCoverageProjection projection =
                new CompanionPopulationCoverageProjection(coverage, scopes);
        List<CompanionPopulationCoverageRecord> records = new ArrayList<>(readyRecords());
        records.removeIf(record -> record.dimension()
                == CompanionPopulationCoverageRecord.Dimension.WORLD_ENTITIES);
        records.add(record(
                "world-entities:alpha",
                CompanionPopulationCoverageRecord.Dimension.WORLD_ENTITIES,
                "alpha",
                CompanionPopulationCoverageRecord.State.READY,
                null
        ));
        records.add(record(
                "world-entities:beta",
                CompanionPopulationCoverageRecord.Dimension.WORLD_ENTITIES,
                "beta",
                CompanionPopulationCoverageRecord.State.RECONCILING,
                "world-scan-in-progress"
        ));

        projection.publish(records, sealedProjection(), 22L);

        String dimension = PersistenceEvidenceDimension.SAVED_WORLD_ENTITIES.key();
        PersistenceScope alpha = scopes.world("alpha");
        PersistenceScope beta = scopes.world("beta");
        assertEquals(PersistenceCoverageStatus.PARTIAL,
                coverage.snapshot().get(dimension).status());
        assertTrue(coverage.areReady(Set.of(dimension), List.of(alpha)));
        assertFalse(coverage.areReady(Set.of(dimension), List.of(beta)));
        assertFalse(coverage.areReady(Set.of(dimension)));
    }

    private static List<CompanionPopulationCoverageRecord> readyRecords() {
        return List.of(
                record("profile-state:sqlite",
                        CompanionPopulationCoverageRecord.Dimension.PROFILE_STATE,
                        "canonical", CompanionPopulationCoverageRecord.State.READY, null),
                record("owner-population:global",
                        CompanionPopulationCoverageRecord.Dimension.GLOBAL_OWNER,
                        "canonical", CompanionPopulationCoverageRecord.State.READY, null),
                record("owner-population:per-world",
                        CompanionPopulationCoverageRecord.Dimension.PER_WORLD_OWNER,
                        "canonical", CompanionPopulationCoverageRecord.State.READY, null),
                record("world-entities:alpha",
                        CompanionPopulationCoverageRecord.Dimension.WORLD_ENTITIES,
                        "alpha", CompanionPopulationCoverageRecord.State.READY, null),
                record("base-containers:alpha",
                        CompanionPopulationCoverageRecord.Dimension.BASE_CONTAINER_BLOCKS,
                        "alpha", CompanionPopulationCoverageRecord.State.READY, null),
                record("player-saves:stored",
                        CompanionPopulationCoverageRecord.Dimension.PLAYER_SAVES,
                        "universe", CompanionPopulationCoverageRecord.State.READY, null),
                record("player-saves:online",
                        CompanionPopulationCoverageRecord.Dimension.PLAYER_SAVES,
                        "universe", CompanionPopulationCoverageRecord.State.READY, null),
                record("catalog:custom-containers",
                        CompanionPopulationCoverageRecord.Dimension.CUSTOM_CONTAINERS,
                        "catalog", CompanionPopulationCoverageRecord.State.READY, null)
        );
    }

    private static Set<PersistenceEvidenceDimension> populationDimensions() {
        return Set.of(
                PersistenceEvidenceDimension.CANONICAL_PROFILE_CATALOG,
                PersistenceEvidenceDimension.OWNER_POPULATION_CATALOG,
                PersistenceEvidenceDimension.GLOBAL_OWNER_COUNTS,
                PersistenceEvidenceDimension.PER_WORLD_OWNER_COUNTS,
                PersistenceEvidenceDimension.PHYSICAL_CLAIM_OCCUPANCY,
                PersistenceEvidenceDimension.SAVED_WORLD_ENTITIES,
                PersistenceEvidenceDimension.BASE_CONTAINER_EVIDENCE,
                PersistenceEvidenceDimension.PERSISTED_PLAYER_INVENTORIES,
                PersistenceEvidenceDimension.LIVE_PLAYER_OVERLAY,
                PersistenceEvidenceDimension.CUSTOM_CONTAINER_EVIDENCE,
                PersistenceEvidenceDimension.LOADED_PROJECTION_IDENTITIES
        );
    }

    private static PersistenceCoverageRegistry.CoverageState state(
            PersistenceCoverageRegistry coverage,
            PersistenceEvidenceDimension dimension
    ) {
        return coverage.snapshot().get(dimension.key());
    }

    private static CompanionPopulationCoverageRecord record(
            String key,
            CompanionPopulationCoverageRecord.Dimension dimension,
            String scope,
            CompanionPopulationCoverageRecord.State state,
            String error
    ) {
        return new CompanionPopulationCoverageRecord(
                key, dimension, scope, "generation-a", state, null,
                1L, 1L, 1L, 2L,
                state == CompanionPopulationCoverageRecord.State.READY ? 2L : 0L,
                error
        );
    }

    private static CompanionPersistedProjectionEvidenceRegistry.Snapshot sealedProjection() {
        CompanionPersistedProjectionEvidenceRegistry registry =
                new CompanionPersistedProjectionEvidenceRegistry();
        LoadedNpcIdentityIndex identities = new LoadedNpcIdentityIndex();
        identities.markInitializationComplete();
        CompanionLiveEvidenceRevision live = new CompanionLiveEvidenceRevision();
        registry.bindLoadedIdentityIndex(identities);
        registry.bindLiveEvidenceRevision(live);
        registry.begin("test-epoch");
        assertTrue(registry.publishSealed(
                "test-epoch",
                new CompanionPopulationEvidenceSet(List.of()),
                identities.snapshot().mutationRevision(),
                live.capture()
        ));
        return registry.snapshot();
    }
}
