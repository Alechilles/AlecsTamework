package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.RetirementReady;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationResult;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationKind;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Retires the exact live source of a durably housed managed-coop capture.
 *
 * <p>World work is scheduled by name and carries only {@link RetirementCommand}. Persistence
 * continuations likewise retain no world, store, reference, NPC, or component. A capture becomes
 * complete only after the source is absent or an entity-removal callback supplies the exact
 * persistent capture-source marker.</p>
 */
public final class ManagedCoopCaptureSourceRetirementService {
    public enum OutcomeStatus {
        COMPLETED,
        ALREADY_COMPLETE,
        BLOCKED,
        FAILED
    }
    public enum LiveSourceStatus {
        ABSENT,
        DESPAWN_REQUESTED,
        CONFLICT,
        UNAVAILABLE
    }
    /** Immutable identity used for every world-thread and persistence boundary. */
    public record RetirementCommand(@Nonnull UUID sourceNpcUuid,
                                    @Nonnull String profileId,
                                    @Nonnull String residentId,
                                    @Nonnull String operationId,
                                    @Nonnull ManagedCoopAuthorityKey authorityKey,
                                    @Nonnull String coopId,
                                    int residentSlot,
                                    @Nonnull String snapshotHash,
                                    long expectedResidentGeneration,
                                    long operationGeneration) {
        public RetirementCommand {
            Objects.requireNonNull(sourceNpcUuid, "sourceNpcUuid");
            requireText(profileId, "profileId");
            requireText(residentId, "residentId");
            requireText(operationId, "operationId");
            Objects.requireNonNull(authorityKey, "authorityKey");
            requireText(coopId, "coopId");
            requireText(snapshotHash, "snapshotHash");
            if (residentSlot < 0 || expectedResidentGeneration < 0L
                    || operationGeneration < 0L) {
                throw new IllegalArgumentException("retirement generations and slot must be valid");
            }
        }

        @Nonnull
        public String worldName() {
            return authorityKey.worldName();
        }

        @Nonnull
        public String authoritySlotKey() {
            return authorityKey.slotKey(residentSlot);
        }
    }
    /** Immutable copy made by the removal RefSystem through its callback command buffer. */
    public record RemovalObservation(@Nonnull UUID removedNpcUuid,
                                     @Nullable String profileId,
                                     @Nullable String operationId,
                                     @Nullable String projectionKind,
                                     @Nullable String authoritySlotKey,
                                     @Nullable UUID markerSourceNpcUuid,
                                     long operationGeneration) {
        public RemovalObservation {
            Objects.requireNonNull(removedNpcUuid, "removedNpcUuid");
        }
    }
    public record Outcome(@Nonnull OutcomeStatus status,
                          @Nullable RetirementCommand command,
                          @Nullable String detail) {
        public Outcome {
            Objects.requireNonNull(status, "status");
        }
    }
    public record LiveSourceDecision(@Nonnull LiveSourceStatus status,
                                     @Nullable String detail) {
        public LiveSourceDecision {
            Objects.requireNonNull(status, "status");
        }

        @Nonnull
        public static LiveSourceDecision absent() {
            return new LiveSourceDecision(LiveSourceStatus.ABSENT, null);
        }

        @Nonnull
        public static LiveSourceDecision despawnRequested() {
            return new LiveSourceDecision(LiveSourceStatus.DESPAWN_REQUESTED, null);
        }

        @Nonnull
        public static LiveSourceDecision conflict(String detail) {
            return new LiveSourceDecision(LiveSourceStatus.CONFLICT, detail);
        }

        @Nonnull
        public static LiveSourceDecision unavailable(String detail) {
            return new LiveSourceDecision(LiveSourceStatus.UNAVAILABLE, detail);
        }
    }
    enum EvidenceStatus {
        ACTIVE,
        ALREADY_COMPLETE,
        REJECTED
    }

