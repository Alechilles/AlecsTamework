package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.breeding.BreedingBirthJob;
import com.alechilles.alecstamework.npc.breeding.BreedingBirthJobRegistry;
import com.alechilles.alecstamework.npc.breeding.BreedingJobDiagnosticSnapshot;
import com.alechilles.alecstamework.npc.breeding.BreedingJobDiagnosticsService;
import com.alechilles.alecstamework.npc.breeding.BreedingParentIdentity;
import com.alechilles.alecstamework.npc.breeding.TameworkBreedingServices;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Cancels an active breeding job before a managed-coop capture snapshots either parent.
 *
 * <p>Callers must invoke this service synchronously on the owning world thread. The registry is
 * terminated before any live parent mutation, which releases every job reservation and makes a
 * delayed callback terminal before cooldown rollback begins. Rollback is fingerprint guarded by
 * the injected parent gateway so a newer breeding attempt is never overwritten.
 */
public final class BreedingCaptureCancellationService {
    private final BreedingBirthJobRegistry jobRegistry;
    private final PreparedCancellationGateway preparedCancellation;
    private final BreedingCaptureCancellationAttemptIndex cancellationAttempts =
            new BreedingCaptureCancellationAttemptIndex();
    private final ParentRollbackGateway parentRollbackGateway;
    @Nullable
    private final BreedingJobDiagnosticsService diagnosticsService;

    /** Uses the one runtime registry shared by manual and passive breeding entrypoints. */
    public BreedingCaptureCancellationService() {
        this(TameworkBreedingServices.shared());
    }

    private BreedingCaptureCancellationService(TameworkBreedingServices services) {
        this(
                services.jobRegistry(),
                services.preparedPopulationRegistry(),
                new BreedingCaptureParentRollbackService(),
                services.jobDiagnostics()
        );
    }

    BreedingCaptureCancellationService(@Nonnull BreedingBirthJobRegistry jobRegistry,
                                       @Nonnull ParentRollbackGateway parentRollbackGateway) {
        this(
                jobRegistry,
                new com.alechilles.alecstamework.npc.breeding.BreedingPreparedPopulationRegistry(),
                parentRollbackGateway,
                null
        );
    }

    BreedingCaptureCancellationService(@Nonnull BreedingBirthJobRegistry jobRegistry,
                                       @Nonnull ParentRollbackGateway parentRollbackGateway,
                                       @Nullable BreedingJobDiagnosticsService diagnosticsService) {
        this(
                jobRegistry,
                new com.alechilles.alecstamework.npc.breeding.BreedingPreparedPopulationRegistry(),
                parentRollbackGateway,
                diagnosticsService
        );
    }

    private BreedingCaptureCancellationService(
            @Nonnull BreedingBirthJobRegistry jobRegistry,
            @Nonnull com.alechilles.alecstamework.npc.breeding.BreedingPreparedPopulationRegistry
                    preparedPopulationRegistry,
            @Nonnull ParentRollbackGateway parentRollbackGateway,
            @Nullable BreedingJobDiagnosticsService diagnosticsService) {
        this(
                jobRegistry,
                preparedCancellationGateway(preparedPopulationRegistry),
                parentRollbackGateway,
                diagnosticsService
        );
    }

    BreedingCaptureCancellationService(
            @Nonnull BreedingBirthJobRegistry jobRegistry,
            @Nonnull PreparedCancellationGateway preparedCancellation,
            @Nonnull ParentRollbackGateway parentRollbackGateway,
            @Nullable BreedingJobDiagnosticsService diagnosticsService) {
        this.jobRegistry = Objects.requireNonNull(jobRegistry, "jobRegistry");
        this.preparedCancellation = Objects.requireNonNull(
                preparedCancellation, "preparedCancellation"
        );
        this.parentRollbackGateway = Objects.requireNonNull(
                parentRollbackGateway,
                "parentRollbackGateway"
        );
        this.diagnosticsService = diagnosticsService;
    }

