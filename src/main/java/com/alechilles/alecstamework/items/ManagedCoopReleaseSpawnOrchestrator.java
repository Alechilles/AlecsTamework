package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopReleaseCoordinator.SpawnReady;
import com.alechilles.alecstamework.items.ManagedCoopReleaseProjectionCoordinator.FinalizedProjection;
import com.alechilles.alecstamework.items.ManagedCoopReleaseProjectionCoordinator.OutcomeStatus;
import com.alechilles.alecstamework.items.ManagedCoopReleaseProjectionCoordinator.ProjectionAttempt;
import com.alechilles.alecstamework.items.ManagedCoopReleaseProjectionCoordinator.ProjectionOutcome;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Pure ordering boundary for one managed-coop release projection.
 *
 * <p>The synchronous spawn callback is never retained. Once it returns, this class keeps only an
 * immutable operation/UUID receipt. Persistence completions therefore cannot retain a world store,
 * entity reference, NPC, or other thread-affine runtime object.</p>
 */
public final class ManagedCoopReleaseSpawnOrchestrator {
    /** Result categories exposed to the owning world-thread adapter. */
    public enum Status {
        FINALIZED,
        FINALIZED_PRESENTATION_FAILED,
        DEDUPLICATED,
        SPAWN_FAILED,
        PERSISTENCE_FAILED,
        BLOCKED
    }
    /** Result of the adapter's synchronous live UUID/profile/marker lookup. */
    public enum AdmissionStatus {
        CLEAR_TO_SPAWN,
        MATCHING_MARKED_PROJECTION,
        BLOCKED
    }
    /** Immutable admission result. A matching projection must report its observed UUID. */
    public record Admission(@Nonnull AdmissionStatus status,
                            @Nullable UUID observedTargetUuid,
                            @Nullable String detail) {
        public Admission {
            Objects.requireNonNull(status, "status");
            if (status == AdmissionStatus.MATCHING_MARKED_PROJECTION
                    && observedTargetUuid == null) {
                throw new IllegalArgumentException("matching projection UUID is required");
            }
            if (status != AdmissionStatus.MATCHING_MARKED_PROJECTION
                    && observedTargetUuid != null) {
                throw new IllegalArgumentException("only a matching projection may report a UUID");
            }
        }
        @Nonnull
        public static Admission clearToSpawn() {
            return new Admission(AdmissionStatus.CLEAR_TO_SPAWN, null, null);
        }
        @Nonnull
        public static Admission matching(@Nonnull UUID observedTargetUuid) {
            return new Admission(
                    AdmissionStatus.MATCHING_MARKED_PROJECTION,
                    Objects.requireNonNull(observedTargetUuid, "observedTargetUuid"),
                    null
            );
        }
        @Nonnull
        public static Admission blocked(@Nonnull String detail) {
            return new Admission(AdmissionStatus.BLOCKED, null, requireText(detail, "detail"));
        }
    }
    /** Immutable result returned by the adapter's one synchronous spawn call. */
    public record SpawnAttempt(boolean spawned,
                               @Nullable UUID observedTargetUuid,
                               @Nullable String detail) {
        public SpawnAttempt {
            if (spawned && observedTargetUuid == null) {
                throw new IllegalArgumentException("spawned projection UUID is required");
            }
            if (!spawned && observedTargetUuid != null) {
                throw new IllegalArgumentException("failed spawn cannot report a projection UUID");
            }
        }

        @Nonnull
        public static SpawnAttempt spawned(@Nonnull UUID observedTargetUuid) {
            return new SpawnAttempt(
                    true,
                    Objects.requireNonNull(observedTargetUuid, "observedTargetUuid"),
                    null
            );
        }
        @Nonnull
        public static SpawnAttempt failed(@Nonnull String detail) {
            return new SpawnAttempt(false, null, requireText(detail, "detail"));
        }
    }
    /** Immutable IDs queued for post-finalization world-thread re-resolution. */
    public record PresentationCommand(@Nonnull String operationId,
                                      @Nonnull String profileId,
                                      @Nonnull String residentId,
                                      @Nonnull ManagedCoopAuthorityKey authorityKey,
                                      @Nonnull String coopId,
                                      int residentSlot,
                                      @Nonnull UUID sourceNpcUuid,
                                      @Nonnull UUID plannedTargetUuid,
                                      @Nonnull UUID actualTargetUuid,
                                      @Nonnull String snapshotHash,
                                      long expectedResidentGeneration) {
    }
    /** Immutable orchestration outcome. */
    public record Outcome(@Nonnull Status status,
                          @Nullable UUID actualTargetUuid,
                          boolean spawnedThisAttempt,
                          boolean presentationDispatched,
                          @Nullable String detail) {
        public boolean finalized() {
            return status == Status.FINALIZED
                    || status == Status.FINALIZED_PRESENTATION_FAILED;
        }
    }
    /** Must synchronously spawn at most once and return before this callback leaves the stack. */
    @FunctionalInterface
    public interface SpawnAction {
        @Nonnull
        SpawnAttempt spawn();
    }
    /** Stable-ID persistence boundary; implementations must not retain live runtime objects. */
    @FunctionalInterface
    public interface ProjectionFinalizer {
        @Nonnull
        CompletableFuture<ProjectionOutcome> finalizeProjection(
                @Nonnull SpawnReady claim,
                @Nonnull UUID actualTargetUuid,
                long projectionRecordedAtMs);
    }
    /** Queues immutable IDs for a dispatcher that re-resolves live state on its owning thread. */
    @FunctionalInterface
    public interface PresentationDispatcher {
        void dispatch(@Nonnull PresentationCommand command);
    }
    private final ProjectionFinalizer finalizer;
    private final PresentationDispatcher presentationDispatcher;
    private final ConcurrentMap<String, SpawnReceipt> receipts = new ConcurrentHashMap<>();
    private final Set<String> presentationDispatched = ConcurrentHashMap.newKeySet();