    record EvidenceDecision(@Nonnull EvidenceStatus status,
                            @Nullable RetirementCommand command,
                            @Nullable String detail) {
        static EvidenceDecision active(RetirementCommand command) {
            return new EvidenceDecision(EvidenceStatus.ACTIVE, command, null);
        }

        static EvidenceDecision alreadyComplete() {
            return new EvidenceDecision(EvidenceStatus.ALREADY_COMPLETE, null, null);
        }

        static EvidenceDecision rejected(String detail) {
            return new EvidenceDecision(EvidenceStatus.REJECTED, null, detail);
        }
    }
    private final StateEvidenceGateway evidence;
    private final WorldGateway worlds;
    private final CompletionGateway completions;
    private final RefreshGateway refresh;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, PendingRetirement> pendingByOperation =
            new ConcurrentHashMap<>();
    public ManagedCoopCaptureSourceRetirementService(
            @Nonnull CoopLifecycleOperationRepository repository,
            @Nonnull ManagedCoopCompositeIndexRefreshService compositeIndexes,
            @Nonnull ManagedCoopResidentIndex residentIndex,
            @Nonnull ManagedCoopLifecycleOperationIndex operationIndex) {
        this(
                new ManagedCoopCaptureRetirementIndexEvidence(
                        compositeIndexes::isTrusted, residentIndex, operationIndex),
                new HytaleManagedCoopCaptureSourceGateway(),
                repositoryGateway(repository),
                compositeRefresh(compositeIndexes),
                System::currentTimeMillis
        );
    }

    ManagedCoopCaptureSourceRetirementService(@Nonnull StateEvidenceGateway evidence,
                                              @Nonnull WorldGateway worlds,
                                              @Nonnull CompletionGateway completions,
                                              @Nonnull RefreshGateway refresh,
                                              @Nonnull LongSupplier clock) {
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.completions = Objects.requireNonNull(completions, "completions");
        this.refresh = Objects.requireNonNull(refresh, "refresh");
        this.clock = Objects.requireNonNull(clock, "clock");
    }
    /** Enqueues retirement on the source authority's world without retaining any live ECS state. */
    @Nonnull
    public CompletableFuture<Outcome> retire(@Nullable RetirementReady ready) {
        final EvidenceDecision resolved;
        try {
            resolved = evidence.resolve(ready);
        } catch (RuntimeException exception) {
            return completedBlocked(detail("retirement_evidence_failed", exception));
        }
        if (resolved.status() == EvidenceStatus.ALREADY_COMPLETE) {
            return CompletableFuture.completedFuture(
                    new Outcome(OutcomeStatus.ALREADY_COMPLETE, null, null));
        }
        if (resolved.status() != EvidenceStatus.ACTIVE || resolved.command() == null) {
            return completedBlocked(resolved.detail());
        }
        RetirementCommand command = resolved.command();
        PendingAdmission admission = admit(command);
        if (admission.conflict() != null) {
            return completedBlocked(admission.conflict());
        }
        if (!admission.created()) {
            return admission.pending().completion;
        }
        try {
            boolean queued = worlds.enqueue(
                    command.worldName(), () -> processOnWorldThread(command));
            if (!queued) {
                finish(admission.pending(), OutcomeStatus.FAILED,
                        "source_world_enqueue_rejected");
            }
        } catch (RuntimeException exception) {
            finish(admission.pending(), OutcomeStatus.FAILED,
                    detail("source_world_enqueue_failed", exception));
        }
        return admission.pending().completion;
    }
    /** Confirms an exact marked removal; mismatched or untrusted observations fail closed. */
    @Nonnull
    public CompletableFuture<Outcome> confirmRemoved(@Nullable RemovalObservation observation) {
        final EvidenceDecision resolved;
        try {
            resolved = evidence.resolve(observation);
        } catch (RuntimeException exception) {
            return completedBlocked(detail("removal_evidence_failed", exception));
        }
        if (resolved.status() != EvidenceStatus.ACTIVE || resolved.command() == null) {
            return completedBlocked(resolved.detail());
        }
        PendingAdmission admission = admit(resolved.command());
        if (admission.conflict() != null) {
            return completedBlocked(admission.conflict());
        }
        completeDurably(admission.pending());
        return admission.pending().completion;
    }