    /** Cancels by current entity UUID before the caller captures a coop snapshot. */
    @Nonnull
    public CancellationResult cancelForCapturedParent(
            @Nonnull Store<EntityStore> store,
            @Nonnull UUID capturedParentUuid,
            @Nonnull CancellationReason reason) {
        return cancelForCapturedParent(store, capturedParentUuid, null, reason);
    }

    /**
     * Cancels by current UUID, falling back to stable profile identity after a UUID remap.
     *
     * <p>The supplied store asserts its owning thread before any registry or ECS work begins.
     */
    @Nonnull
    public CancellationResult cancelForCapturedParent(
            @Nonnull Store<EntityStore> store,
            @Nonnull UUID capturedParentUuid,
            @Nullable String stableProfileId,
            @Nonnull CancellationReason reason) {
        Objects.requireNonNull(store, "store");
        store.assertThread();
        return cancelForCapturedParentInScope(
                store,
                capturedParentUuid,
                stableProfileId,
                reason
        );
    }

    /**
     * Starts capture cancellation without blocking the world thread.
     * Completion is successful only after every prepared child operation is durably terminal.
     */
    @Nonnull
    public CompletableFuture<CancellationResult> cancelForCapturedParentDurably(
            @Nonnull Store<EntityStore> store,
            @Nonnull UUID capturedParentUuid,
            @Nullable String stableProfileId,
            @Nonnull CancellationReason reason) {
        Objects.requireNonNull(store, "store");
        store.assertThread();
        return cancelForCapturedParentDurablyInScope(
                store, capturedParentUuid, stableProfileId, reason
        );
    }

    /**
     * Performs cancellation and rollback before invoking the snapshot callback.
     *
     * <p>The callback always runs for safe idempotent misses, allowing coop capture to continue
     * when the parent had no active breeding job. Snapshot exceptions are propagated unchanged.
     */
    @Nonnull
    public <T> SnapshotHandoff<T> cancelThenCaptureSnapshot(
            @Nonnull Store<EntityStore> store,
            @Nonnull UUID capturedParentUuid,
            @Nonnull CancellationReason reason,
            @Nonnull SnapshotCapture<T> snapshotCapture) {
        return cancelThenCaptureSnapshot(
                store,
                capturedParentUuid,
                null,
                reason,
                snapshotCapture
        );
    }

    @Nonnull
    public <T> SnapshotHandoff<T> cancelThenCaptureSnapshot(
            @Nonnull Store<EntityStore> store,
            @Nonnull UUID capturedParentUuid,
            @Nullable String stableProfileId,
            @Nonnull CancellationReason reason,
            @Nonnull SnapshotCapture<T> snapshotCapture) {
        Objects.requireNonNull(store, "store");
        store.assertThread();
        return cancelThenCaptureSnapshotInScope(
                store,
                capturedParentUuid,
                stableProfileId,
                reason,
                snapshotCapture
        );
    }

    /**
     * Performs cancellation and snapshot capture while retaining the parent-identity fence.
     *
     * <p>The caller must invoke {@link #releaseCaptureFence(Object, UUID, String, boolean)} after
     * its enclosing capture mutation either commits or fails. Exceptions raised while producing
     * the handoff release the fence as a failed capture before being propagated.</p>
     */
    @Nonnull
    public <T> SnapshotHandoff<T> cancelThenCaptureSnapshotRetainingFence(
            @Nonnull Store<EntityStore> store,
            @Nonnull UUID capturedParentUuid,
            @Nullable String stableProfileId,
            @Nonnull CancellationReason reason,
            @Nonnull SnapshotCapture<T> snapshotCapture) {
        Objects.requireNonNull(store, "store");
        store.assertThread();
        return BreedingCaptureSnapshotFenceHandoff.capture(
                () -> currentResult(cancelForCapturedParentDurablyInScope(
                        store, capturedParentUuid, stableProfileId, reason), reason),
                snapshotCapture,
                () -> releaseCaptureFenceInScope(
                        store, capturedParentUuid, stableProfileId, false)
        );
    }

