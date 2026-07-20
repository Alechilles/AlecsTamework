package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyReadiness;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex;
import com.alechilles.alecstamework.items.LoadedNpcIdentitySnapshot;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.ownership.CompanionPopulationBootstrapService;
import com.alechilles.alecstamework.ownership.OwnerPopulationIndex;
import com.alechilles.alecstamework.ownership.OwnerPopulationReadiness;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationScanSessionRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * Coordinates one bounded startup scan after the Universe is ready, then reloads both indexes.
 */
public final class CompanionPopulationStartupReconciler implements AutoCloseable {
    static final int DEFAULT_BATCH_SIZE = 128;

    private final TameworkPersistenceRuntime persistence;
    private final CompanionPopulationBootstrapService bootstrapService;
    private final CoalescedCompanionPopulationWriter observationWriter;
    private final CompanionPopulationRuntimeReconciler runtimeReconciler;
    private final OwnerPopulationIndex ownerIndex;
    private final ClaimOccupancyIndex claimIndex;
    private final LoadedNpcIdentityIndex loadedNpcIdentityIndex;
    private final CompanionLiveEvidenceRevision liveEvidenceRevision;
    private final ScheduledExecutorService executor;
    private final LoadedNpcIdentityStartupGate loadedIdentityStartupGate;
    private final AtomicReference<CompanionPopulationReconciliationProgress> progress =
            new AtomicReference<>(CompanionPopulationReconciliationProgress.idle());
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile CompletableFuture<CompanionPopulationReconciliationProgress> completion;
    private volatile StartRequest lastStartRequest;
    public CompanionPopulationStartupReconciler(
            @Nonnull TameworkPersistenceRuntime persistence,
            @Nonnull CompanionPopulationBootstrapService bootstrapService,
            @Nonnull CoalescedCompanionPopulationWriter observationWriter,
            @Nonnull CompanionPopulationRuntimeReconciler runtimeReconciler,
            @Nonnull OwnerPopulationIndex ownerIndex,
            @Nonnull ClaimOccupancyIndex claimIndex,
            @Nonnull LoadedNpcIdentityIndex loadedNpcIdentityIndex,
            @Nonnull CompanionLiveEvidenceRevision liveEvidenceRevision
    ) {
        this(
                persistence,
                bootstrapService,
                observationWriter,
                runtimeReconciler,
                ownerIndex,
                claimIndex,
                loadedNpcIdentityIndex,
                liveEvidenceRevision,
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "tamework-population-reconciliation");
                    thread.setDaemon(true);
                    return thread;
                })
        );
    }
    CompanionPopulationStartupReconciler(
            @Nonnull TameworkPersistenceRuntime persistence,
            @Nonnull CompanionPopulationBootstrapService bootstrapService,
            @Nonnull CoalescedCompanionPopulationWriter observationWriter,
            @Nonnull CompanionPopulationRuntimeReconciler runtimeReconciler,
            @Nonnull OwnerPopulationIndex ownerIndex,
            @Nonnull ClaimOccupancyIndex claimIndex,
            @Nonnull LoadedNpcIdentityIndex loadedNpcIdentityIndex,
            @Nonnull CompanionLiveEvidenceRevision liveEvidenceRevision,
            @Nonnull ScheduledExecutorService executor
    ) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.bootstrapService = Objects.requireNonNull(bootstrapService, "bootstrapService");
        this.observationWriter = Objects.requireNonNull(observationWriter, "observationWriter");
        this.runtimeReconciler = Objects.requireNonNull(runtimeReconciler, "runtimeReconciler");
        this.ownerIndex = Objects.requireNonNull(ownerIndex, "ownerIndex");
        this.claimIndex = Objects.requireNonNull(claimIndex, "claimIndex");
        this.loadedNpcIdentityIndex = Objects.requireNonNull(
                loadedNpcIdentityIndex, "loadedNpcIdentityIndex"
        );
        this.liveEvidenceRevision = Objects.requireNonNull(
                liveEvidenceRevision, "liveEvidenceRevision"
        );
        this.executor = Objects.requireNonNull(executor, "executor");
        this.loadedIdentityStartupGate = new LoadedNpcIdentityStartupGate(
                loadedNpcIdentityIndex, executor, closed::get
        );
    }
    /**
     * Starts at most one reconciliation pass. Every expensive scan and final reload runs away from
     * world threads; the returned future is suitable for diagnostics/logging only.
     */
    @Nonnull
    public CompletableFuture<CompanionPopulationReconciliationProgress> start(
            @Nonnull Universe universe,
            @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> ownerType,
            @Nonnull ItemFeatureRegistry itemFeatures,
            @Nonnull CustomContainerReconciliationRegistry customContainers,
            @Nonnull Supplier<CompletableFuture<LoadedNpcIdentitySnapshot>> loadedIdentitiesReady
    ) {
        Objects.requireNonNull(universe, "universe");
        Objects.requireNonNull(ownerType, "ownerType");
        Objects.requireNonNull(itemFeatures, "itemFeatures");
        Objects.requireNonNull(customContainers, "customContainers");
        Objects.requireNonNull(loadedIdentitiesReady, "loadedIdentitiesReady");
        if (closed.get()) {
            return CompletableFuture.completedFuture(progress.get());
        }
        lastStartRequest = new StartRequest(
                universe, ownerType, itemFeatures, customContainers, loadedIdentitiesReady
        );
        if (!started.compareAndSet(false, true)) {
            CompletableFuture<CompanionPopulationReconciliationProgress> current = completion;
            return current == null ? CompletableFuture.completedFuture(progress.get()) : current;
        }

        long startedAt = System.currentTimeMillis();
        progress.set(running("waiting-for-universe-ready", 0L, 0L, startedAt));
        CompletableFuture<CompanionPopulationReconciliationProgress> future = loadedIdentityStartupGate
                .awaitAfter(universe.getUniverseReady(), loadedIdentitiesReady)
                .thenComposeAsync(ignored -> acquireScanSession(), executor)
                .thenComposeAsync(session -> new PersistedWorldCoverageLoader()
                        .ensureLoaded(universe)
                        .thenApply(ignored -> session), executor)
                .thenComposeAsync(session -> new HytaleSavedWorldScanBarrier(universe)
                        .acquireAsync()
                        .thenApply(lease -> new BarrierSession(session, lease)), executor)
                .thenCompose(barrier -> reconcileUnderBarrier(
                        barrier,
                        universe,
                        ownerType,
                        itemFeatures,
                        customContainers,
                        startedAt
                ))
                .exceptionally(exception -> fail(exception, startedAt));
        completion = future;
        return future;
    }

    /** Repeats a completed degraded scan after an exact external source repair closes its journal. */
    @Nonnull
    public CompletableFuture<CompanionPopulationReconciliationProgress>
    restartAfterExternalRepair() {
        StartRequest request;
        synchronized (this) {
            request = lastStartRequest;
            if (closed.get() || request == null) {
                return CompletableFuture.completedFuture(progress.get());
            }
            if (progress.get().status()
                    != CompanionPopulationReconciliationProgress.Status.DEGRADED) {
                CompletableFuture<CompanionPopulationReconciliationProgress> current = completion;
                if (current == null) {
                    return CompletableFuture.completedFuture(progress.get());
                }
                return current.thenCompose(finished -> finished.status()
                        == CompanionPopulationReconciliationProgress.Status.DEGRADED
                        ? restartAfterExternalRepair()
                        : CompletableFuture.completedFuture(finished));
            }
            completion = null;
            started.set(false);
        }
        return start(
                request.universe(),
                request.ownerType(),
                request.itemFeatures(),
                request.customContainers(),
                request.loadedIdentitiesReady()
        );
    }
    @Nonnull
    private CompletableFuture<CompanionPopulationReconciliationProgress> reconcileUnderBarrier(
            @Nonnull BarrierSession barrier,
            @Nonnull Universe universe,
            @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> ownerType,
            @Nonnull ItemFeatureRegistry itemFeatures,
            @Nonnull CustomContainerReconciliationRegistry customContainers,
            long startedAt
    ) {
        CompletableFuture<CompanionPopulationReconciliationProgress> work;
        try {
            String epoch = barrier.session().epoch();
            StartupCatalog startup = new StartupCatalog(
                    () -> HytaleCompanionPopulationCatalogFactory.create(
                            universe,
                            ownerType,
                            itemFeatures,
                            persistence.getCompanionPopulationLegacyEvidenceRepository(),
                            customContainers,
                            epoch,
                            epoch + ":live:" + liveEvidenceRevision.capture()
                    ),
                    epoch
            );
            work = reconcile(startup, startedAt);
        } catch (Throwable failure) {
            work = CompletableFuture.failedFuture(failure);
        }
        return work.handle(BarrierCompletion::new).thenCompose(completion ->
                barrier.lease().releaseAsync().handle((ignored, releaseFailure) -> {
                    if (completion.failure() != null) {
                        if (releaseFailure != null) {
                            completion.failure().addSuppressed(rootCause(releaseFailure));
                        }
                        throw new java.util.concurrent.CompletionException(completion.failure());
                    }
                    if (releaseFailure != null) {
                        throw new java.util.concurrent.CompletionException(rootCause(releaseFailure));
                    }
                    return completion.value();
                })
        );
    }
    @Nonnull
    public CompanionPopulationReconciliationProgress progress() {
        return progress.get();
    }

    private record StartRequest(
            Universe universe,
            ComponentType<EntityStore, TameworkOwnerComponent> ownerType,
            ItemFeatureRegistry itemFeatures,
            CustomContainerReconciliationRegistry customContainers,
            Supplier<CompletableFuture<LoadedNpcIdentitySnapshot>> loadedIdentitiesReady
    ) {
    }

    @Nonnull
    private CompletableFuture<CompanionPopulationScanSessionRepository.Session> acquireScanSession() {
        PersistenceWriteQueue.WriteSubmission<CompanionPopulationScanSessionRepository.Session> submission =
                persistence.getCompanionPopulationScanSessionRepository().acquireOrResumeAsync();
        return submission.completion().thenCompose(outcome -> {
            CompanionPopulationScanSessionRepository.Session session = outcome.value();
            if (outcome.isCommitted() && session != null) {
                persistence.getCompanionPersistedProjectionEvidenceRegistry().begin(session.epoch());
                return CompletableFuture.completedFuture(session);
            }
            String reason = outcome.failureReason() == null
                    ? "population-scan-session-acquire-failed"
                    : outcome.failureReason();
            return CompletableFuture.failedFuture(new IllegalStateException(reason, outcome.failure()));
        });
    }

    @Nonnull
    private CompletableFuture<CompanionPopulationReconciliationProgress> reconcile(
            @Nonnull StartupCatalog startup,
            long startedAt
    ) {
        return reconcile(startup, startedAt, 0);
    }

    @Nonnull
    private CompletableFuture<CompanionPopulationReconciliationProgress> reconcile(
            @Nonnull StartupCatalog startup,
            long startedAt,
            int priorRetries
    ) {
        if (closed.get()) {
            return CompletableFuture.completedFuture(progress.get());
        }
        HytaleCompanionPopulationCatalogFactory.BuildResult build = startup.build();
        ProgressTracker tracker = new ProgressTracker(build.catalog(), build.reason(), startedAt);
        progress.set(tracker.snapshot());
        CompanionPopulationReconciliationService service = new CompanionPopulationReconciliationService(
                build.catalog(),
                persistence.getCompanionPopulationReconciliationRepository(),
                persistence.getCompanionPopulationRepository(),
                persistence.getCompanionPopulationRepairRepository(),
                persistence.getCompanionPopulationScanSessionRepository(),
                startup.scanSessionEpoch(),
                loadedNpcIdentityIndex,
                persistence.getCompanionPersistedProjectionEvidenceRegistry(),
                liveEvidenceRevision,
                (descriptor, offset) -> progress.set(tracker.update(descriptor, offset))
        );
        return service.reconcileFullyAsync(DEFAULT_BATCH_SIZE).thenCompose(result -> {
            if (CompanionPopulationReconciliationRetryPolicy.shouldRetry(result.reason())) {
                return retry(startup, startedAt, priorRetries, result.reason());
            }
            return beginFinalReload().thenCompose(ignored -> CanonicalReloadCompletion.run(
                    () -> observationWriter.flushCurrentAttemptsNow().thenComposeAsync(value ->
                            finish(result, tracker, service), executor),
                    this::finishFinalReload
            )).exceptionallyCompose(failure -> service.rejectAfterCanonicalReloadAsync(
                    result,
                    "reconciliation-final-reload-failed:"
                            + rootCause(failure).getClass().getSimpleName()
            ).thenCompose(ignored -> CompletableFuture.failedFuture(failure)))
                    .thenCompose(finished ->
                            CompanionPopulationReconciliationRetryPolicy.shouldRetry(
                                    finished.reason())
                                    ? retry(startup, startedAt, priorRetries, finished.reason())
                                    : CompletableFuture.completedFuture(finished));
        });
    }

    @Nonnull
    private CompletableFuture<CompanionPopulationReconciliationProgress> retry(
            StartupCatalog startup,
            long startedAt,
            int priorRetries,
            String reason
    ) {
        persistence.getCompanionPersistedProjectionEvidenceRegistry()
                .begin(startup.scanSessionEpoch());
        CompanionPopulationReconciliationProgress previous = progress.get();
        progress.set(running(
                "reconciliation-retrying:" + reason,
                previous.scannedUnits(), previous.totalUnits(), startedAt));
        CompletableFuture<CompanionPopulationReconciliationProgress> retry =
                new CompletableFuture<>();
        try {
            executor.schedule(() -> reconcile(startup, startedAt, priorRetries + 1)
                            .whenComplete((value, failure) -> {
                                if (failure == null) {
                                    retry.complete(value);
                                } else {
                                    retry.completeExceptionally(failure);
                                }
                            }),
                    CompanionPopulationReconciliationRetryPolicy.delayMs(priorRetries),
                    TimeUnit.MILLISECONDS);
        } catch (RuntimeException exception) {
            if (closed.get()) {
                retry.complete(progress.get());
            } else {
                retry.completeExceptionally(exception);
            }
        }
        return retry;
    }

    @Nonnull
    private CompletableFuture<Void> beginFinalReload() {
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Population reconciliation runtime is closed.")
            );
        }
        if (ownerIndex.tryBeginCanonicalReload()) {
            try {
                runtimeReconciler.beginCanonicalReload();
                return CompletableFuture.completedFuture(null);
            } catch (Throwable throwable) {
                ownerIndex.finishCanonicalReload();
                return CompletableFuture.failedFuture(throwable);
            }
        }
        CompletableFuture<Void> retry = new CompletableFuture<>();
        executor.schedule(() -> beginFinalReload().whenComplete((ignored, failure) -> {
            if (failure == null) {
                retry.complete(null);
            } else {
                retry.completeExceptionally(failure);
            }
        }), 50L, TimeUnit.MILLISECONDS);
        return retry;
    }

    private void finishFinalReload() {
        try {
            runtimeReconciler.finishCanonicalReload();
        } finally {
            ownerIndex.finishCanonicalReload();
        }
    }

    @Nonnull
    private CompletableFuture<CompanionPopulationReconciliationProgress> finish(
            @Nonnull CompanionPopulationReconciliationService.Result result,
            @Nonnull ProgressTracker tracker,
            @Nonnull CompanionPopulationReconciliationService service
    ) {
        if (closed.get()) {
            return CompletableFuture.completedFuture(progress.get());
        }
        boolean candidate = result.loadedIdentityRevision() != null
                && result.liveEvidenceRevision() != null
                && result.projectionEvidenceSet() != null;
        CompanionPopulationBootstrapService.BootstrapResult bootstrap = candidate
                ? bootstrapService.loadForFinalizationCandidate()
                : bootstrapService.loadForReconciliation();
        runtimeReconciler.finishCanonicalReload();
        CompanionPopulationReconciliationProgress.Status status = finalStatus(result, bootstrap);
        if (!candidate) {
            if (status == CompanionPopulationReconciliationProgress.Status.DEGRADED) {
                ownerIndex.setReadiness(OwnerPopulationReadiness.DEGRADED);
                claimIndex.setReadiness(ClaimOccupancyReadiness.DEGRADED);
            } else {
                bootstrapService.publishReadiness(bootstrap);
            }
            return CompletableFuture.completedFuture(publishProgress(
                    result, tracker, status
            ));
        }

        CompletableFuture<CompanionPopulationReconciliationService.Result> finalization;
        if (status == CompanionPopulationReconciliationProgress.Status.READY) {
            finalization = service.completeAfterCanonicalReloadAsync(result);
        } else if (result.status() == CompanionPopulationReconciliationService.Status.RECONCILING
                && status == CompanionPopulationReconciliationProgress.Status.RECONCILING
                && bootstrap.globalReadiness() == OwnerPopulationReadiness.READY
                && bootstrap.perWorldReadiness() == OwnerPopulationReadiness.RECONCILING) {
            finalization = service.completePartialAfterCanonicalReloadAsync(result);
        } else {
            finalization = service.rejectAfterCanonicalReloadAsync(
                    result, "reconciliation-final-index-" + bootstrap.reason()
            );
        }
        return finalization.thenApply(finalResult -> {
            CompanionPopulationReconciliationProgress.Status finalStatus =
                    switch (finalResult.status()) {
                        case READY -> CompanionPopulationReconciliationProgress.Status.READY;
                        case RECONCILING -> CompanionPopulationReconciliationProgress.Status.RECONCILING;
                        case DEGRADED -> CompanionPopulationReconciliationProgress.Status.DEGRADED;
                    };
            if (finalStatus != CompanionPopulationReconciliationProgress.Status.DEGRADED) {
                bootstrapService.publishReadiness(bootstrap);
            } else {
                ownerIndex.setReadiness(OwnerPopulationReadiness.DEGRADED);
                claimIndex.setReadiness(ClaimOccupancyReadiness.DEGRADED);
            }
            return publishProgress(finalResult, tracker, finalStatus);
        });
    }

    @Nonnull
    private CompanionPopulationReconciliationProgress publishProgress(
            @Nonnull CompanionPopulationReconciliationService.Result result,
            @Nonnull ProgressTracker tracker,
            @Nonnull CompanionPopulationReconciliationProgress.Status status
    ) {
        CompanionPopulationReconciliationProgress finished = tracker.finish(
                status,
                result.reason(),
                result.profileCount(),
                result.duplicateObservations(),
                result.recoveredOperations(),
                result.canceledOperations()
        );
        progress.set(finished);
        return finished;
    }

    @Nonnull
    private CompanionPopulationReconciliationProgress fail(@Nonnull Throwable exception, long startedAt) {
        if (closed.get()) {
            return progress.get();
        }
        String reason = "reconciliation_startup_failed";
        CompanionPersistedProjectionEvidenceRegistry registry =
                persistence.getCompanionPersistedProjectionEvidenceRegistry();
        CompanionPersistedProjectionEvidenceRegistry.Snapshot projectionSnapshot =
                registry.snapshot();
        if (projectionSnapshot.scanEpoch() != null) {
            registry.degrade(projectionSnapshot.scanEpoch(), reason);
        }
        persistence.getHealthService().markDegraded(reason);
        ownerIndex.setReadiness(OwnerPopulationReadiness.DEGRADED);
        claimIndex.setReadiness(ClaimOccupancyReadiness.DEGRADED);
        CompanionPopulationReconciliationProgress failed = new CompanionPopulationReconciliationProgress(
                CompanionPopulationReconciliationProgress.Status.DEGRADED,
                reason,
                0L,
                0L,
                0,
                0,
                0,
                0,
                startedAt,
                System.currentTimeMillis()
        );
        progress.set(failed);
        return failed;
    }

    @Nonnull
    static CompanionPopulationReconciliationProgress.Status finalStatus(
            @Nonnull CompanionPopulationReconciliationService.Result result,
            @Nonnull CompanionPopulationBootstrapService.BootstrapResult bootstrap
    ) {
        if (result.status() == CompanionPopulationReconciliationService.Status.DEGRADED
                || bootstrap.globalReadiness() == OwnerPopulationReadiness.DEGRADED
                || bootstrap.perWorldReadiness() == OwnerPopulationReadiness.DEGRADED) {
            return CompanionPopulationReconciliationProgress.Status.DEGRADED;
        }
        if (result.status() == CompanionPopulationReconciliationService.Status.READY
                && bootstrap.globalReadiness() == OwnerPopulationReadiness.READY
                && bootstrap.perWorldReadiness() == OwnerPopulationReadiness.READY) {
            return CompanionPopulationReconciliationProgress.Status.READY;
        }
        return CompanionPopulationReconciliationProgress.Status.RECONCILING;
    }

    @Nonnull
    private static CompanionPopulationReconciliationProgress running(
            @Nonnull String reason,
            long scanned,
            long total,
            long startedAt
    ) {
        return new CompanionPopulationReconciliationProgress(
                CompanionPopulationReconciliationProgress.Status.RUNNING,
                reason,
                scanned,
                total,
                0,
                0,
                0,
                0,
                startedAt,
                0L
        );
    }

    @Nonnull
    private static Throwable rootCause(@Nonnull Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture<CompanionPopulationReconciliationProgress> current = completion;
        if (current != null) {
            current.cancel(true);
        }
        CompanionPopulationReconciliationProgress previous = progress.get();
        progress.set(new CompanionPopulationReconciliationProgress(
                CompanionPopulationReconciliationProgress.Status.CLOSED,
                "reconciliation-runtime-closed",
                previous.scannedUnits(),
                previous.totalUnits(),
                previous.profileCount(),
                previous.duplicateObservations(),
                previous.recoveredOperations(),
                previous.canceledOperations(),
                previous.startedAtMs(),
                System.currentTimeMillis()
        ));
        executor.shutdownNow();
    }

    private record StartupCatalog(
            @Nonnull Supplier<HytaleCompanionPopulationCatalogFactory.BuildResult> buildFactory,
            @Nonnull String scanSessionEpoch
    ) {
        private HytaleCompanionPopulationCatalogFactory.BuildResult build() {
            return Objects.requireNonNull(buildFactory.get(), "startup catalog");
        }
    }

    private record BarrierSession(
            @Nonnull CompanionPopulationScanSessionRepository.Session session,
            @Nonnull HytaleSavedWorldScanBarrier.Lease lease
    ) {
    }

    private record BarrierCompletion(
            CompanionPopulationReconciliationProgress value,
            Throwable failure
    ) {
    }

    private final class ProgressTracker {
        private final Map<String, Long> offsets = new HashMap<>();
        private final Map<String, Long> estimates = new HashMap<>();
        private final String reason;
        private final long startedAt;

        private ProgressTracker(@Nonnull CompanionPopulationReconciliationCatalog catalog,
                                @Nonnull String reason,
                                long startedAt) {
            this.reason = reason;
            this.startedAt = startedAt;
            for (CompanionPopulationEvidenceSource source : catalog.sources()) {
                CompanionPopulationEvidenceSource.Descriptor descriptor = source.descriptor();
                offsets.put(descriptor.coverageKey(), 0L);
                estimates.put(descriptor.coverageKey(), descriptor.estimatedTotal());
            }
        }

        @Nonnull
        private synchronized CompanionPopulationReconciliationProgress update(
                @Nonnull CompanionPopulationEvidenceSource.Descriptor descriptor,
                long offset
        ) {
            long normalized = Math.max(0L, offset);
            offsets.put(descriptor.coverageKey(), normalized);
            estimates.merge(descriptor.coverageKey(), normalized, Math::max);
            return snapshot();
        }

        @Nonnull
        private synchronized CompanionPopulationReconciliationProgress snapshot() {
            long scanned = sum(offsets);
            long total = Math.max(scanned, sum(estimates));
            return running(reason, scanned, total, startedAt);
        }

        @Nonnull
        private synchronized CompanionPopulationReconciliationProgress finish(
                @Nonnull CompanionPopulationReconciliationProgress.Status status,
                @Nonnull String finalReason,
                int profileCount,
                int duplicateObservations,
                int recoveredOperations,
                int canceledOperations
        ) {
            long scanned = sum(offsets);
            long total = Math.max(scanned, sum(estimates));
            return new CompanionPopulationReconciliationProgress(
                    status,
                    finalReason,
                    scanned,
                    total,
                    profileCount,
                    duplicateObservations,
                    recoveredOperations,
                    canceledOperations,
                    startedAt,
                    System.currentTimeMillis()
            );
        }

        private long sum(@Nonnull Map<String, Long> values) {
            long total = 0L;
            for (long value : values.values()) {
                total = Math.addExact(total, value);
            }
            return total;
        }
    }
}
