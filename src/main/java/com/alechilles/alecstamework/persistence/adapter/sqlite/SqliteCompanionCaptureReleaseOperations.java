package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseDefinition;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseEventCodec;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseLiveBoundary;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseOutcome;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseRequest;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChangeCodec;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChange;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionEvidence;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** One lifecycle-fenced, receipt-first captured-artifact release workflow. */
public final class SqliteCompanionCaptureReleaseOperations {
    public static final String FEATURE_SCOPE = "companion_capture_release";
    public static final ProjectionEventType EVENT_TYPE =
            new ProjectionEventType("companion_capture_released");

    private final SqliteLiveOperationCoordinator workflow;
    private final SqliteOperationEngine operations;
    private final LongSupplier clock;
    @Nullable
    private final SqliteCaptureReleaseLifecycleAdmission admission;
    private final List<ProjectionConsumer> requiredConsumers;

    public SqliteCompanionCaptureReleaseOperations(
            @Nonnull SqliteOperationEngine operations,
            @Nonnull SqliteOperationPublisher publisher,
            @Nonnull LongSupplier clock,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        this(
                operations,
                publisher,
                clock,
                null,
                null,
                requiredConsumers
        );
    }

    SqliteCompanionCaptureReleaseOperations(
            @Nonnull SqliteOperationEngine operations,
            @Nonnull SqliteOperationPublisher publisher,
            @Nonnull LongSupplier clock,
            @Nullable SqliteOperationReader reader,
            @Nullable SqliteLifecycleAdmissionBinding lifecycleAdmission,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        if (operations == null || publisher == null || clock == null
                || requiredConsumers == null) {
            throw new IllegalArgumentException(
                    "Captured-artifact release dependencies are required"
            );
        }
        workflow = new SqliteLiveOperationCoordinator(
                operations,
                publisher,
                clock
        );
        this.operations = operations;
        this.clock = clock;
        this.admission = reader == null || lifecycleAdmission == null
                ? null
                : new SqliteCaptureReleaseLifecycleAdmission(
                reader, lifecycleAdmission
        );
        this.requiredConsumers = List.copyOf(requiredConsumers);
    }

    /** Starts or resumes one exact inventory- and spawn-receipt-correlated release. */
    @Nonnull
    public Submission submit(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull CompanionCaptureReleaseRequest release,
            @Nonnull CompanionCaptureReleaseLiveBoundary liveBoundary
    ) {
        if (operationId == null || idempotencyKey == null
                || release == null || liveBoundary == null) {
            throw new IllegalArgumentException(
                    "Complete captured-artifact release is required"
            );
        }
        if (!requiresAdmission(release)
                || admission == null) {
            return execute(
                    operationId, idempotencyKey, release, liveBoundary
            );
        }
        return admissionAwareSubmit(
                operationId, idempotencyKey, release, liveBoundary
        );
    }

    private Submission admissionAwareSubmit(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            CompanionCaptureReleaseRequest requested,
            CompanionCaptureReleaseLiveBoundary liveBoundary
    ) {
        CompletionStage<SqliteCaptureReleaseLifecycleAdmission.ResolvedRelease>
                resolved = admission.resolve(
                operationId, idempotencyKey, requested
        );
        CompletionStage<OperationWorkflowResult> completion = resolved
                .thenCompose(value -> execute(
                        value.operationId(),
                        idempotencyKey,
                        value.payload(),
                        liveBoundary
                ).completion());
        return new Submission(
                SqliteSingleWriter.WriteAcceptance.ACCEPTED,
                completion.exceptionally(failure ->
                        SqliteOperationResults.failed(
                                OperationWorkflowResult.Status.PREPARE_FAILED,
                                null,
                                List.of(),
                                SqliteCaptureReleaseLifecycleAdmission
                                        .unwrap(failure)
                        )
                )
        );
    }