    @Nonnull
    CancellationResult cancelForCapturedParentInScope(
            @Nonnull Object storeScope,
            @Nonnull UUID capturedParentUuid,
            @Nullable String stableProfileId,
            @Nonnull CancellationReason reason) {
        try {
            return currentResult(cancelForCapturedParentDurablyInScope(
                    storeScope, capturedParentUuid, stableProfileId, reason
            ), reason);
        } finally {
            releaseCaptureFenceInScope(
                    storeScope, capturedParentUuid, stableProfileId, false
            );
        }
    }

    @Nonnull
    CompletableFuture<CancellationResult> cancelForCapturedParentDurablyInScope(
            @Nonnull Object storeScope,
            @Nonnull UUID capturedParentUuid,
            @Nullable String stableProfileId,
            @Nonnull CancellationReason reason) {
        Objects.requireNonNull(storeScope, "storeScope");
        Objects.requireNonNull(capturedParentUuid, "capturedParentUuid");
        Objects.requireNonNull(reason, "reason");
        String normalizedProfileId = normalizeProfileId(stableProfileId);

        BreedingBirthJobRegistry.TerminalResult terminal =
                jobRegistry.cancelByParentUuid(storeScope, capturedParentUuid);
        MatchKind matchKind = terminal.status() == BreedingBirthJobRegistry.TerminalStatus.APPLIED
                ? MatchKind.ENTITY_UUID
                : MatchKind.NONE;
        if (terminal.status() == BreedingBirthJobRegistry.TerminalStatus.NOT_FOUND
                && normalizedProfileId != null) {
            terminal = jobRegistry.cancelByProfileId(storeScope, normalizedProfileId);
            if (terminal.status() == BreedingBirthJobRegistry.TerminalStatus.APPLIED) {
                matchKind = MatchKind.PROFILE_ID;
            }
        }
        if (terminal.status() != BreedingBirthJobRegistry.TerminalStatus.APPLIED
                || terminal.job().isEmpty()) {
            return recoverTerminalPreparation(
                    storeScope,
                    capturedParentUuid,
                    normalizedProfileId,
                    reason,
                    terminal
            );
        }

        BreedingBirthJob cancelledJob = terminal.job().orElseThrow();
        CompletableFuture<Boolean> durableCancellation = startDurableCancellation(
                storeScope,
                cancelledJob.jobId(),
                capturedParentUuid,
                normalizedProfileId
        );
        boolean capturedIsFirst = isCapturedParent(
                cancelledJob.firstParent(),
                capturedParentUuid,
                normalizedProfileId,
                matchKind
        );
        ParentRollbackReport first = rollbackSafely(
                storeScope,
                cancelledJob,
                true,
                capturedIsFirst ? capturedParentUuid : cancelledJob.firstParent().entityUuid()
        );
        ParentRollbackReport second = rollbackSafely(
                storeScope,
                cancelledJob,
                false,
                capturedIsFirst ? cancelledJob.secondParent().entityUuid() : capturedParentUuid
        );
        CancellationResult cancelled = new CancellationResult(
                CancellationStatus.CANCELLED,
                reason,
                matchKind,
                Optional.of(cancelledJob.jobId()),
                Optional.of(capturedIsFirst ? first : second),
                Optional.of(capturedIsFirst ? second : first)
        );
        CompletableFuture<CancellationResult> completion = durableCancellation.handle(
                (durable, failure) -> {
                    if (failure != null || !Boolean.TRUE.equals(durable)) {
                        return withStatus(cancelled, CancellationStatus.DURABILITY_FAILED);
                    }
                    recordCancellationDiagnostics(cancelledJob, reason, first, second);
                    return cancelled;
                }
        );
        cancellationAttempts.remember(
                storeScope, cancelledJob, capturedParentUuid, completion
        );
        return completion;
    }