    private void processOnWorldThread(RetirementCommand command) {
        PendingRetirement pending = pendingByOperation.get(command.operationId());
        if (pending == null || !pending.command.equals(command) || pending.completion.isDone()) {
            return;
        }
        EvidenceDecision current = revalidate(
                pending, "retirement_revalidation_failed");
        if (current == null) {
            return;
        }
        if (current.status() != EvidenceStatus.ACTIVE || !command.equals(current.command())) {
            finish(pending, OutcomeStatus.BLOCKED,
                    fallback(current.detail(), "retirement_evidence_changed"));
            return;
        }
        final LiveSourceDecision live;
        try {
            live = worlds.retire(command);
        } catch (RuntimeException exception) {
            finish(pending, OutcomeStatus.FAILED,
                    detail("source_retirement_failed", exception));
            return;
        }
        if (live == null) {
            finish(pending, OutcomeStatus.FAILED, "source_retirement_result_missing");
        } else if (live.status() == LiveSourceStatus.ABSENT) {
            completeDurably(pending);
        } else if (live.status() == LiveSourceStatus.CONFLICT) {
            finish(pending, OutcomeStatus.BLOCKED,
                    fallback(live.detail(), "source_marker_conflict"));
        } else if (live.status() == LiveSourceStatus.UNAVAILABLE) {
            finish(pending, OutcomeStatus.FAILED,
                    fallback(live.detail(), "source_lookup_unavailable"));
        }
    }

    private void completeDurably(PendingRetirement pending) {
        if (!pending.beginCompletion()) {
            return;
        }
        EvidenceDecision current = revalidate(
                pending, "completion_revalidation_failed");
        if (current == null) {
            return;
        }
        if (current.status() != EvidenceStatus.ACTIVE
                || !pending.command.equals(current.command())) {
            finish(pending, OutcomeStatus.BLOCKED,
                    fallback(current.detail(), "completion_evidence_changed"));
            return;
        }
        final CompletionStage<MutationResult> stage;
        try {
            stage = completions.complete(pending.command, clock.getAsLong());
        } catch (RuntimeException exception) {
            finish(pending, OutcomeStatus.FAILED,
                    detail("capture_completion_failed", exception));
            return;
        }
        if (stage == null) {
            finish(pending, OutcomeStatus.FAILED, "capture_completion_stage_missing");
            return;
        }
        stage.whenComplete((mutation, failure) -> afterCompletion(pending, mutation, failure));
    }

    @Nullable
    private EvidenceDecision revalidate(PendingRetirement pending, String stage) {
        try {
            return evidence.revalidate(pending.command);
        } catch (RuntimeException exception) {
            finish(pending, OutcomeStatus.BLOCKED, detail(stage, exception));
            return null;
        }
    }

    private void afterCompletion(PendingRetirement pending,
                                 @Nullable MutationResult mutation,
                                 @Nullable Throwable failure) {
        if (failure != null) {
            finish(pending, OutcomeStatus.FAILED,
                    detail("capture_completion_failed", unwrap(failure)));
            return;
        }
        String invalid = validateCompletedOperation(pending.command, mutation);
        if (invalid != null) {
            finish(pending, OutcomeStatus.FAILED, invalid);
            return;
        }
        final RefreshDecision refreshed;
        try {
            refreshed = refresh.refresh();
        } catch (RuntimeException exception) {
            finish(pending, OutcomeStatus.FAILED,
                    detail("capture_completion_refresh_failed", exception));
            return;
        }
        if (refreshed == null || !refreshed.refreshed()) {
            finish(pending, OutcomeStatus.FAILED,
                    refreshed == null
                            ? "capture_completion_refresh_result_missing"
                            : fallback(refreshed.detail(), "capture_completion_refresh_rejected"));
            return;
        }
        finish(pending, OutcomeStatus.COMPLETED, null);
    }

    @Nullable
    private String validateCompletedOperation(RetirementCommand command,
                                              @Nullable MutationResult mutation) {
        if (mutation == null || !mutation.succeeded() || mutation.operation() == null) {
            return "capture_completion_not_committed";
        }
        OperationRecord operation = mutation.operation();
        long completedGeneration;
        try {
            completedGeneration = Math.addExact(command.operationGeneration(), 1L);
        } catch (ArithmeticException exception) {
            return "capture_completion_generation_overflow";
        }
        boolean exact = operation.kind() == OperationKind.CAPTURE
                && operation.state() == OperationState.COMPLETE
                && !operation.active()
                && operation.operationId().equals(command.operationId())
                && operation.profileId().equals(command.profileId())
                && operation.authorityKey().equals(command.authorityKey())
                && operation.coopId().equals(command.coopId())
                && operation.residentSlot() == command.residentSlot()
                && Objects.equals(operation.sourceNpcUuid(), command.sourceNpcUuid())
                && Objects.equals(operation.snapshotHash(), command.snapshotHash())
                && operation.expectedResidentGeneration()
                    == command.expectedResidentGeneration()
                && operation.generation() == completedGeneration;
        return exact ? null : "capture_completion_identity_mismatch";
    }

