package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex;
import com.alechilles.alecstamework.items.LoadedNpcIdentitySnapshot;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationReconciliationRepository;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationRepairRepository;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationRepository;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationScanSessionRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import java.util.List;
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
    public static final String GLOBAL_OWNER_COVERAGE_KEY =
            CompanionPopulationCoveragePublisher.GLOBAL_OWNER_COVERAGE_KEY;
    public static final String PER_WORLD_OWNER_COVERAGE_KEY =
            CompanionPopulationCoveragePublisher.PER_WORLD_OWNER_COVERAGE_KEY;

    private final CompanionPopulationReconciliationCatalog catalog;
    private final CompanionPopulationReconciliationRepository reconciliationRepository;
    private final CompanionPopulationRepository populationRepository;
    private final CompanionPopulationOperationRecoveryService operationRecovery;
    private final CompanionPopulationRepairRepository repairRepository;
    private final LoadedNpcIdentityIndex loadedNpcIdentityIndex;
    private final CompanionLiveEvidenceRevision liveEvidenceRevision;
    private final BiConsumer<CompanionPopulationEvidenceSource.Descriptor, Long> progressObserver;
    private final CompanionPopulationCoveragePublisher coveragePublisher;
    private final CompanionPopulationFinalizationService finalizationService;

    public CompanionPopulationReconciliationService(
            @Nonnull CompanionPopulationReconciliationCatalog catalog,
            @Nonnull CompanionPopulationReconciliationRepository reconciliationRepository,
            @Nonnull CompanionPopulationRepository populationRepository,
            @Nonnull CompanionPopulationRepairRepository repairRepository,
            @Nonnull CompanionPopulationScanSessionRepository scanSessionRepository,
            @Nonnull String scanSessionEpoch,
            @Nonnull LoadedNpcIdentityIndex loadedNpcIdentityIndex,
            @Nonnull CompanionPersistedProjectionEvidenceRegistry projectionEvidenceRegistry,
            @Nonnull CompanionLiveEvidenceRevision liveEvidenceRevision,
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
        this.loadedNpcIdentityIndex = Objects.requireNonNull(
                loadedNpcIdentityIndex, "loadedNpcIdentityIndex"
        );
        this.liveEvidenceRevision = Objects.requireNonNull(
                liveEvidenceRevision, "liveEvidenceRevision"
        );
        this.progressObserver = Objects.requireNonNull(progressObserver, "progressObserver");
        this.coveragePublisher = new CompanionPopulationCoveragePublisher(
                catalog, reconciliationRepository
        );
        this.finalizationService = new CompanionPopulationFinalizationService(
                scanSessionRepository, scanSessionEpoch, loadedNpcIdentityIndex,
                projectionEvidenceRegistry, liveEvidenceRevision, coveragePublisher
        );
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
        return coveragePublisher.initializeAsync().thenCompose(initialized -> {
            if (!initialized) {
                return CompletableFuture.completedFuture(Result.degraded("reconciliation-initialize-failed"));
            }
            LoadedNpcIdentitySnapshot initialIdentities = loadedNpcIdentityIndex.snapshot();
            if (!initialIdentities.initializationComplete()) {
                return loadedIdentityFailure("reconciliation-loaded-identity-incomplete");
            }
            long initialLiveEvidenceRevision = liveEvidenceRevision.capture();
            return scanNextSource(0, batchSize).thenCompose(scan -> {
                if (!scan.success()) {
                    return CompletableFuture.completedFuture(Result.degraded(scan.reason()));
                }
                LoadedNpcIdentitySnapshot scannedIdentities = loadedNpcIdentityIndex.snapshot();
                if (!scannedIdentities.initializationComplete()
                        || scannedIdentities.mutationRevision()
                        != initialIdentities.mutationRevision()) {
                    return loadedIdentityFailure(
                            "reconciliation-loaded-identity-mutated-during-scan"
                    );
                }
                return coveragePublisher.finishCatalogAsync().thenCompose(catalogReady -> {
                    if (!catalogReady) {
                        return coveragePublisher.publishBothAsync(
                                CompanionPopulationCoverageRecord.State.RECONCILING,
                                "reconciliation-catalog-not-sealed",
                                0,
                                0
                        ).thenApply(written -> written
                                ? Result.reconciling("reconciliation-catalog-not-sealed")
                                : Result.degraded("reconciliation-coverage-publish-failed"));
                    }
                    return finalizePopulation(scannedIdentities, initialLiveEvidenceRevision);
                });
            });
        }).thenCompose(this::verifyLoadedIdentityStableAfterFinalization);
    }

    @Nonnull
    private CompletableFuture<Result> verifyLoadedIdentityStableAfterFinalization(
            @Nonnull Result result
    ) {
        boolean candidate = result.loadedIdentityRevision() != null
                && result.liveEvidenceRevision() != null
                && result.projectionEvidenceSet() != null;
        if (result.status() != Status.READY
                && !(result.status() == Status.RECONCILING && candidate)) {
            return CompletableFuture.completedFuture(finalizationService.reject(
                    result, result.status(), result.reason()
            ));
        }
        if (!candidate) {
            return CompletableFuture.completedFuture(finalizationService.reject(
                    result, Status.DEGRADED, "reconciliation-final-readiness-invalid"
            ));
        }
        if (!loadedNpcIdentityIndex.isMutationRevisionCurrent(result.loadedIdentityRevision())) {
            return rejectStabilityFailure(
                    "reconciliation-loaded-identity-mutated-during-finalization"
            );
        }
        if (!liveEvidenceRevision.isCurrent(result.liveEvidenceRevision())) {
            return rejectStabilityFailure(
                    "reconciliation-live-evidence-mutated-during-finalization"
            );
        }
        return CompletableFuture.completedFuture(result);
    }

    @Nonnull
    private CompletableFuture<Result> rejectStabilityFailure(@Nonnull String reason) {
        return loadedIdentityFailure(reason).thenApply(failure -> finalizationService.reject(
                failure, failure.status(), failure.reason()
        ));
    }

    @Nonnull
    private CompletableFuture<Result> loadedIdentityFailure(@Nonnull String reason) {
        return coveragePublisher.publishBothAsync(
                CompanionPopulationCoverageRecord.State.DEGRADED,
                reason,
                0,
                1
        ).thenApply(written -> Result.degraded(
                written ? reason : "reconciliation-coverage-publish-failed"
        ));
    }

    /** Seals restart evidence and the scan session only after canonical reload succeeds. */
    @Nonnull
    CompletableFuture<Result> completeAfterCanonicalReloadAsync(@Nonnull Result result) {
        return finalizationService.completeAsync(result);
    }

    @Nonnull
    CompletableFuture<Result> completePartialAfterCanonicalReloadAsync(@Nonnull Result result) {
        return finalizationService.completePartialAsync(result);
    }

    @Nonnull
    CompletableFuture<Result> rejectAfterCanonicalReloadAsync(
            @Nonnull Result result, @Nonnull String reason) {
        return finalizationService.invalidateAndFail(result, reason);
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
    private CompletableFuture<Result> finalizePopulation(
            @Nonnull LoadedNpcIdentitySnapshot loadedIdentities,
            long expectedLiveEvidenceRevision
    ) {
        List<CompanionPopulationEvidenceSource.Descriptor> descriptors = catalog.sources().stream()
                .map(CompanionPopulationEvidenceSource::descriptor)
                .toList();
        return loadEvidenceSet(descriptors).thenCompose(evidenceSet -> {
            return loadOperations().thenCompose(operations ->
                    operationRecovery.recoverAsync(
                            operations, evidenceSet, loadedIdentities
                    ).thenCompose(recovery -> {
                        if (!recovery.complete()) {
                            return coveragePublisher.publishBothAsync(
                                    CompanionPopulationCoverageRecord.State.DEGRADED,
                                    "reconciliation-operation-ambiguous",
                                    evidenceSet.evidence().size(),
                                    recovery.ambiguous().size()
                            ).thenApply(written -> Result.degraded(written
                                    ? "reconciliation-operation-ambiguous"
                                    : "reconciliation-coverage-publish-failed"));
                        }
                        if (!evidenceSet.isConflictFree()) {
                            return coveragePublisher.publishBothAsync(
                                    CompanionPopulationCoverageRecord.State.DEGRADED,
                                    "reconciliation-evidence-conflict",
                                    evidenceSet.evidence().size(),
                                    evidenceSet.conflicts().size()
                            ).thenApply(written -> Result.degraded(written
                                    ? "reconciliation-evidence-conflict"
                                    : "reconciliation-coverage-publish-failed"));
                        }
                        return mergeEvidence(
                                evidenceSet,
                                recovery,
                                loadedIdentities.mutationRevision(),
                                expectedLiveEvidenceRevision
                        );
                    })
            );
        }).exceptionally(exception -> Result.degraded(
                "reconciliation-finalize-failed:" + rootCauseName(exception)
        ));
    }

    @Nonnull
    private CompletableFuture<Result> mergeEvidence(
            @Nonnull CompanionPopulationEvidenceSet evidenceSet,
            @Nonnull CompanionPopulationOperationRecoveryService.RecoveryResult recovery,
            long loadedIdentityRevision,
            long expectedLiveEvidenceRevision
    ) {
        PersistenceWriteQueue.WriteSubmission<CompanionPopulationRepairRepository.RepairResult> submission =
                repairRepository.mergeAsync(evidenceSet);
        return submission.completion().thenCompose(outcome -> {
            CompanionPopulationRepairRepository.RepairResult repair = outcome.value();
            if (!outcome.isCommitted() || repair == null || !repair.merged()) {
                String reason = repair != null && repair.reason() != null
                        ? repair.reason()
                        : "reconciliation-repair-failed";
                return coveragePublisher.publishBothAsync(
                        CompanionPopulationCoverageRecord.State.DEGRADED,
                        reason,
                        evidenceSet.evidence().size(),
                        0
                ).thenApply(ignored -> Result.degraded(reason));
            }
            return loadOperations().thenCompose(remaining -> {
                if (!remaining.isEmpty()) {
                    return coveragePublisher.publishBothAsync(
                            CompanionPopulationCoverageRecord.State.DEGRADED,
                            "reconciliation-operations-remain",
                            repair.profileCount(),
                            remaining.size()
                    ).thenApply(ignored -> Result.degraded("reconciliation-operations-remain"));
                }
                Status stagedStatus = repair.reason() == null ? Status.READY : Status.RECONCILING;
                String stagedReason = repair.reason() == null
                        ? "reconciliation-awaiting-final-fence" : repair.reason();
                return coveragePublisher.publishBothAsync(
                        CompanionPopulationCoverageRecord.State.RECONCILING,
                        stagedReason,
                        repair.profileCount(),
                        repair.reason() == null ? 0 : 1
                ).thenApply(written -> written ? new Result(
                        stagedStatus,
                        stagedStatus == Status.READY ? "reconciliation-ready" : repair.reason(),
                        repair.profileCount(),
                        repair.duplicateObservations(),
                        recovery.committed() + recovery.retryable(),
                        recovery.canceled(),
                        loadedIdentityRevision,
                        expectedLiveEvidenceRevision,
                        evidenceSet
                ) : Result.degraded("reconciliation-coverage-publish-failed"));
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
    private static CompletableFuture<Boolean> committed(
            @Nonnull PersistenceWriteQueue.WriteSubmission<?> submission
    ) {
        return submission.completion().thenApply(PersistenceWriteQueue.WriteOutcome::isCommitted);
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
                         int canceledOperations,
                         @Nullable Long loadedIdentityRevision,
                         @Nullable Long liveEvidenceRevision,
                         @Nullable CompanionPopulationEvidenceSet projectionEvidenceSet) {
        public Result(@Nonnull Status status,
                      @Nonnull String reason,
                      int profileCount,
                      int duplicateObservations,
                      int recoveredOperations,
                      int canceledOperations) {
            this(
                    status, reason, profileCount, duplicateObservations,
                    recoveredOperations, canceledOperations, null, null, null
            );
        }

        @Nonnull
        Result withoutFinalizationMetadata() {
            return loadedIdentityRevision == null
                    && liveEvidenceRevision == null
                    && projectionEvidenceSet == null
                    ? this
                    : new Result(
                            status, reason, profileCount, duplicateObservations,
                            recoveredOperations, canceledOperations, null, null, null
                    );
        }

        @Nonnull
        private static Result reconciling(@Nonnull String reason) {
            return new Result(Status.RECONCILING, reason, 0, 0, 0, 0, null, null, null);
        }

        @Nonnull
        private static Result degraded(@Nonnull String reason) {
            return new Result(Status.DEGRADED, reason, 0, 0, 0, 0, null, null, null);
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