    @Nonnull
    <T> SnapshotHandoff<T> cancelThenCaptureSnapshotInScope(
            @Nonnull Object storeScope,
            @Nonnull UUID capturedParentUuid,
            @Nullable String stableProfileId,
            @Nonnull CancellationReason reason,
            @Nonnull SnapshotCapture<T> snapshotCapture) {
        try {
            return BreedingCaptureSnapshotFenceHandoff.capture(
                    () -> currentResult(cancelForCapturedParentDurablyInScope(
                            storeScope, capturedParentUuid, stableProfileId, reason), reason),
                    snapshotCapture,
                    () -> { }
            );
        } finally {
            releaseCaptureFenceInScope(
                    storeScope, capturedParentUuid, stableProfileId, false
            );
        }
    }

    /** Releases the identity fence after the enclosing capture attempt reaches a terminal outcome. */
    public void releaseCaptureFence(@Nonnull Object storeScope,
                                    @Nonnull UUID capturedParentUuid,
                                    @Nullable String stableProfileId,
                                    boolean captured) {
        releaseCaptureFenceInScope(
                storeScope, capturedParentUuid, stableProfileId, captured
        );
    }

    private void releaseCaptureFenceInScope(Object storeScope,
                                            UUID capturedParentUuid,
                                            @Nullable String stableProfileId,
                                            boolean captured) {
        try {
            preparedCancellation.releaseParent(
                    storeScope, capturedParentUuid, stableProfileId, captured
            );
        } catch (RuntimeException | LinkageError ignored) {
            // Missing release retains the conservative identity fence.
        }
    }

    @Nonnull
    private CompletableFuture<Boolean> startDurableCancellation(
            Object storeScope,
            UUID jobId,
            UUID capturedParentUuid,
            @Nullable String stableProfileId) {
        List<CompletableFuture<Boolean>> barriers = new ArrayList<>();
        barriers.add(startJobCancellation(storeScope, jobId));
        CompletableFuture<Boolean> retained = retainedParentCancellation(
                storeScope, capturedParentUuid, stableProfileId
        );
        if (retained != null) {
            barriers.add(retained);
        }
        addPriorAttemptBarriers(
                barriers, storeScope, capturedParentUuid, stableProfileId
        );
        return allDurable(barriers);
    }

    private CompletableFuture<Boolean> startJobCancellation(Object storeScope, UUID jobId) {
        try {
            CompletableFuture<Boolean> completion = preparedCancellation.cancel(
                    storeScope, jobId, "breeding-parent-captured"
            );
            return completion != null
                    ? completion : CompletableFuture.completedFuture(false);
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(false);
        }
    }

    @Nonnull
    private CompletableFuture<CancellationResult> recoverTerminalPreparation(
            Object storeScope,
            UUID capturedParentUuid,
            @Nullable String stableProfileId,
            CancellationReason reason,
            BreedingBirthJobRegistry.TerminalResult terminal) {
        if (terminal.status() != BreedingBirthJobRegistry.TerminalStatus.NOT_FOUND) {
            return CompletableFuture.completedFuture(withoutRollback(terminal, reason));
        }
        List<CompletableFuture<CancellationResult>> existing = cancellationAttempts.findAll(
                storeScope, capturedParentUuid, stableProfileId
        );
        CompletableFuture<Boolean> retained = retainedParentCancellation(
                storeScope, capturedParentUuid, stableProfileId
        );
        if (retained == null && existing.isEmpty()) {
            return CompletableFuture.completedFuture(withoutRollback(terminal, reason));
        }
        List<CompletableFuture<Boolean>> barriers = new ArrayList<>();
        if (retained != null) {
            barriers.add(retained);
        }
        addAttemptBarriers(barriers, existing);
        CompletableFuture<CancellationResult> completion = allDurable(barriers).handle(
                (durable, failure) -> retainedGateResult(reason, durable, failure)
        );
        if (existing.isEmpty()) {
            cancellationAttempts.remember(
                    storeScope, capturedParentUuid, stableProfileId, completion
            );
        }
        return completion;
    }