    private PendingAdmission admit(RetirementCommand command) {
        PendingRetirement proposed = new PendingRetirement(command);
        PendingRetirement existing = pendingByOperation.putIfAbsent(
                command.operationId(), proposed);
        if (existing == null) {
            return new PendingAdmission(proposed, true, null);
        }
        return existing.command.equals(command)
                ? new PendingAdmission(existing, false, null)
                : new PendingAdmission(existing, false,
                    "retirement_operation_identity_conflict");
    }

    private void finish(PendingRetirement pending,
                        OutcomeStatus status,
                        @Nullable String detail) {
        pending.completion.complete(new Outcome(status, pending.command, detail));
        pendingByOperation.remove(pending.command.operationId(), pending);
    }

    @Nonnull
    private CompletableFuture<Outcome> completedBlocked(@Nullable String detail) {
        return CompletableFuture.completedFuture(new Outcome(
                OutcomeStatus.BLOCKED, null,
                fallback(detail, "retirement_evidence_rejected")));
    }

    private static CompletionGateway repositoryGateway(
            CoopLifecycleOperationRepository repository) {
        CoopLifecycleOperationRepository required = Objects.requireNonNull(
                repository, "repository");
        return (command, nowMs) -> committed(required.completeCapture(
                command.operationId(), command.operationGeneration(), nowMs));
    }

    private static CompletionStage<MutationResult> committed(
            PersistenceWriteQueue.WriteSubmission<MutationResult> submission) {
        if (submission == null || submission.completion() == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("capture_completion_submission_missing"));
        }
        return submission.completion().thenCompose(outcome -> {
            if (outcome == null || !outcome.isCommitted() || outcome.value() == null) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        outcome == null || outcome.failureReason() == null
                                ? "capture_completion_not_committed"
                                : outcome.failureReason()));
            }
            return CompletableFuture.completedFuture(outcome.value());
        });
    }

    private static RefreshGateway compositeRefresh(
            ManagedCoopCompositeIndexRefreshService composite) {
        ManagedCoopCompositeIndexRefreshService required = Objects.requireNonNull(
                composite, "compositeIndexes");
        return () -> {
            ManagedCoopCompositeIndexRefreshService.RefreshResult result = required.refresh();
            return new RefreshDecision(
                    result != null && result.refreshed() && required.isTrusted(),
                    result != null ? result.detail() : "composite_refresh_result_missing");
        };
    }

    private static String detail(String stage, Throwable failure) {
        String message = failure != null ? failure.getMessage() : null;
        return stage + (message == null || message.isBlank() ? "" : ":" + message);
    }

    private static Throwable unwrap(Throwable failure) {
        return failure.getCause() != null ? failure.getCause() : failure;
    }

    private static String fallback(@Nullable String detail, String fallback) {
        return detail == null || detail.isBlank() ? fallback : detail;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    interface StateEvidenceGateway {
        EvidenceDecision resolve(@Nullable RetirementReady ready);

        EvidenceDecision resolve(@Nullable RemovalObservation observation);

        EvidenceDecision revalidate(@Nonnull RetirementCommand command);
    }

    interface WorldGateway {
        boolean enqueue(@Nonnull String worldName, @Nonnull Runnable task);

        @Nonnull
        LiveSourceDecision retire(@Nonnull RetirementCommand command);
    }

    interface CompletionGateway {
        CompletionStage<MutationResult> complete(
                @Nonnull RetirementCommand command, long nowMs);
    }

    interface RefreshGateway {
        RefreshDecision refresh();
    }

    record RefreshDecision(boolean refreshed, @Nullable String detail) {
    }

    private static final class PendingRetirement {
        private final RetirementCommand command;
        private final CompletableFuture<Outcome> completion = new CompletableFuture<>();
        private final AtomicBoolean completionStarted = new AtomicBoolean();

        private PendingRetirement(RetirementCommand command) {
            this.command = command;
        }

        private boolean beginCompletion() {
            return completionStarted.compareAndSet(false, true);
        }
    }

    private record PendingAdmission(@Nonnull PendingRetirement pending,
                                    boolean created,
                                    @Nullable String conflict) {
    }
}
