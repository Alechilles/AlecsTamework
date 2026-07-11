package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

/** Loads and refreshes retained breeding operation evidence away from world-thread callbacks. */
final class BreedingReplayJournalLoader {
    private final CompanionPopulationRepository repository;
    private final PersistenceHealthService health;
    private final BreedingPopulationReplayService replayService =
            new BreedingPopulationReplayService(List.of(), false);

    BreedingReplayJournalLoader(@Nonnull CompanionPopulationRepository repository,
                                @Nonnull PersistenceHealthService health) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.health = Objects.requireNonNull(health, "health");
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

    @Nonnull
    CompletableFuture<Void> refreshAsync() {
        return CompletableFuture.runAsync(this::refresh);
    }
}