    @Nullable
    private CompletableFuture<Boolean> retainedParentCancellation(
            Object storeScope,
            UUID capturedParentUuid,
            @Nullable String stableProfileId) {
        try {
            return preparedCancellation.cancelByParent(
                    storeScope,
                    capturedParentUuid,
                    stableProfileId,
                    "breeding-parent-captured-after-job-terminal"
            );
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(false);
        }
    }

    private CancellationResult retainedGateResult(CancellationReason reason,
                                                  Boolean durable,
                                                  Throwable failure) {
        CancellationStatus status = failure == null && Boolean.TRUE.equals(durable)
                ? CancellationStatus.ALREADY_TERMINAL
                : CancellationStatus.DURABILITY_FAILED;
        return new CancellationResult(
                status,
                reason,
                MatchKind.ENTITY_UUID,
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
        );
    }

    private void addPriorAttemptBarriers(
            List<CompletableFuture<Boolean>> barriers,
            Object storeScope,
            UUID capturedParentUuid,
            @Nullable String stableProfileId) {
        addAttemptBarriers(
                barriers,
                cancellationAttempts.findAll(
                        storeScope, capturedParentUuid, stableProfileId
                )
        );
    }

    private void addAttemptBarriers(
            List<CompletableFuture<Boolean>> barriers,
            List<CompletableFuture<CancellationResult>> attempts) {
        for (CompletableFuture<CancellationResult> attempt : attempts) {
            barriers.add(attempt.handle((result, failure) ->
                    failure == null && result != null && result.safeToCapture()
            ));
        }
    }

    private static CompletableFuture<Boolean> allDurable(
            List<CompletableFuture<Boolean>> barriers) {
        CompletableFuture<?>[] waits = barriers.toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(waits).handle((ignored, failure) -> {
            if (failure != null) {
                return false;
            }
            for (CompletableFuture<Boolean> barrier : barriers) {
                if (!Boolean.TRUE.equals(barrier.getNow(false))) {
                    return false;
                }
            }
            return true;
        });
    }

    @Nonnull
    private CancellationResult currentResult(
            CompletableFuture<CancellationResult> completion,
            CancellationReason reason) {
        CancellationResult current = completion.getNow(null);
        return current != null ? current : new CancellationResult(
                CancellationStatus.DURABILITY_PENDING,
                reason,
                MatchKind.NONE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
        );
    }

    private CancellationResult withStatus(CancellationResult source, CancellationStatus status) {
        return new CancellationResult(
                status,
                source.reason(),
                source.matchKind(),
                source.jobId(),
                source.capturedParent(),
                source.partner()
        );
    }

    @Nonnull
    private ParentRollbackReport rollbackSafely(Object storeScope,
                                                BreedingBirthJob job,
                                                boolean firstParent,
                                                UUID liveEntityUuid) {
        BreedingParentIdentity identity = firstParent
                ? job.firstParent()
                : job.secondParent();
        BreedingParentIdentity liveIdentity = identity.entityUuid().equals(liveEntityUuid)
                ? identity
                : new BreedingParentIdentity(liveEntityUuid, identity.profileId());
        try {
            ParentRollbackOutcome outcome = Objects.requireNonNull(
                    parentRollbackGateway.rollback(storeScope, job, firstParent, liveEntityUuid),
                    "parent rollback outcome"
            );
            return new ParentRollbackReport(
                    liveIdentity,
                    outcome.status(),
                    outcome.pairingStateCleared()
            );
        } catch (RuntimeException exception) {
            return new ParentRollbackReport(liveIdentity, ParentRollbackStatus.ERROR, false);
        }
    }

    private void recordCancellationDiagnostics(BreedingBirthJob job,
                                               CancellationReason reason,
                                               ParentRollbackReport first,
                                               ParentRollbackReport second) {
        if (diagnosticsService == null) {
            return;
        }
        diagnosticsService.recordOutcome(
                job.jobId(),
                BreedingJobDiagnosticSnapshot.Outcome.CANCELLED,
                0,
                "capture-cancelled:" + reason,
                combinedRollbackStatus(first.status(), second.status()),
                "first=" + first.status() + ",second=" + second.status()
        );
    }