    private Submission execute(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            CompanionCaptureReleaseRequest release,
            CompanionCaptureReleaseLiveBoundary liveBoundary
    ) {
        OperationRequest<CompanionCaptureReleaseRequest> request =
                new OperationRequest<>(
                        operationId,
                        idempotencyKey,
                        release,
                        FEATURE_SCOPE,
                        release.expectedLifecycleRevision(),
                        SqliteCaptureReleaseLifecycleAdmission
                                .participantScopes(release),
                        release.requestedAtMs()
                );
        SqliteCompanionCaptureReleasePreparation base =
                new SqliteCompanionCaptureReleasePreparation(release);
        SqliteManagedAdmissionParticipant managed =
                release.admissionEvidence() != null
                        && release.admissionEvidence().status()
                        == LifecycleAdmissionEvidence.Status.MANAGED
                        ? SqliteManagedAdmissionParticipant.from(
                        operationId, release.admissionEvidence()
                ) : null;
        PreparedOperationDetail detail = managed == null
                ? base
                : PreparedOperationDetail.compose(managed, base);
        SqliteManagedAdmissionParticipant managedParticipant = managed;
        SqliteLiveOperationCoordinator.Submission submission =
                workflow.execute(
                        CompanionCaptureReleaseDefinition.INSTANCE,
                        request,
                        detail,
                        liveBoundary,
                        (transaction, operation, payload, releasedAtMs) -> {
                            if (managedParticipant == null) {
                                return commitRelease(
                                        transaction,
                                        operation,
                                        payload,
                                        releasedAtMs
                                );
                            }
                            return managedParticipant.decorate(
                                    (current, envelope) -> commitRelease(
                                            current,
                                            envelope,
                                            payload,
                                            releasedAtMs
                                    )
                            ).execute(transaction, operation);
                        },
                        requiredConsumers,
                        FEATURE_SCOPE
                );
        CompletionStage<OperationWorkflowResult> completion =
                submission.completion().thenCompose(result -> {
                    if (result.status()
                            != OperationWorkflowResult.Status.LIVE_UNKNOWN
                            || result.operation() == null) {
                        return CompletableFuture.completedFuture(result);
                    }
                    return containUnknown(result.operation(), release, result);
                }).thenCompose(result ->
                        SqliteCaptureReleaseLifecycleAdmission
                                .releaseProjectionHold(
                                        liveBoundary, release, result
                                ));
        return new Submission(submission.acceptance(), completion);
    }

    private boolean requiresAdmission(CompanionCaptureReleaseRequest release) {
        return release.orphanRecovery() == null
                && release.legacyRecovery() == null;
    }

    private CompletionStage<OperationWorkflowResult> containUnknown(
            OperationEnvelope operation,
            CompanionCaptureReleaseRequest release,
            OperationWorkflowResult result
    ) {
        return operations.containUnknown(
                operation,
                operation.failureCode() == null
                        ? "capture_release_live_outcome_unknown"
                        : operation.failureCode(),
                "Captured-artifact release could not prove both the exact "
                        + "inventory and entity receipts",
                SqliteCaptureReleaseLifecycleAdmission
                        .containmentScopes(operation, release),
                clock.getAsLong()
        ).completion().thenApply(containment -> {
            if (containment instanceof
                    com.alechilles.alecstamework.persistence.kernel
                    .PersistenceTransactionResult.Committed<?>) {
                return result;
            }
            return new OperationWorkflowResult(
                    OperationWorkflowResult.Status.LIVE_UNKNOWN,
                    operation,
                    List.of(),
                    new IllegalStateException(
                            "capture_release_unknown_containment_failed",
                            result.failure()
                    )
            );
        });
    }

