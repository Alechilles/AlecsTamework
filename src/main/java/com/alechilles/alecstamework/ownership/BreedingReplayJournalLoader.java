package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import com.alechilles.alecstamework.persistence.sqlite.LegacyGlobalPersistenceFailureBridge;
import com.alechilles.alecstamework.persistence.health.PersistenceCoverageRegistry;
import com.alechilles.alecstamework.persistence.health.PersistenceEvidenceDimension;
import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFailureContext;
import com.alechilles.alecstamework.persistence.incidents.PersistenceIncidentReporter;
import com.alechilles.alecstamework.persistence.incidents.PersistenceOperationPhase;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScopeFactory;
import com.alechilles.alecstamework.persistence.incidents.PersistenceTransactionOutcome;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPersistedProjectionEvidenceRegistry;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

/** Loads and refreshes retained breeding operation evidence away from world-thread callbacks. */
final class BreedingReplayJournalLoader {
    private final CompanionPopulationRepository repository;
    private final PersistenceHealthService health;
    private final BreedingPopulationReplayService replayService;
    private final PersistenceCoverageRegistry coverage;
    private final PersistenceIncidentReporter incidents;
    private final PersistenceScopeFactory scopes;

    BreedingReplayJournalLoader(@Nonnull CompanionPopulationRepository repository,
                                @Nonnull PersistenceHealthService health) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.health = Objects.requireNonNull(health, "health");
        this.replayService = new BreedingPopulationReplayService(List.of(), false);
        this.coverage = null;
        this.incidents = null;
        this.scopes = null;
    }

    BreedingReplayJournalLoader(@Nonnull CompanionPopulationRepository repository,
                                @Nonnull PersistenceHealthService health,
                                @Nonnull CompanionPersistedProjectionEvidenceRegistry projections) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.health = Objects.requireNonNull(health, "health");
        this.replayService = new BreedingPopulationReplayService(
                List.of(), false,
                new BreedingPersistedProjectionReplayGuard(projections));
        this.coverage = null;
        this.incidents = null;
        this.scopes = null;
    }

    BreedingReplayJournalLoader(
            @Nonnull CompanionPopulationRepository repository,
            @Nonnull PersistenceHealthService health,
            @Nonnull CompanionPersistedProjectionEvidenceRegistry projections,
            @Nonnull PersistenceCoverageRegistry coverage,
            @Nonnull PersistenceIncidentReporter incidents,
            @Nonnull PersistenceScopeFactory scopes) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.health = Objects.requireNonNull(health, "health");
        this.replayService = new BreedingPopulationReplayService(
                List.of(), false,
                new BreedingPersistedProjectionReplayGuard(projections));
        this.coverage = Objects.requireNonNull(coverage, "coverage");
        this.incidents = Objects.requireNonNull(incidents, "incidents");
        this.scopes = Objects.requireNonNull(scopes, "scopes");
    }

    @Nonnull
    BreedingPopulationReplayService replayService() {
        return replayService;
    }

    void refresh() {
        try {
            replayService.replace(repository.loadBreedingOperations());
            if (coverage != null) {
                coverage.publish(PersistenceEvidenceDimension.BREEDING_REPLAY_JOURNAL,
                        true, "loaded", System.currentTimeMillis());
            }
        } catch (Exception | LinkageError failure) {
            replayService.markUnavailable();
            if (coverage == null || incidents == null || scopes == null) {
                LegacyGlobalPersistenceFailureBridge.markDegraded(
                        health, "breeding_replay_journal_load_failed");
                return;
            }
            coverage.publish(PersistenceEvidenceDimension.BREEDING_REPLAY_JOURNAL,
                    false, "breeding_replay_journal_load_failed", System.currentTimeMillis());
            incidents.report(new PersistenceFailureContext(
                    "breeding_replay_journal_load_failed",
                    PersistenceDomain.BREEDING,
                    PersistenceOperationPhase.RECOVERY,
                    PersistenceTransactionOutcome.NOT_STARTED,
                    List.of(scopes.featureDomain(
                            PersistenceDomain.BREEDING,
                            PersistenceEvidenceDimension.BREEDING_REPLAY_JOURNAL.key())),
                    true, true, false, false, false,
                    false, true, false, null, failure));
        }
    }

    @Nonnull
    CompletableFuture<Void> refreshAsync() {
        return CompletableFuture.runAsync(this::refresh);
    }
}