    private BreedingJobDiagnosticSnapshot.RollbackStatus combinedRollbackStatus(
            ParentRollbackStatus first,
            ParentRollbackStatus second) {
        boolean firstCompleted = rollbackCompleted(first);
        boolean secondCompleted = rollbackCompleted(second);
        if (firstCompleted && secondCompleted) {
            return BreedingJobDiagnosticSnapshot.RollbackStatus.COMPLETED;
        }
        if (firstCompleted || secondCompleted) {
            return BreedingJobDiagnosticSnapshot.RollbackStatus.PARTIAL;
        }
        if (!rollbackFailed(first) && !rollbackFailed(second)) {
            return BreedingJobDiagnosticSnapshot.RollbackStatus.SKIPPED;
        }
        if (!rollbackFailed(first) || !rollbackFailed(second)) {
            return BreedingJobDiagnosticSnapshot.RollbackStatus.PARTIAL;
        }
        return BreedingJobDiagnosticSnapshot.RollbackStatus.FAILED;
    }

    private boolean rollbackCompleted(ParentRollbackStatus status) {
        return status == ParentRollbackStatus.RESTORED;
    }

    private boolean rollbackFailed(ParentRollbackStatus status) {
        return status == ParentRollbackStatus.ERROR
                || status == ParentRollbackStatus.SKIPPED_RESTORE_FAILED;
    }

    @Nonnull
    private CancellationResult withoutRollback(BreedingBirthJobRegistry.TerminalResult terminal,
                                               CancellationReason reason) {
        CancellationStatus status = switch (terminal.status()) {
            case NOT_FOUND -> CancellationStatus.NOT_FOUND;
            case ALREADY_TERMINAL -> CancellationStatus.ALREADY_TERMINAL;
            case SCOPE_CLOSED -> CancellationStatus.SCOPE_CLOSED;
            case NOT_READY, APPLIED -> CancellationStatus.REJECTED;
        };
        return new CancellationResult(
                status,
                reason,
                MatchKind.NONE,
                terminal.job().map(BreedingBirthJob::jobId),
                Optional.empty(),
                Optional.empty()
        );
    }

    private boolean isCapturedParent(BreedingParentIdentity identity,
                                     UUID capturedParentUuid,
                                     @Nullable String stableProfileId,
                                     MatchKind matchKind) {
        if (matchKind == MatchKind.ENTITY_UUID) {
            return identity.entityUuid().equals(capturedParentUuid);
        }
        return matchKind == MatchKind.PROFILE_ID
                && stableProfileId != null
                && identity.profileId().equals(stableProfileId);
    }

