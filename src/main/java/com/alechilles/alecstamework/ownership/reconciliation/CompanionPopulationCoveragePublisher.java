package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationReconciliationRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Publishes source-catalog and derived owner coverage for one reconciliation generation. */
final class CompanionPopulationCoveragePublisher {
    static final String GLOBAL_OWNER_COVERAGE_KEY = "owner-population:global";
    static final String PER_WORLD_OWNER_COVERAGE_KEY = "owner-population:per-world";

    private static final Map<CompanionPopulationCoverageRecord.Dimension, String> CATALOG_KEYS =
            catalogKeys();

    private final CompanionPopulationReconciliationCatalog catalog;
    private final CompanionPopulationReconciliationRepository repository;

    CompanionPopulationCoveragePublisher(
            @Nonnull CompanionPopulationReconciliationCatalog catalog,
            @Nonnull CompanionPopulationReconciliationRepository repository
    ) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Nonnull
    CompletableFuture<Boolean> initializeAsync() {
        return committed(repository.pruneInactiveSourcesAsync(
                catalog.activeCoverageKeys(CATALOG_KEYS)
        )).thenCompose(pruned -> {
            if (!pruned) {
                return CompletableFuture.completedFuture(false);
            }
            CompletableFuture<Boolean> chain = CompletableFuture.completedFuture(true);
            for (CompanionPopulationCoverageRecord.Dimension dimension : CATALOG_KEYS.keySet()) {
                chain = chain.thenCompose(success -> success
                        ? writeCatalogCoverage(
                                dimension,
                                CompanionPopulationCoverageRecord.State.RECONCILING,
                                0,
                                null
                        )
                        : CompletableFuture.completedFuture(false));
            }
            return chain;
        });
    }

    @Nonnull
    CompletableFuture<Boolean> finishCatalogAsync() {
        CompletableFuture<Boolean> chain = CompletableFuture.completedFuture(true);
        for (CompanionPopulationCoverageRecord.Dimension dimension : CATALOG_KEYS.keySet()) {
            boolean sealed = catalog.sealed(dimension);
            CompanionPopulationCoverageRecord.State state = sealed
                    ? CompanionPopulationCoverageRecord.State.READY
                    : CompanionPopulationCoverageRecord.State.RECONCILING;
            String error = sealed ? null : "coverage-catalog-not-sealed";
            int completeSources = catalog.sources(dimension).size();
            chain = chain.thenCompose(success -> success
                    ? writeCatalogCoverage(dimension, state, completeSources, error)
                    : CompletableFuture.completedFuture(false));
        }
        return chain.thenApply(written -> written && allCatalogsSealed());
    }

    @Nonnull
    CompletableFuture<Boolean> publishBothAsync(
            @Nonnull CompanionPopulationCoverageRecord.State state,
            @Nonnull String reason,
            int scanned,
            int unresolved
    ) {
        return writeOwnerCoverage(
                CompanionPopulationCoverageRecord.Dimension.GLOBAL_OWNER,
                state,
                scanned,
                reason
        ).thenCompose(written -> writeOwnerCoverage(
                CompanionPopulationCoverageRecord.Dimension.PER_WORLD_OWNER,
                state,
                scanned,
                reason + ":unresolved=" + unresolved
        ));
    }

    @Nonnull
    CompletableFuture<Boolean> publishMergedAsync(
            @Nonnull CompanionPopulationCoverageRecord.State perWorldState,
            int profiles,
            @Nullable String perWorldReason
    ) {
        return writeOwnerCoverage(
                CompanionPopulationCoverageRecord.Dimension.GLOBAL_OWNER,
                CompanionPopulationCoverageRecord.State.READY,
                profiles,
                null
        ).thenCompose(globalWritten -> writeOwnerCoverage(
                CompanionPopulationCoverageRecord.Dimension.PER_WORLD_OWNER,
                perWorldState,
                profiles,
                perWorldReason
        ));
    }

    @Nonnull
    private CompletableFuture<Boolean> writeCatalogCoverage(
            @Nonnull CompanionPopulationCoverageRecord.Dimension dimension,
            @Nonnull CompanionPopulationCoverageRecord.State state,
            int completedSources,
            @Nullable String error
    ) {
        int total = catalog.sources(dimension).size();
        long now = System.currentTimeMillis();
        return writeCoverage(new CompanionPopulationCoverageRecord(
                CATALOG_KEYS.get(dimension),
                dimension,
                "catalog",
                catalog.generation(dimension),
                state,
                "{\"completedSources\":" + completedSources + "}",
                completedSources,
                total,
                now,
                now,
                state == CompanionPopulationCoverageRecord.State.READY ? now : 0L,
                error
        ));
    }

    @Nonnull
    private CompletableFuture<Boolean> writeOwnerCoverage(
            @Nonnull CompanionPopulationCoverageRecord.Dimension dimension,
            @Nonnull CompanionPopulationCoverageRecord.State state,
            int profiles,
            @Nullable String error
    ) {
        String key = dimension == CompanionPopulationCoverageRecord.Dimension.GLOBAL_OWNER
                ? GLOBAL_OWNER_COVERAGE_KEY
                : PER_WORLD_OWNER_COVERAGE_KEY;
        long now = System.currentTimeMillis();
        return writeCoverage(new CompanionPopulationCoverageRecord(
                key,
                dimension,
                "canonical",
                overallGeneration(),
                state,
                "{\"profiles\":" + profiles + "}",
                profiles,
                profiles,
                now,
                now,
                state == CompanionPopulationCoverageRecord.State.READY ? now : 0L,
                error
        ));
    }

    @Nonnull
    private CompletableFuture<Boolean> writeCoverage(
            @Nonnull CompanionPopulationCoverageRecord coverage
    ) {
        return committed(repository.upsertCoverageAsync(coverage));
    }

    private boolean allCatalogsSealed() {
        for (CompanionPopulationCoverageRecord.Dimension dimension : CATALOG_KEYS.keySet()) {
            if (!catalog.sealed(dimension)) {
                return false;
            }
        }
        return true;
    }

    @Nonnull
    private String overallGeneration() {
        List<String> generations = new ArrayList<>();
        for (CompanionPopulationCoverageRecord.Dimension dimension : CATALOG_KEYS.keySet()) {
            generations.add(dimension.name() + "=" + catalog.generation(dimension));
        }
        return ReconciliationGeneration.forStrings("owner-population", generations);
    }

    @Nonnull
    private static Map<CompanionPopulationCoverageRecord.Dimension, String> catalogKeys() {
        EnumMap<CompanionPopulationCoverageRecord.Dimension, String> keys =
                new EnumMap<>(CompanionPopulationCoverageRecord.Dimension.class);
        keys.put(CompanionPopulationCoverageRecord.Dimension.PROFILE_STATE, "catalog:profile-state");
        keys.put(CompanionPopulationCoverageRecord.Dimension.WORLD_ENTITIES, "catalog:world-entities");
        keys.put(CompanionPopulationCoverageRecord.Dimension.PLAYER_SAVES, "catalog:player-saves");
        keys.put(CompanionPopulationCoverageRecord.Dimension.BASE_CONTAINER_BLOCKS, "catalog:base-containers");
        keys.put(CompanionPopulationCoverageRecord.Dimension.CUSTOM_CONTAINERS, "catalog:custom-containers");
        return Map.copyOf(keys);
    }

    @Nonnull
    private static CompletableFuture<Boolean> committed(
            @Nonnull PersistenceWriteQueue.WriteSubmission<?> submission
    ) {
        return submission.completion().thenApply(PersistenceWriteQueue.WriteOutcome::isCommitted);
    }
}