    public ManagedCoopReleaseSpawnOrchestrator(
            @Nonnull ManagedCoopReleaseProjectionCoordinator coordinator,
            @Nonnull PresentationDispatcher presentationDispatcher) {
        this(
                coordinatorFinalizer(coordinator),
                presentationDispatcher
        );
    }

    ManagedCoopReleaseSpawnOrchestrator(
            @Nonnull ProjectionFinalizer finalizer,
            @Nonnull PresentationDispatcher presentationDispatcher) {
        this.finalizer = Objects.requireNonNull(finalizer, "finalizer");
        this.presentationDispatcher = Objects.requireNonNull(
                presentationDispatcher, "presentationDispatcher");
    }

    /**
     * Executes the live admission/spawn portion synchronously, then crosses into persistence using
     * immutable values only. A successful spawn receipt permanently closes the spawn gate.
     */
    @Nonnull
    public CompletableFuture<Outcome> coordinate(
            @Nonnull SpawnReady claim,
            @Nonnull Admission admission,
            @Nonnull SpawnAction spawnAction,
            long projectionRecordedAtMs) {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(admission, "admission");
        Objects.requireNonNull(spawnAction, "spawnAction");
        ClaimFingerprint fingerprint;
        try {
            fingerprint = validateClaim(claim);
        } catch (RuntimeException exception) {
            return completed(Status.BLOCKED, null, false, false,
                    failureDetail("invalid_spawn_claim", exception));
        }
        SpawnSelection selection = selectProjection(
                claim, fingerprint, admission, spawnAction);
        if (!selection.ready()) {
            return completed(
                    selection.status(),
                    selection.actualTargetUuid(),
                    selection.spawnedThisAttempt(),
                    false,
                    selection.detail()
            );
        }
        CompletionContext context = new CompletionContext(
                claim,
                selection.actualTargetUuid(),
                selection.spawnedThisAttempt()
        );
        final CompletableFuture<ProjectionOutcome> completion;
        try {
            completion = finalizer.finalizeProjection(
                    claim, context.actualTargetUuid(), projectionRecordedAtMs);
        } catch (RuntimeException exception) {
            return completed(Status.PERSISTENCE_FAILED, context.actualTargetUuid(),
                    context.spawnedThisAttempt(), false,
                    failureDetail("projection_finalization", exception));
        }
        if (completion == null) {
            return completed(Status.PERSISTENCE_FAILED, context.actualTargetUuid(),
                    context.spawnedThisAttempt(), false,
                    "projection_finalization_completion_missing");
        }
        return completion.handle((outcome, failure) ->
                completeProjection(context, outcome, failure));
    }
    @Nonnull
    CompletableFuture<Outcome> rejected(@Nonnull String detail) {
        return completed(Status.BLOCKED, null, false, false, detail);
    }
    @Nonnull
    private SpawnSelection selectProjection(SpawnReady claim,
                                            ClaimFingerprint fingerprint,
                                            Admission admission,
                                            SpawnAction spawnAction) {
        SpawnReceipt existing = receipts.get(claim.operationId());
        if (existing != null) {
            return resumeExistingReceipt(claim, fingerprint, admission, existing);
        }
        if (admission.status() == AdmissionStatus.BLOCKED) {
            return SpawnSelection.blocked(admission.detail());
        }
        if (admission.status() == AdmissionStatus.MATCHING_MARKED_PROJECTION) {
            return adoptExistingProjection(claim, fingerprint, admission.observedTargetUuid());
        }
        SpawnReceipt reservation = new SpawnReceipt(fingerprint, null);
        SpawnReceipt raced = receipts.putIfAbsent(claim.operationId(), reservation);
        if (raced != null) {
            return resumeExistingReceipt(claim, fingerprint, admission, raced);
        }
        return spawnReserved(claim, reservation, spawnAction);
    }
    @Nonnull
    private SpawnSelection resumeExistingReceipt(SpawnReady claim,
                                                 ClaimFingerprint fingerprint,
                                                 Admission admission,
                                                 SpawnReceipt existing) {
        if (!existing.fingerprint().equals(fingerprint)) {
            return SpawnSelection.blocked("spawn_receipt_identity_conflict");
        }
        if (existing.actualTargetUuid() == null) {
            return SpawnSelection.deduplicated("spawn_already_in_progress");
        }
        if (admission.status() != AdmissionStatus.MATCHING_MARKED_PROJECTION) {
            return SpawnSelection.blocked("spawn_receipt_requires_matching_live_projection");
        }
        if (!existing.actualTargetUuid().equals(admission.observedTargetUuid())) {
            return SpawnSelection.blocked("spawn_receipt_live_uuid_conflict");
        }
        return SpawnSelection.ready(existing.actualTargetUuid(), false);
    }
    @Nonnull
    private SpawnSelection adoptExistingProjection(SpawnReady claim,
                                                    ClaimFingerprint fingerprint,
                                                    UUID observedTargetUuid) {
        if (!claim.plannedTargetUuid().equals(observedTargetUuid)) {
            return SpawnSelection.blocked("marked_projection_uuid_does_not_match_plan");
        }
        SpawnReceipt adopted = new SpawnReceipt(fingerprint, observedTargetUuid);
        SpawnReceipt raced = receipts.putIfAbsent(claim.operationId(), adopted);
        return raced == null
                ? SpawnSelection.ready(observedTargetUuid, false)
                : resumeExistingReceipt(
                        claim,
                        fingerprint,
                        Admission.matching(observedTargetUuid),
                        raced
                );
    }
    @Nonnull
    private SpawnSelection spawnReserved(SpawnReady claim,
                                         SpawnReceipt reservation,
                                         SpawnAction spawnAction) {
        final SpawnAttempt attempt;
        try {
            attempt = spawnAction.spawn();
        } catch (RuntimeException exception) {
            receipts.remove(claim.operationId(), reservation);
            return SpawnSelection.spawnFailed(failureDetail("projection_spawn", exception));
        }
        if (attempt == null || !attempt.spawned() || attempt.observedTargetUuid() == null) {
            receipts.remove(claim.operationId(), reservation);
            String detail = attempt != null ? attempt.detail() : null;
            return SpawnSelection.spawnFailed(
                    detail != null ? detail : "projection_spawn_failed");
        }
        if (!claim.plannedTargetUuid().equals(attempt.observedTargetUuid())) {
            SpawnReceipt mismatch = new SpawnReceipt(
                    reservation.fingerprint(), attempt.observedTargetUuid());
            if (!receipts.replace(claim.operationId(), reservation, mismatch)) {
                return SpawnSelection.blocked("spawn_receipt_publish_conflict");
            }
            return SpawnSelection.spawnFailed("spawned_projection_uuid_does_not_match_plan");
        }
        SpawnReceipt completed = new SpawnReceipt(
                reservation.fingerprint(), attempt.observedTargetUuid());
        if (!receipts.replace(claim.operationId(), reservation, completed)) {
            return SpawnSelection.blocked("spawn_receipt_publish_conflict");
        }
        return SpawnSelection.ready(attempt.observedTargetUuid(), true);
    }
    @Nonnull
    private Outcome completeProjection(CompletionContext context,
                                       @Nullable ProjectionOutcome outcome,
                                       @Nullable Throwable failure) {
        if (failure != null) {
            return outcome(Status.PERSISTENCE_FAILED, context, false,
                    failureDetail("projection_finalization", unwrap(failure)));
        }
        if (outcome == null) {
            return outcome(Status.PERSISTENCE_FAILED, context, false,
                    "projection_finalization_outcome_missing");
        }
        if (outcome.status() == OutcomeStatus.DEDUPLICATED) {
            return outcome(Status.DEDUPLICATED, context, false, outcome.detail());
        }
        if (!outcome.finalized()
                || !matchesFinalized(context, outcome.finalizedProjection())) {
            String detail = outcome.detail() != null
                    ? outcome.detail() : "projection_finalization_rejected";
            return outcome(Status.PERSISTENCE_FAILED, context, false, detail);
        }
        return dispatchPresentation(context);
    }