    private List<ProjectionEventDraft> commitRelease(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            CompanionCaptureReleaseRequest release,
            long releasedAtMs
    ) {
        CompanionLifecycle fenced = requireFencedLifecycle(
                transaction,
                operation,
                release
        );
        CompanionProfileProjectionState before =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction,
                        release.profileId()
                );
        requireApplied(
                transaction.identities().promoteAlias(
                        release.targetAlias(),
                        operation.operationId(),
                        releasedAtMs
                ),
                "capture_release_alias_promotion"
        );
        requireApplied(
                transaction.snapshots().retireCurrent(
                        release.modernRecovery() == null
                                ? release.sourceSnapshot().snapshotId()
                                : release.modernRecovery()
                                .supersededSnapshot().snapshotId()
                ),
                "capture_release_snapshot_retirement"
        );
        CompanionLifecycle active = active(
                fenced,
                release,
                releasedAtMs
        );
        requireApplied(
                transaction.lifecycles().transition(new LifecycleTransition(
                        fenced.revision(),
                        operation.operationId(),
                        active
                )),
                "capture_release_lifecycle"
        );
        CompanionProfileProjectionState after =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction,
                        release.profileId()
                );
        return events(
                operation,
                release,
                fenced,
                active,
                before,
                after,
                releasedAtMs
        );
    }

    private CompanionLifecycle active(
            CompanionLifecycle fenced,
            CompanionCaptureReleaseRequest release,
            long releasedAtMs
    ) {
        OwnerId ownerId = release.ownerAssignment() == null
                ? fenced.ownerId()
                : release.ownerAssignment();
        return new CompanionLifecycle(
                release.profileId(),
                ownerId,
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity(
                        release.targetAlias().toString(),
                        release.targetWorldKey()
                ),
                fenced.revision().next(),
                null,
                releasedAtMs,
                fenced.lastReconciledGeneration(),
                fenced.quarantineIncidentId(),
                ownerId == null
                        ? null
                        : release.targetWorldKey()
        );
    }

    private List<ProjectionEventDraft> events(
            OperationEnvelope operation,
            CompanionCaptureReleaseRequest release,
            CompanionLifecycle fenced,
            CompanionLifecycle active,
            CompanionProfileProjectionState before,
            CompanionProfileProjectionState after,
            long releasedAtMs
    ) {
        CompanionCaptureReleaseOutcome outcome =
                new CompanionCaptureReleaseOutcome(
                        release.profileId(),
                        release.sourceSnapshot().snapshotId(),
                        release.targetAlias(),
                        release.targetWorldKey(),
                        active.revision(),
                        release.inventoryReceiptKey(),
                        release.spawnReceiptKey(),
                        releasedAtMs
                );
        CompanionProfileProjectionChange change =
                new CompanionProfileProjectionChange(
                        CompanionProfileProjectionChange.Source.LIFECYCLE,
                        release.profileId(),
                        active.revision().value(),
                        before,
                        after,
                        releasedAtMs
                );
        return List.of(
                new ProjectionEventDraft(
                        operation.operationId(),
                        EVENT_TYPE,
                        "capture-release-result:" + release.profileId(),
                        active.revision().value(),
                        CompanionCaptureReleaseEventCodec.VERSION,
                        CompanionCaptureReleaseEventCodec.encode(outcome),
                        releasedAtMs
                ),
                SqliteCompanionProfileProjectionComposer.event(
                        operation.operationId(),
                        change
                ),
                CompanionLifecycleProjectionChangeCodec.draft(
                        operation.operationId(),
                        fenced,
                        active,
                        releasedAtMs
                )
        );
    }

    private CompanionLifecycle requireFencedLifecycle(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            CompanionCaptureReleaseRequest release
    ) {
        CompanionLifecycle lifecycle = transaction.lifecycles()
                .findByProfile(release.profileId())
                .orElseThrow(() -> new IllegalStateException(
                        "capture_release_lifecycle_missing"
                ));
        boolean sourceStateMatches = release.legacyRecovery() != null
                ? lifecycle.state() == LifecycleState.UNLOADED
                && lifecycle.location().equals(LifecycleLocation.none())
                && lifecycle.lastReconciledGeneration().equals(
                release.legacyRecovery().reconciliationGeneration()
        )
                : lifecycle.state() == LifecycleState.CAPTURED
                && lifecycle.location().equals(LifecycleLocation.keyed(
                LifecycleLocationKind.CAPTURE_ITEM,
                (release.modernRecovery() == null
                        ? release.sourceSnapshot()
                        : release.modernRecovery().supersededSnapshot())
                        .snapshotId().toString()
        ));
        CompanionSnapshot durableSource = release.modernRecovery() == null
                ? release.sourceSnapshot()
                : release.modernRecovery().supersededSnapshot();
        boolean snapshotMatches = release.legacyRecovery() == null
                ? transaction.snapshots()
                .findById(durableSource.snapshotId())
                .filter(durableSource::equals)
                .filter(snapshot -> transaction.snapshots()
                        .findCurrent(
                                release.profileId(),
                                durableSource.kind()
                        )
                        .filter(snapshot::equals)
                        .isPresent())
                .isPresent()
                : transaction.snapshots()
                .findById(
                        release.legacyRecovery()
                                .historicalSnapshot().snapshotId()
                )
                .filter(release.legacyRecovery()
                        .historicalSnapshot()::equals)
                .isPresent()
                && transaction.snapshots()
                .findCurrentByProfile(release.profileId()).isEmpty();
        if (!lifecycle.revision().equals(
                release.expectedLifecycleRevision().next()
        )
                || !sourceStateMatches
                || !operation.operationId().equals(
                lifecycle.activeOperationId()
        )
                || lifecycle.quarantined()
                || !snapshotMatches) {
            throw new IllegalStateException(
                    "capture_release_lifecycle_fence_mismatch"
            );
        }
        return lifecycle;
    }

    private <T> T requireApplied(
            PersistenceMutationResult<T> result,
            String operation
    ) {
        if (result == null || !result.applied()) {
            throw new IllegalStateException(
                    operation + "_" + (result == null
                            ? "null"
                            : result.status().name().toLowerCase())
            );
        }
        return result.value();
    }

    /** Writer admission for preparation plus the eventual exact workflow result. */
    public record Submission(
            @Nonnull SqliteSingleWriter.WriteAcceptance acceptance,
            @Nonnull CompletionStage<OperationWorkflowResult> completion
    ) {
        public Submission {
            if (acceptance == null || completion == null) {
                throw new IllegalArgumentException(
                        "Captured-artifact release submission is incomplete"
                );
            }
        }
    }
}
