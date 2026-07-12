package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
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

    BreedingReplayJournalLoader(@Nonnull CompanionPopulationRepository repository,
                                @Nonnull PersistenceHealthService health) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.health = Objects.requireNonNull(health, "health");
        this.replayService = new BreedingPopulationReplayService(List.of(), false);
    }

    BreedingReplayJournalLoader(@Nonnull CompanionPopulationRepository repository,
                                @Nonnull PersistenceHealthService health,
                                @Nonnull CompanionPersistedProjectionEvidenceRegistry projections) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.health = Objects.requireNonNull(health, "health");
        this.replayService = new BreedingPopulationReplayService(
                List.of(), false,
                new BreedingPersistedProjectionReplayGuard(projections));
    }

    @Nonnull
    BreedingPopulationReplayService replayService() {
        return replayService;
    }

    void refresh() {
        try {
            replayService.replace(repository.loadBreedingOperations());
        } catch (Exception | LinkageError failure) {
            replayService.markUnavailable();
            health.markDegraded("breeding_replay_journal_load_failed");
        }
    }

    /** Keeps replay fail-closed while startup evidence is not yet sealed. */
    void markUnavailable() {
        replayService.markUnavailable();
    }

    @Nonnull
    CompletableFuture<Void> refreshAsync() {
        return CompletableFuture.runAsync(this::refresh);
    }
}