    @Nonnull
    private Outcome dispatchPresentation(CompletionContext context) {
        SpawnReady claim = context.claim();
        if (!presentationDispatched.add(claim.operationId())) {
            return outcome(Status.FINALIZED, context, false,
                    "presentation_already_dispatched");
        }
        PresentationCommand command = new PresentationCommand(
                claim.operationId(),
                claim.profileId(),
                claim.residentId(),
                claim.authorityKey(),
                claim.coopId(),
                claim.residentSlot(),
                claim.sourceNpcUuid(),
                claim.plannedTargetUuid(),
                context.actualTargetUuid(),
                claim.snapshotHash(),
                claim.expectedResidentGeneration()
        );
        try {
            presentationDispatcher.dispatch(command);
            return outcome(Status.FINALIZED, context, true, null);
        } catch (RuntimeException exception) {
            presentationDispatched.remove(claim.operationId());
            return outcome(Status.FINALIZED_PRESENTATION_FAILED, context, false,
                    failureDetail("presentation_dispatch", exception));
        }
    }

    private boolean matchesFinalized(CompletionContext context,
                                     @Nullable FinalizedProjection finalized) {
        SpawnReady claim = context.claim();
        return finalized != null
                && claim.operationId().equals(finalized.operationId())
                && claim.profileId().equals(finalized.profileId())
                && claim.residentId().equals(finalized.residentId())
                && claim.authorityKey().equals(finalized.authorityKey())
                && claim.coopId().equalsIgnoreCase(finalized.coopId())
                && claim.residentSlot() == finalized.residentSlot()
                && claim.sourceNpcUuid().equals(finalized.sourceNpcUuid())
                && claim.plannedTargetUuid().equals(finalized.plannedTargetUuid())
                && context.actualTargetUuid().equals(finalized.actualTargetUuid())
                && claim.snapshotHash().equals(finalized.snapshotHash())
                && claim.expectedResidentGeneration() == finalized.expectedResidentGeneration();
    }

