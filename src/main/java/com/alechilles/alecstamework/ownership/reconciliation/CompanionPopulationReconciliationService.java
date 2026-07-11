package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationReconciliationRepository;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationRepairRepository;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Runs bounded/resumable startup reconciliation and only publishes owner readiness after complete
 * persisted-world, player-save, base-container, and explicitly sealed custom-container coverage.
 */
public final class CompanionPopulationReconciliationService {
    public static final String GLOBAL_OWNER_COVERAGE_KEY = "owner-population:global";
    public static final String PER_WORLD_OWNER_COVERAGE_KEY = "owner-population:per-world";

    private static final Map<CompanionPopulationCoverageRecord.Dimension, String> CATALOG_KEYS = catalogKeys();

    private final CompanionPopulationReconciliationCatalog catalog;
    private final CompanionPopulationReconciliationRepository reconciliationRepository;
    private final CompanionPopulationRepository populationRepository;
    private final CompanionPopulationOperationRecoveryService operationRecovery;
    private final CompanionPopulationRepairRepository repairRepository;
    private final BiConsumer<CompanionPopulationEvidenceSource.Descriptor, Long> progressObserver;

    public CompanionPopulationReconciliationService(
            @Nonnull CompanionPopulationReconciliationCatalog catalog,
            @Nonnull CompanionPopulationReconciliationRepository reconciliationRepository,
            @Nonnull CompanionPopulationRepository populationRepository,
            @Nonnull CompanionPopulationRepairRepository repairRepository,
            @Nonnull BiConsumer<CompanionPopulationEvidenceSource.Descriptor, Long> progressObserver
    ) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.reconciliationRepository = Objects.requireNonNull(
                reconciliationRepository,
                "reconciliationRepository"
        );
        this.populationRepository = Objects.requireNonNull(populationRepository, "populationRepository");
        this.operationRecovery = new CompanionPopulationOperationRecoveryService(populationRepository);
        this.repairRepository = Objects.requireNonNull(repairRepository, "repairRepository");
        this.progressObserver = Objects.requireNonNull(progressObserver, "progressObserver");
    }

    /**
     * Reconciles all sources in batches. Invoke from startup/recovery orchestration, never a world
     * thread; source futures and SQLite completions yield between batches.
     */
    @Nonnull
    public CompletableFuture<Result> reconcileFullyAsync(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive.");
        }
        return initializeCoverage().thenCompose(initialized -> {
            if (!initialized) {
                return CompletableFuture.completedFuture(Result.degraded("reconciliation-initialize-failed"));
            }
            return scanNextSource(0, batchSize).thenCompose(scan -> {
                if (!scan.success()) {
                    return CompletableFuture.completedFuture(Result.degraded(scan.reason()));
                }
                return finishCatalogCoverage().thenCompose(catalogReady -> {
                    if (!catalogReady) {
                        return publishOwnerState(
                                CompanionPopulationCoverageRecord.State.RECONCILING,
                                "reconciliation-catalog-not-sealed",
                                0,
                                0
                        ).thenApply(ignored -> Result.reconciling("reconciliation-catalog-not-sealed"));
                    }
                    return finalizePopulation();
                });
            });
        });
    }

    @Nonnull
    private CompletableFuture<Boolean> initializeCoverage() {
        return committed(reconciliationRepository.pruneInactiveSourcesAsync(
                catalog.activeCoverageKeys(CATALOG_KEYS)
        )).thenCompose(pruned -> {
            if (!pruned) {
                return CompletableFuture.completedFuture(false);
            }
            CompletableFuture<Boolean> chain = CompletableFuture.completedFuture(true);
            for (CompanionPopulationCoverageRecord.Dimension dimension : CATALOG_KEYS.keySet()) {
                chain = chain.thenCompose(success -> success
                        ? writeCatalogCoverage(dimension, CompanionPopulationCoverageRecord.State.RECONCILING, 0, null)
                        : CompletableFuture.completedFuture(false));
            }
            return chain;
        });
    }

    @Nonnull
    private CompletableFuture<ScanResult> scanNextSource(int sourceIndex, int batchSize) {
        if (sourceIndex >= catalog.sources().size()) {
            return CompletableFuture.completedFuture(ScanResult.ok());
        }
        CompanionPopulationEvidenceSource source = catalog.sources().get(sourceIndex);
        return scanSource(source, batchSize).thenCompose(result -> result.success()
                ? scanNextSource(sourceIndex + 1, batchSize)
                : CompletableFuture.completedFuture(result));
    }

    @Nonnull
    private CompletableFuture<ScanResult> scanSource(
            @Nonnull CompanionPopulationEvidenceSource source,
            int batchSize
    ) {
        return loadResumePoint(source).thenCompose(resume -> {
            progressObserver.accept(source.descriptor(), resume.offset());
            if (resume.complete()) {
                return CompletableFuture.completedFuture(ScanResult.ok());
            }
            long offset = resume.offset();
            CompletableFuture<CompanionPopulationEvidenceSource.Batch> scanned;
            try {
                scanned = source.scan(offset, batchSize);
            } catch (Throwable throwable) {
                return markSourceFailure(source, offset, throwable);
            }
            return scanned.handle((batch, failure) -> new ScanAttempt(batch, failure))
                    .thenCompose(attempt -> {
                        if (attempt.failure() != null) {
                            return markSourceFailure(source, offset, attempt.failure());
                        }
                        return stage(source, attempt.batch(), offset).thenCompose(staged -> {
                            if (!staged.success()) {
                                return CompletableFuture.completedFuture(staged);
                            }
                            progressObserver.accept(source.descriptor(), attempt.batch().nextOffset());
                            return attempt.batch().complete()
                                    ? CompletableFuture.completedFuture(ScanResult.ok())
                                    : scanSource(source, batchSize);
                        });
                    });
        }).exceptionally(exception -> ScanResult.failed(
                "reconciliation-resume-failed:" + rootCauseName(exception)
        ));
    }

    @Nonnull
    private CompletableFuture<CompanionPopulationReconciliationRepository.ResumePoint> loadResumePoint(
            @Nonnull CompanionPopulationEvidenceSource source
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return reconciliationRepository.resumePoint(source.descriptor());
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
    }

    @Nonnull
    private CompletableFuture<ScanResult> stage(
            @Nonnull CompanionPopulationEvidenceSource source,
            @Nonnull CompanionPopulationEvidenceSource.Batch batch,
            long offset
    ) {
        PersistenceWriteQueue.WriteSubmission<CompanionPopulationReconciliationRepository.StageResult> submission =
                reconciliationRepository.stageAsync(source.descriptor(), batch, offset);
        return submission.completion().thenApply(outcome -> {
            CompanionPopulationReconciliationRepository.StageResult value = outcome.value();
            if (!outcome.isCommitted() || value == null || !value.committed()) {
                String reason = value != null && value.reason() != null
                        ? value.reason()
                        : "reconciliation-stage-failed";
                return ScanResult.failed(reason);
            }
            return ScanResult.ok();
        });
    }

    @Nonnull
    private CompletableFuture<ScanResult> markSourceFailure(
            @Nonnull CompanionPopulationEvidenceSource source,
            long offset,
            @Nonnull Throwable throwable
    ) {
        String reason = "reconciliation-source-failed:" + rootCauseName(throwable);
        return committed(reconciliationRepository.markFailureAsync(
                source.descriptor(),
                offset,
                reason
        )).thenApply(ignored -> ScanResult.failed(reason));
    }

    @Nonnull
    private CompletableFuture<Boolean> finishCatalogCoverage() {
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
    private CompletableFuture<Result> finalizePopulation() {
        List<CompanionPopulationEvidenceSource.Descriptor> descriptors = catalog.sources().stream()
                .map(CompanionPopulationEvidenceSource::descriptor)
                .toList();
        return loadEvidenceSet(descriptors).thenCompose(evidenceSet -> {
            return loadOperations().thenCompose(operations ->
                    operationRecovery.recoverAsync(operations, evidenceSet).thenCompose(recovery -> {
                        if (!recovery.complete()) {
                            return publishOwnerState(
                                    CompanionPopulationCoverageRecord.State.DEGRADED,
                                    "reconciliation-operation-ambiguous",
                                    evidenceSet.evidence().size(),
                                    recovery.ambiguous().size()
                            ).thenApply(ignored -> Result.degraded("reconciliation-operation-ambiguous"));
                        }
                        if (!evidenceSet.isConflictFree()) {
                            return publishOwnerState(
                                    CompanionPopulationCoverageRecord.State.DEGRADED,
                                    "reconciliation-evidence-conflict",
                                    evidenceSet.evidence().size(),
                                    evidenceSet.conflicts().size()
                            ).thenApply(ignored -> Result.degraded("reconciliation-evidence-conflict"));
                        }
                        return mergeEvidence(evidenceSet, recovery);
                    })
            );
        }).exceptionally(exception -> Result.degraded(
                "reconciliation-finalize-failed:" + rootCauseName(exception)
        ));
    }

    @Nonnull
    private CompletableFuture<Result> mergeEvidence(
            @Nonnull CompanionPopulationEvidenceSet evidenceSet,
            @Nonnull CompanionPopulationOperationRecoveryService.RecoveryResult recovery
    ) {
        PersistenceWriteQueue.WriteSubmission<CompanionPopulationRepairRepository.RepairResult> submission =
                repairRepository.mergeAsync(evidenceSet);
        return submission.completion().thenCompose(outcome -> {
            CompanionPopulationRepairRepository.RepairResult repair = outcome.value();
            if (!outcome.isCommitted() || repair == null || !repair.merged()) {
                String reason = repair != null && repair.reason() != null
                        ? repair.reason()
                        : "reconciliation-repair-failed";
                return publishOwnerState(
                        CompanionPopulationCoverageRecord.State.DEGRADED,
                        reason,
                        evidenceSet.evidence().size(),
                        0
                ).thenApply(ignored -> Result.degraded(reason));
            }
            return loadOperations().thenCompose(remaining -> {
                if (!remaining.isEmpty()) {
                    return publishOwnerState(
                            CompanionPopulationCoverageRecord.State.DEGRADED,
                            "reconciliation-operations-remain",
                            repair.profileCount(),
                            remaining.size()
                    ).thenApply(ignored -> Result.degraded("reconciliation-operations-remain"));
                }
                CompanionPopulationCoverageRecord.State perWorldState = repair.reason() == null
                        ? CompanionPopulationCoverageRecord.State.READY
                        : CompanionPopulationCoverageRecord.State.RECONCILING;
                return writeOwnerCoverage(
                        CompanionPopulationCoverageRecord.Dimension.GLOBAL_OWNER,
                        CompanionPopulationCoverageRecord.State.READY,
                        repair.profileCount(),
                        null
                ).thenCompose(globalWritten -> writeOwnerCoverage(
                        CompanionPopulationCoverageRecord.Dimension.PER_WORLD_OWNER,
                        perWorldState,
                        repair.profileCount(),
                        repair.reason()
                )).thenApply(written -> new Result(
                        perWorldState == CompanionPopulationCoverageRecord.State.READY
                                ? Status.READY
                                : Status.RECONCILING,
                        perWorldState == CompanionPopulationCoverageRecord.State.READY
                                ? "reconciliation-ready"
                                : repair.reason(),
                        repair.profileCount(),
                        repair.duplicateObservations(),
                        recovery.committed(),
                        recovery.canceled()
                ));
            });
        });
    }

    @Nonnull
    private CompletableFuture<CompanionPopulationEvidenceSet> loadEvidenceSet(
            @Nonnull List<CompanionPopulationEvidenceSource.Descriptor> descriptors
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new CompanionPopulationEvidenceSet(reconciliationRepository.loadEvidence(descriptors));
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
    }

    @Nonnull
    private CompletableFuture<List<CompanionPopulationOperationRecord>> loadOperations() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return populationRepository.loadNonterminalOperations();
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
    }

    @Nonnull
    private CompletableFuture<Boolean> publishOwnerState(
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
    private CompletableFuture<Boolean> writeCatalogCoverage(
            @Nonnull CompanionPopulationCoverageRecord.Dimension dimension,
            @Nonnull CompanionPopulationCoverageRecord.State state,
            int completedSources,
            @Nullable String error
    ) {
        int total = catalog.sources(dimension).size();
        return writeCoverage(new CompanionPopulationCoverageRecord(
                CATALOG_KEYS.get(dimension),
                dimension,
                "catalog",
                catalog.generation(dimension),
                state,
                "{\"completedSources\":" + completedSources + "}",
                completedSources,
                total,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                state == CompanionPopulationCoverageRecord.State.READY ? System.currentTimeMillis() : 0L,
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
        String generation = overallGeneration();
        long now = System.currentTimeMillis();
        return writeCoverage(new CompanionPopulationCoverageRecord(
                key,
                dimension,
                "canonical",
                generation,
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
    private CompletableFuture<Boolean> writeCoverage(@Nonnull CompanionPopulationCoverageRecord coverage) {
        return committed(reconciliationRepository.upsertCoverageAsync(coverage));
    }

    @Nonnull
    private static CompletableFuture<Boolean> committed(
            @Nonnull PersistenceWriteQueue.WriteSubmission<?> submission
    ) {
        return submission.completion().thenApply(PersistenceWriteQueue.WriteOutcome::isCommitted);
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
    private static String rootCauseName(@Nonnull Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current.getCause() != null)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName();
    }

    public enum Status {
        READY,
        RECONCILING,
        DEGRADED
    }

    public record Result(@Nonnull Status status,
                         @Nonnull String reason,
                         int profileCount,
                         int duplicateObservations,
                         int recoveredOperations,
                         int canceledOperations) {
        @Nonnull
        private static Result reconciling(@Nonnull String reason) {
            return new Result(Status.RECONCILING, reason, 0, 0, 0, 0);
        }

        @Nonnull
        private static Result degraded(@Nonnull String reason) {
            return new Result(Status.DEGRADED, reason, 0, 0, 0, 0);
        }
    }

    private record ScanAttempt(@Nullable CompanionPopulationEvidenceSource.Batch batch,
                               @Nullable Throwable failure) {
    }

    private record ScanResult(boolean success, @Nullable String reason) {
        @Nonnull
        private static ScanResult ok() {
            return new ScanResult(true, null);
        }

        @Nonnull
        private static ScanResult failed(@Nonnull String reason) {
            return new ScanResult(false, reason);
        }
    }
}
