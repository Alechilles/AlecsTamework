package com.alechilles.alecstamework.items.persistence.checkpoint;

import com.alechilles.alecstamework.companion.extension.ProfileExtensionKey;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionData;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutation;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutationAction;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex;
import com.alechilles.alecstamework.items.persistence.maintenance.LatestWorkCoordinator;
import com.alechilles.alecstamework.items.persistence.maintenance.MaintenanceDrainResult;
import com.alechilles.alecstamework.items.persistence.maintenance.MaintenanceMetricsSnapshot;
import com.alechilles.alecstamework.items.persistence.maintenance.MaintenanceThroughputReporter;
import com.alechilles.alecstamework.items.persistence.maintenance.MaintenanceWorkOutcome;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.kernel.StorageFailure;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PublicOperationSubmission;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDomainFacades;
import com.alechilles.alecstamework.persistence.runtime.PersistenceThroughputMetrics;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.annotation.Nonnull;

/** Persists exact entity checkpoints through canonical extension operations. */
public final class ReplacementCompanionEntityCheckpointSink
        implements CompanionEntityCheckpointSink {
    public static final String NAMESPACE =
            "Alechilles:Tamework:EntityCheckpoint";
    private static final String KEY_PREFIX = "alias:";
    private static final int RETURNED_IDENTITY_RETRIES = 120;

    private final PersistenceDomainFacades persistence;
    private final Consumer<String> warnings;
    private final CompanionEntityCheckpointCodec codec =
            new CompanionEntityCheckpointCodec();
    private final CompanionEntityCheckpointAuthor author =
            new CompanionEntityCheckpointAuthor(codec);
    private final ReturnedOriginalCheckpointAuthor returnedAuthor =
            new ReturnedOriginalCheckpointAuthor(codec);
    private final LoadedNpcIdentityIndex identities;
    private final Consumer<CompanionEntityCheckpoint> published;
    private final Predicate<NpcAlias> suppressed;
    private final LatestWorkCoordinator<UUID, CheckpointWork>
            coordinator;
    private final Object schedulerLock = new Object();
    private final ScheduledThreadPoolExecutor deferralScheduler;
    private final Set<ScheduledFuture<?>> deferralTimers = new HashSet<>();
    private final PersistenceThroughputMetrics throughputMetrics;
    private final MaintenanceThroughputReporter<UUID, CheckpointWork>
            throughputReporter;
    private volatile boolean closing;

    public ReplacementCompanionEntityCheckpointSink(
            @Nonnull PersistenceDomainFacades persistence,
            @Nonnull Consumer<String> warnings
    ) {
        this(
                persistence, warnings, null,
                ignored -> { }, ignored -> false,
                PersistenceThroughputMetrics.NO_OP
        );
    }

    public ReplacementCompanionEntityCheckpointSink(
            @Nonnull PersistenceDomainFacades persistence,
            @Nonnull Consumer<String> warnings,
            LoadedNpcIdentityIndex identities,
            @Nonnull Consumer<CompanionEntityCheckpoint> published,
            @Nonnull Predicate<NpcAlias> suppressed
    ) {
        this(
                persistence,
                warnings,
                identities,
                published,
                suppressed,
                PersistenceThroughputMetrics.NO_OP
        );
    }

    /** Builds the checkpoint sink with passive maintenance measurements. */
    public ReplacementCompanionEntityCheckpointSink(
            @Nonnull PersistenceDomainFacades persistence,
            @Nonnull Consumer<String> warnings,
            LoadedNpcIdentityIndex identities,
            @Nonnull Consumer<CompanionEntityCheckpoint> published,
            @Nonnull Predicate<NpcAlias> suppressed,
            @Nonnull PersistenceThroughputMetrics throughputMetrics
    ) {
        this.persistence = Objects.requireNonNull(
                persistence, "persistence"
        );
        this.warnings = Objects.requireNonNull(warnings, "warnings");
        this.identities = identities;
        this.published = Objects.requireNonNull(published, "published");
        this.suppressed = Objects.requireNonNull(suppressed, "suppressed");
        this.throughputMetrics = Objects.requireNonNull(
                throughputMetrics, "throughputMetrics"
        );
        this.deferralScheduler = new ScheduledThreadPoolExecutor(
                1,
                runnable -> {
                    Thread thread = new Thread(
                            runnable, "tamework-checkpoint-deferral"
                    );
                    thread.setDaemon(true);
                    return thread;
                }
        );
        this.deferralScheduler.setRemoveOnCancelPolicy(true);
        this.deferralScheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(
                false
        );
        this.coordinator = new LatestWorkCoordinator<>(
                4,
                (alias, work) -> persistWithWarning(alias, work),
                this::scheduleResume
        );
        this.throughputReporter = new MaintenanceThroughputReporter<>(
                coordinator, throughputMetrics::checkpointMaintenance
        );
    }

    @Override
    @Nonnull
    public CompletionStage<Void> publish(CompanionEntityCheckpointCapture capture) {
        if (capture == null) {
            return CompletableFuture.completedFuture(null);
        }
        if (suppressed.test(capture.alias())) {
            return CompletableFuture.completedFuture(null);
        }
        CheckpointWork work = new CheckpointWork(capture);
        UUID alias = capture.alias().value();
        CompletionStage<Void> result;
        if (critical(capture.boundary())) {
            result = coordinator.submitPriority(
                    alias,
                    work,
                    ReplacementCompanionEntityCheckpointSink::selectCritical
            );
        } else {
            result = coordinator.submit(alias, work);
        }
        throughputReporter.sampleAdmission();
        result.whenComplete((ignored, failure) -> {
            if (failure != null && critical(capture.boundary())) {
                safe(() -> throughputMetrics.criticalFlushFailed());
            }
            throughputReporter.recordIfIdle();
        });
        return result;
    }

    /** Waits until the newest accepted checkpoint for one alias is durable. */
    @Nonnull
    public CompletionStage<Void> flush(@Nonnull NpcAlias alias) {
        CompletionStage<Void> result = coordinator.flush(
                Objects.requireNonNull(alias, "alias").value()
        );
        result.whenComplete((ignored, failure) -> {
            if (failure != null) {
                safe(() -> throughputMetrics.criticalFlushFailed());
            }
            throughputReporter.record();
        });
        return result;
    }

    /** Returns bounded checkpoint admission evidence. */
    @Nonnull
    public MaintenanceMetricsSnapshot metrics() {
        MaintenanceMetricsSnapshot snapshot = coordinator.metrics();
        throughputReporter.record(snapshot);
        return snapshot;
    }

    /** Stops admission and drains retained checkpoint work by the deadline. */
    @Nonnull
    public MaintenanceDrainResult shutdown(@Nonnull Duration timeout) {
        Duration checked = Objects.requireNonNull(timeout, "timeout");
        if (checked.isNegative()) {
            throw new IllegalArgumentException(
                    "Shutdown timeout must be non-negative"
            );
        }
        long startedAtNanos = System.nanoTime();
        synchronized (schedulerLock) {
            closing = true;
            for (ScheduledFuture<?> timer : deferralTimers) {
                timer.cancel(false);
            }
            deferralTimers.clear();
        }
        MaintenanceDrainResult result = coordinator.shutdown(
                remainingTimeout(checked, startedAtNanos)
        );
        deferralScheduler.shutdown();
        awaitSchedulerTermination(
                remainingTimeout(checked, startedAtNanos)
        );
        throughputReporter.record();
        return result;
    }

    private void safe(Runnable action) {
        try {
            action.run();
        } catch (Throwable ignored) {
            // Passive measurements cannot change checkpoint persistence.
        }
    }

    private CompletionStage<? extends MaintenanceWorkOutcome<CheckpointWork>>
    persistWithWarning(
            UUID alias,
            CheckpointWork work
    ) {
        CompletionStage<MaintenanceWorkOutcome<CheckpointWork>> persistence;
        try {
            persistence = persist(work);
        } catch (Throwable failure) {
            warnFailure(alias, failure);
            return CompletableFuture.completedFuture(
                    MaintenanceWorkOutcome.failed(failure)
            );
        }
        if (persistence == null) {
            NullPointerException failure = new NullPointerException(
                    "Checkpoint persistence returned no completion"
            );
            warnFailure(alias, failure);
            return CompletableFuture.completedFuture(
                    MaintenanceWorkOutcome.failed(failure)
            );
        }
        return persistence.handle((outcome, failure) -> {
            if (failure != null) {
                warnFailure(alias, failure);
                return MaintenanceWorkOutcome.failed(failure);
            }
            if (outcome instanceof MaintenanceWorkOutcome.Failed<CheckpointWork>
                    failed) {
                warnFailure(alias, failed.failure());
            }
            return outcome;
        });
    }

    private CompletionStage<MaintenanceWorkOutcome<CheckpointWork>> persist(
            CheckpointWork work
    ) {
        CompanionEntityCheckpointCapture capture = work.capture();
        return persistence.queries().findProfile(capture.alias())
                .thenCompose(read -> {
                    if (read instanceof PersistenceReadResult.Failed<
                            CompanionProfileReadModel> failed) {
                        return failedOutcome(storageFailure(failed.failure()));
                    }
                    if (!(read instanceof PersistenceReadResult.Found<
                            CompanionProfileReadModel> found)) {
                        return durableOutcome();
                    }
                    CompanionEntityCheckpoint checkpoint = author.author(
                            found.value(), capture
                    );
                    if (checkpoint != null) {
                        return durableAfter(submitAndPublish(checkpoint));
                    }
                    return persistReturned(found.value(), work);
                });
    }

    private CompletionStage<MaintenanceWorkOutcome<CheckpointWork>>
    persistReturned(
            CompanionProfileReadModel profile,
            CheckpointWork work
    ) {
        CompanionEntityCheckpointCapture capture = work.capture();
        if (identities == null || profile.currentAlias() == null) {
            return durableOutcome();
        }
        LoadedNpcIdentityIndex.ProbeStatus currentStatus = identities.probe(
                profile.currentAlias().alias().value()
        ).status();
        if (currentStatus == LoadedNpcIdentityIndex.ProbeStatus.UNKNOWN) {
            if (closing || work.attempt() >= RETURNED_IDENTITY_RETRIES) {
                return failedOutcome(new IllegalStateException(
                        closing
                                ? "returned_identity_unknown_during_shutdown"
                                : "returned_identity_unknown_retry_limit"
                ));
            }
            work.incrementAttempt();
            return CompletableFuture.completedFuture(
                    MaintenanceWorkOutcome.deferred()
            );
        }
        boolean currentSafeToReplace = currentStatus
                == LoadedNpcIdentityIndex.ProbeStatus.ABSENT
                || currentStatus
                == LoadedNpcIdentityIndex.ProbeStatus.ONE_LOCATION;
        if (!currentSafeToReplace) {
            return durableOutcome();
        }
        return persistence.queries().findAlias(capture.alias())
                .thenCompose(read -> {
                    if (read instanceof PersistenceReadResult.Failed<
                            CompanionAlias> failed) {
                        return failedOutcome(storageFailure(failed.failure()));
                    }
                    if (!(read instanceof PersistenceReadResult.Found<
                            CompanionAlias> found)) {
                        return durableOutcome();
                    }
                    CompanionEntityCheckpoint checkpoint =
                            returnedAuthor.author(
                                    profile,
                                    found.value(),
                                    capture,
                                    true
                            );
                    return checkpoint == null
                            ? durableOutcome()
                            : durableAfter(submitAndPublish(checkpoint));
                });
    }

    private static CompletionStage<MaintenanceWorkOutcome<CheckpointWork>>
    durableOutcome() {
        return CompletableFuture.completedFuture(
                MaintenanceWorkOutcome.durable()
        );
    }

    private static CompletionStage<MaintenanceWorkOutcome<CheckpointWork>>
    failedOutcome(Throwable failure) {
        return CompletableFuture.completedFuture(
                MaintenanceWorkOutcome.failed(failure)
        );
    }

    private static CompletionStage<MaintenanceWorkOutcome<CheckpointWork>>
    durableAfter(CompletionStage<Void> persistence) {
        return persistence.thenApply(ignored ->
                MaintenanceWorkOutcome.durable()
        );
    }

    private void scheduleResume(Runnable resume) {
        Objects.requireNonNull(resume, "resume");
        AtomicReference<ScheduledFuture<?>> reference =
                new AtomicReference<>();
        Runnable guarded = () -> {
            synchronized (schedulerLock) {
                ScheduledFuture<?> timer = reference.get();
                if (timer != null) {
                    deferralTimers.remove(timer);
                }
                if (!closing) {
                    resume.run();
                }
            }
        };
        synchronized (schedulerLock) {
            if (closing) {
                throw new RejectedExecutionException(
                        "Checkpoint deferral scheduler is closed"
                );
            }
            ScheduledFuture<?> timer = deferralScheduler.schedule(
                    guarded, 500L, TimeUnit.MILLISECONDS
            );
            reference.set(timer);
            deferralTimers.add(timer);
        }
    }

    private void awaitSchedulerTermination(Duration timeout) {
        long nanos;
        try {
            nanos = timeout.toNanos();
        } catch (ArithmeticException overflow) {
            nanos = Long.MAX_VALUE;
        }
        try {
            deferralScheduler.awaitTermination(
                    Math.max(0L, nanos), TimeUnit.NANOSECONDS
            );
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static Duration remainingTimeout(
            Duration timeout,
            long startedAtNanos
    ) {
        long total;
        try {
            total = timeout.toNanos();
        } catch (ArithmeticException overflow) {
            return timeout;
        }
        long elapsed = System.nanoTime() - startedAtNanos;
        if (elapsed <= 0L) {
            return timeout;
        }
        long remaining = total - elapsed;
        return remaining <= 0L ? Duration.ZERO : Duration.ofNanos(remaining);
    }

    private static boolean critical(
            CompanionEntityCheckpoint.CaptureBoundary boundary
    ) {
        return boundary == CompanionEntityCheckpoint.CaptureBoundary.UNLOAD
                || boundary
                == CompanionEntityCheckpoint.CaptureBoundary.DESTRUCTIVE_REMOVE;
    }

    private static CheckpointWork selectCritical(
            CheckpointWork existing,
            CheckpointWork candidate
    ) {
        int existingRank = criticalRank(existing.capture().boundary());
        int candidateRank = criticalRank(candidate.capture().boundary());
        return candidateRank >= existingRank ? candidate : existing;
    }

    private static int criticalRank(
            CompanionEntityCheckpoint.CaptureBoundary boundary
    ) {
        return boundary
                == CompanionEntityCheckpoint.CaptureBoundary.DESTRUCTIVE_REMOVE
                ? 2 : 1;
    }

    private CompletionStage<Void> submitAndPublish(
            CompanionEntityCheckpoint checkpoint
    ) {
        ProfileExtensionKey extensionKey = key(checkpoint);
        return currentCheckpoint(extensionKey).thenCompose(current -> {
            if (current != null && equivalent(current, checkpoint)) {
                return CompletableFuture.completedFuture(null);
            }
            return submit(checkpoint).thenRun(() -> published.accept(checkpoint));
        });
    }

    private CompletionStage<Void> submit(
            CompanionEntityCheckpoint checkpoint
    ) {
        String payload = codec.encode(checkpoint);
        String material = "companion-entity-checkpoint:v1:"
                + checkpoint.profileId() + ':'
                + checkpoint.alias() + ':'
                + checkpoint.payloadHash();
        ProfileExtensionMutation mutation = new ProfileExtensionMutation(
                key(checkpoint),
                ProfileExtensionMutationAction.PUT,
                null,
                payload,
                checkpoint.capturedAtMs()
        );
        PublicOperationSubmission submission =
                persistence.operations().mutateExtension(
                        new OperationId(UUID.nameUUIDFromBytes(
                                material.getBytes(StandardCharsets.UTF_8)
                        )),
                        new IdempotencyKey(
                                "companion-entity-checkpoint:v1:"
                                        + Sha256Hash.ofUtf8(material)
                        ),
                        mutation
                );
        if (!submission.accepted()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "checkpoint_submission_"
                                    + submission.admission().name()
                    )
            );
        }
        return submission.completion().thenAccept(result -> {
            if (result.status()
                    != OperationWorkflowResult.Status.PUBLISHED) {
                throw new IllegalStateException(
                        "checkpoint_" + result.status().name(),
                        result.failure()
                );
            }
        });
    }

    private CompletionStage<CompanionEntityCheckpoint> currentCheckpoint(
            ProfileExtensionKey key
    ) {
        var projected = persistence.queries().projectedExtension(key);
        if (projected.isPresent()) {
            return decode(projected.get().jsonPayload());
        }
        return persistence.queries().findExtension(key).thenCompose(read -> {
            if (read instanceof PersistenceReadResult.Failed<
                    ProfileExtensionData> failed) {
                return failedStorage(failed.failure());
            }
            if (!(read instanceof PersistenceReadResult.Found<
                    ProfileExtensionData> found)) {
                return CompletableFuture.completedFuture(null);
            }
            return decode(found.value().jsonPayload());
        });
    }

    private CompletionStage<CompanionEntityCheckpoint> decode(String payload) {
        try {
            return CompletableFuture.completedFuture(codec.decode(payload));
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static boolean equivalent(
            CompanionEntityCheckpoint current,
            CompanionEntityCheckpoint candidate
    ) {
        return current.profileId().equals(candidate.profileId())
                && current.alias().equals(candidate.alias())
                && current.sourceAlias().equals(candidate.sourceAlias())
                && current.aliasGeneration() == candidate.aliasGeneration()
                && current.ownerId().equals(candidate.ownerId())
                && current.lifecycleRevision().equals(
                        candidate.lifecycleRevision()
                )
                && current.reconciliationGeneration().equals(
                        candidate.reconciliationGeneration()
                )
                && current.worldKey().equals(candidate.worldKey())
                && Double.compare(current.x(), candidate.x()) == 0
                && Double.compare(current.y(), candidate.y()) == 0
                && Double.compare(current.z(), candidate.z()) == 0
                && current.holder().equals(candidate.holder())
                && equivalentBoundary(current.boundary(), candidate.boundary());
    }

    private static boolean equivalentBoundary(
            CompanionEntityCheckpoint.CaptureBoundary current,
            CompanionEntityCheckpoint.CaptureBoundary candidate
    ) {
        return candidate == CompanionEntityCheckpoint.CaptureBoundary.LOADED
                ? current == CompanionEntityCheckpoint.CaptureBoundary.LOADED
                || current == CompanionEntityCheckpoint.CaptureBoundary.UNLOAD
                : current == candidate;
    }

    private static <T> CompletionStage<T> failedStorage(
            StorageFailure failure
    ) {
        return CompletableFuture.failedFuture(storageFailure(failure));
    }

    private static IllegalStateException storageFailure(
            StorageFailure failure
    ) {
        return new IllegalStateException(failure.code(), failure.cause());
    }

    private void warnFailure(UUID alias, Throwable failure) {
        String message = failureMessage(failure);
        warnings.accept(
                "Companion checkpoint persistence failed for npc="
                        + alias + ": " + message
        );
    }

    private static String failureMessage(Throwable failure) {
        Throwable current = failure;
        List<String> messages = new ArrayList<>();
        while (current != null) {
            if (current.getMessage() != null
                    && !current.getMessage().isBlank()) {
                messages.add(current.getMessage());
            }
            Throwable cause = current.getCause();
            if (cause == null) {
                break;
            }
            current = cause;
            while ((current instanceof CompletionException
                    || current instanceof ExecutionException)
                    && current.getCause() != null) {
                current = current.getCause();
            }
        }
        if (messages.isEmpty()) {
            return failure.getClass().getSimpleName();
        }
        if (messages.size() == 1) {
            return messages.get(0);
        }
        String deepest = messages.get(messages.size() - 1);
        return deepest + " (cause-chain: "
                + String.join(" -> ", messages.subList(0, messages.size() - 1))
                + ")";
    }

    private static final class CheckpointWork {
        private final CompanionEntityCheckpointCapture capture;
        private final AtomicInteger attempts = new AtomicInteger();

        private CheckpointWork(CompanionEntityCheckpointCapture capture) {
            this.capture = Objects.requireNonNull(capture, "capture");
        }

        private CompanionEntityCheckpointCapture capture() {
            return capture;
        }

        private int attempt() {
            return attempts.get();
        }

        private void incrementAttempt() {
            attempts.incrementAndGet();
        }
    }

    /** Returns the stable extension key for one alias checkpoint. */
    @Nonnull
    public static ProfileExtensionKey key(
            @Nonnull CompanionEntityCheckpoint checkpoint
    ) {
        return key(checkpoint.profileId(), checkpoint.alias());
    }

    /** Returns the stable extension key before a checkpoint is decoded. */
    @Nonnull
    public static ProfileExtensionKey key(
            @Nonnull ProfileId profileId,
            @Nonnull NpcAlias alias
    ) {
        return new ProfileExtensionKey(
                Objects.requireNonNull(profileId, "profileId"),
                NAMESPACE,
                KEY_PREFIX + Objects.requireNonNull(alias, "alias")
        );
    }
}