    @Nonnull
    private ClaimFingerprint validateClaim(SpawnReady claim) {
        if (!claim.spawnRequired()
                || claim.durableState()
                != com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository
                    .OperationState.SPAWN_CLAIMED
                || claim.actualTargetUuid() != null
                || claim.residentSlot() < 0
                || claim.expectedResidentGeneration() < 0L
                || claim.releasingResidentGeneration() != claim.expectedResidentGeneration() + 1L
                || claim.operationGeneration() != 1L) {
            throw new IllegalArgumentException("claim is not an unconsumed spawn reservation");
        }
        requireText(claim.operationId(), "operationId");
        requireText(claim.profileId(), "profileId");
        requireText(claim.residentId(), "residentId");
        requireText(claim.coopId(), "coopId");
        requireText(claim.snapshotHash(), "snapshotHash");
        Objects.requireNonNull(claim.authorityKey(), "authorityKey");
        Objects.requireNonNull(claim.sourceNpcUuid(), "sourceNpcUuid");
        Objects.requireNonNull(claim.plannedTargetUuid(), "plannedTargetUuid");
        if (claim.sourceNpcUuid().equals(claim.plannedTargetUuid())) {
            throw new IllegalArgumentException("planned UUID must differ from source UUID");
        }
        return new ClaimFingerprint(
                claim.profileId(), claim.residentId(), claim.authorityKey(), claim.residentSlot(),
                claim.sourceNpcUuid(), claim.plannedTargetUuid(), claim.snapshotHash(),
                claim.expectedResidentGeneration()
        );
    }