    @Nullable
    private static String normalizeProfileId(@Nullable String profileId) {
        if (profileId == null) {
            return null;
        }
        String normalized = profileId.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static PreparedCancellationGateway preparedCancellationGateway(
            com.alechilles.alecstamework.npc.breeding.BreedingPreparedPopulationRegistry registry) {
        Objects.requireNonNull(registry, "preparedPopulationRegistry");
        return new PreparedCancellationGateway() {
            @Override
            public CompletableFuture<Boolean> cancel(Object storeScope,
                                                     UUID jobId,
                                                     String reason) {
                return registry.cancelRemainingDurably(storeScope, jobId, reason);
            }

            @Override
            public CompletableFuture<Boolean> cancelByParent(Object storeScope,
                                                             UUID parentUuid,
                                                             String stableProfileId,
                                                             String reason) {
                return registry.cancelRemainingDurablyByParent(
                        storeScope, parentUuid, stableProfileId, reason
                );
            }

            @Override
            public void releaseParent(Object storeScope,
                                      UUID parentUuid,
                                      String stableProfileId,
                                      boolean captured) {
                registry.releaseCaptureFence(
                        storeScope, parentUuid, stableProfileId, captured
                );
            }
        };
    }

    /** Why an active birth job is being cancelled. */
    public enum CancellationReason {
        COOP_CAPTURE,
        CAPTURE_CRATE
    }

    /** High-level result of the registry-first cancellation. */
    public enum CancellationStatus {
        CANCELLED,
        NOT_FOUND,
        ALREADY_TERMINAL,
        SCOPE_CLOSED,
        REJECTED,
        DURABILITY_PENDING,
        DURABILITY_FAILED
    }

    /** Identity index that found and atomically terminated the active job. */
    public enum MatchKind {
        ENTITY_UUID,
        PROFILE_ID,
        NONE
    }

    /** Outcome of one fingerprint-guarded live-parent restoration attempt. */
    public enum ParentRollbackStatus {
        RESTORED,
        SKIPPED_PARENT_MISSING,
        SKIPPED_IDENTITY_MISMATCH,
        SKIPPED_NEWER_STATE,
        SKIPPED_NO_PROVISIONAL_STATE,
        SKIPPED_RESTORE_FAILED,
        ERROR
    }

    /** Immutable report for either the captured parent or its partner. */
    public record ParentRollbackReport(
            @Nonnull BreedingParentIdentity identity,
            @Nonnull ParentRollbackStatus status,
            boolean pairingStateCleared) {
        public ParentRollbackReport {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(status, "status");
        }

        public boolean restored() {
            return status == ParentRollbackStatus.RESTORED;
        }
    }

    /** Full cancellation result, with rollback reports present only for a newly cancelled job. */
    public record CancellationResult(
            @Nonnull CancellationStatus status,
            @Nonnull CancellationReason reason,
            @Nonnull MatchKind matchKind,
            @Nonnull Optional<UUID> jobId,
            @Nonnull Optional<ParentRollbackReport> capturedParent,
            @Nonnull Optional<ParentRollbackReport> partner) {
        public CancellationResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(matchKind, "matchKind");
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(capturedParent, "capturedParent");
            Objects.requireNonNull(partner, "partner");
        }

        public boolean cancelled() {
            return status == CancellationStatus.CANCELLED;
        }

        /** True only when no replayable child operation can survive the parent capture. */
        public boolean safeToCapture() {
            return status == CancellationStatus.CANCELLED
                    || status == CancellationStatus.NOT_FOUND
                    || status == CancellationStatus.ALREADY_TERMINAL;
        }
    }

    /** Cancellation report paired with the snapshot captured strictly after rollback. */
    public record SnapshotHandoff<T>(@Nonnull CancellationResult cancellation, T snapshot) {
        public SnapshotHandoff {
            Objects.requireNonNull(cancellation, "cancellation");
        }
    }

    /** Synchronous coop snapshot callback. Implementations must not defer work off-thread. */
    @FunctionalInterface
    public interface SnapshotCapture<T> {
        T capture();
    }

    record ParentRollbackOutcome(@Nonnull ParentRollbackStatus status,
                                 boolean pairingStateCleared) {
        ParentRollbackOutcome {
            Objects.requireNonNull(status, "status");
        }
    }

    @FunctionalInterface
    interface ParentRollbackGateway {
        @Nonnull
        ParentRollbackOutcome rollback(@Nonnull Object storeScope,
                                       @Nonnull BreedingBirthJob job,
                                       boolean firstParent,
                                       @Nonnull UUID liveEntityUuid);
    }

    @FunctionalInterface
    interface PreparedCancellationGateway {
        @Nonnull
        CompletableFuture<Boolean> cancel(@Nonnull Object storeScope,
                                          @Nonnull UUID jobId,
                                          @Nonnull String reason);

        @Nullable
        default CompletableFuture<Boolean> cancelByParent(
                @Nonnull Object storeScope,
                @Nonnull UUID parentUuid,
                @Nullable String stableProfileId,
                @Nonnull String reason) {
            return null;
        }

        default void releaseParent(@Nonnull Object storeScope,
                                   @Nonnull UUID parentUuid,
                                   @Nullable String stableProfileId,
                                   boolean captured) {
        }
    }
}