    @Nonnull
    private Outcome outcome(Status status,
                            CompletionContext context,
                            boolean presentationQueued,
                            @Nullable String detail) {
        return new Outcome(status, context.actualTargetUuid(), context.spawnedThisAttempt(),
                presentationQueued, detail);
    }

    @Nonnull
    private CompletableFuture<Outcome> completed(Status status,
                                                 @Nullable UUID actualTargetUuid,
                                                 boolean spawned,
                                                 boolean presentationQueued,
                                                 @Nullable String detail) {
        return CompletableFuture.completedFuture(
                new Outcome(status, actualTargetUuid, spawned, presentationQueued, detail));
    }

    @Nonnull
    private static String requireText(@Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    @Nonnull
    private static String failureDetail(String stage, Throwable failure) {
        String message = failure != null ? failure.getMessage() : null;
        String suffix = message != null && !message.isBlank()
                ? ":" + message : ":" + (failure != null
                    ? failure.getClass().getSimpleName() : "unknown");
        return stage + "_failed" + suffix;
    }

    @Nonnull
    private static Throwable unwrap(Throwable failure) {
        return failure.getCause() != null ? failure.getCause() : failure;
    }

    @Nonnull
    private static ProjectionFinalizer coordinatorFinalizer(
            @Nonnull ManagedCoopReleaseProjectionCoordinator coordinator) {
        Objects.requireNonNull(coordinator, "coordinator");
        return (claim, actualTargetUuid, recordedAtMs) -> coordinator.coordinate(
                new ProjectionAttempt(claim, actualTargetUuid, recordedAtMs));
    }

    private record ClaimFingerprint(String profileId,
                                    String residentId,
                                    ManagedCoopAuthorityKey authorityKey,
                                    int residentSlot,
                                    UUID sourceNpcUuid,
                                    UUID plannedTargetUuid,
                                    String snapshotHash,
                                    long expectedResidentGeneration) {
    }

    private record SpawnReceipt(ClaimFingerprint fingerprint,
                                @Nullable UUID actualTargetUuid) {
    }

    private record CompletionContext(SpawnReady claim,
                                     UUID actualTargetUuid,
                                     boolean spawnedThisAttempt) {
    }

    private record SpawnSelection(@Nonnull Status status,
                                  @Nullable UUID actualTargetUuid,
                                  boolean spawnedThisAttempt,
                                  @Nullable String detail) {
        boolean ready() {
            return actualTargetUuid != null && status == Status.FINALIZED;
        }

        static SpawnSelection ready(UUID actualTargetUuid, boolean spawned) {
            return new SpawnSelection(Status.FINALIZED, actualTargetUuid, spawned, null);
        }

        static SpawnSelection blocked(@Nullable String detail) {
            return new SpawnSelection(Status.BLOCKED, null, false,
                    detail != null ? detail : "live_projection_admission_blocked");
        }

        static SpawnSelection deduplicated(String detail) {
            return new SpawnSelection(Status.DEDUPLICATED, null, false, detail);
        }

        static SpawnSelection spawnFailed(String detail) {
            return new SpawnSelection(Status.SPAWN_FAILED, null, false, detail);
        }
    }
}
