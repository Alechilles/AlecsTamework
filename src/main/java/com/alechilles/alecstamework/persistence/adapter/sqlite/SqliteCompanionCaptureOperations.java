package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureDefinition;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureEventCodec;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureLiveBoundary;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureOutcome;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChangeCodec;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChange;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.compensation.RefundDeliveryBoundary;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/**
 * One lifecycle-fenced live capture through the shared operation protocol.
 *
 * <p>The operation payload is durable source evidence and the capture snapshot ID is the
 * canonical capture-artifact claim. No independent capture attempt or lifecycle table exists.</p>
 */
public final class SqliteCompanionCaptureOperations {
    public static final String FEATURE_SCOPE = "companion_capture";
    public static final ProjectionEventType EVENT_TYPE =
            new ProjectionEventType("companion_captured");

    private final SqliteLiveOperationCoordinator workflow;
    private final SqliteCaptureCompensation compensation;
    private final List<ProjectionConsumer> requiredConsumers;

    public SqliteCompanionCaptureOperations(
            @Nonnull SqliteOperationEngine operations,
            @Nonnull SqliteOperationPublisher publisher,
            @Nonnull LongSupplier clock,
            @Nonnull RefundDeliveryBoundary refunds,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        if (operations == null || publisher == null || clock == null
                || refunds == null || requiredConsumers == null) {
            throw new IllegalArgumentException("Companion capture dependencies are required");
        }
        workflow = new SqliteLiveOperationCoordinator(operations, publisher, clock);
        compensation = new SqliteCaptureCompensation(
                operations,
                clock,
                refunds
        );
        this.requiredConsumers = List.copyOf(requiredConsumers);
    }

    /** Starts or resumes one exact source-correlated capture. */
    @Nonnull
    public Submission submit(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull CompanionCaptureRequest capture,
            @Nonnull CompanionCaptureLiveBoundary liveBoundary
    ) {
        if (operationId == null || idempotencyKey == null
                || capture == null || liveBoundary == null) {
            throw new IllegalArgumentException("Complete companion capture is required");
        }
        OperationRequest<CompanionCaptureRequest> request = new OperationRequest<>(
                operationId,
                idempotencyKey,
                capture,
                FEATURE_SCOPE,
                capture.expectedLifecycleRevision(),
                List.of(
                        OperationScope.profile(capture.profileId()),
                        OperationScope.owner(OwnerId.parse(
                                capture.source().actorUuid().toString()
                        ))
                ),
                capture.requestedAtMs()
        );
        SqliteLiveOperationCoordinator.Submission submission = workflow.execute(
                CompanionCaptureDefinition.INSTANCE,
                request,
                new CaptureFenceDetail(capture),
                liveBoundary,
                this::commitCapture,
                requiredConsumers,
                "companion_capture"
        );
        CompletionStage<OperationWorkflowResult> completion =
                submission.completion().thenCompose(result -> {
                    OperationEnvelope operation = result.operation();
                    if (operation != null
                            && (result.status()
                            == OperationWorkflowResult.Status.COMPENSATION_REQUIRED
                            || operation.phase() == OperationPhase.COMPENSATING
                            || operation.phase() == OperationPhase.COMPENSATED)) {
                        return compensation.resume(operation, capture);
                    }
                    return java.util.concurrent.CompletableFuture.completedFuture(
                            result
                    );
                });
        return new Submission(submission.acceptance(), completion);
    }

    private List<ProjectionEventDraft> commitCapture(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            CompanionCaptureRequest capture,
            long capturedAtMs
    ) {
        CompanionLifecycle fenced = requireFencedLifecycle(
                transaction,
                operation,
                capture
        );
        CompanionProfileProjectionState before =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction,
                        capture.profileId()
                );
        requireApplied(
                transaction.snapshots().replaceCurrent(capture.snapshot()),
                "capture_snapshot"
        );
        CompanionLifecycle captured = new CompanionLifecycle(
                capture.profileId(),
                capture.resultingOwnerId(),
                LifecycleState.CAPTURED,
                LifecycleLocation.keyed(
                        LifecycleLocationKind.CAPTURE_ITEM,
                        capture.snapshot().snapshotId().toString()
                ),
                fenced.revision().next(),
                null,
                capturedAtMs,
                fenced.lastReconciledGeneration(),
                fenced.quarantineIncidentId(),
                capture.resultingOwnerId() == null
                        ? null
                        : capture.targetWorldKey()
        );
        requireApplied(
                transaction.lifecycles().transition(new LifecycleTransition(
                        fenced.revision(),
                        operation.operationId(),
                        captured
                )),
                "capture_lifecycle"
        );
        CompanionProfileProjectionState after =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction,
                        capture.profileId()
                );
        CompanionCaptureOutcome outcome = new CompanionCaptureOutcome(
                capture.profileId(),
                capture.snapshot().snapshotId(),
                captured.revision(),
                capture.source().receiptKey(),
                capturedAtMs
        );
        CompanionProfileProjectionChange change =
                new CompanionProfileProjectionChange(
                        CompanionProfileProjectionChange.Source.LIFECYCLE,
                        capture.profileId(),
                        captured.revision().value(),
                        before,
                        after,
                        capturedAtMs
                );
        return List.of(
                new ProjectionEventDraft(
                        operation.operationId(),
                        EVENT_TYPE,
                        "capture-result:" + capture.profileId(),
                        captured.revision().value(),
                        CompanionCaptureEventCodec.VERSION,
                        CompanionCaptureEventCodec.encode(outcome),
                        capturedAtMs
                ),
                SqliteCompanionProfileProjectionComposer.event(
                        operation.operationId(),
                        change
                ),
                CompanionLifecycleProjectionChangeCodec.draft(
                        operation.operationId(),
                        fenced,
                        captured,
                        capturedAtMs
                )
        );
    }

    private CompanionLifecycle requireFencedLifecycle(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            CompanionCaptureRequest capture
    ) {
        CompanionLifecycle lifecycle = transaction.lifecycles()
                .findByProfile(capture.profileId())
                .orElseThrow(() -> new IllegalStateException(
                        "capture_lifecycle_missing"
                ));
        if (!lifecycle.revision().equals(
                capture.expectedLifecycleRevision().next()
        ) || !operation.operationId().equals(lifecycle.activeOperationId())
                || lifecycle.quarantined()) {
            throw new IllegalStateException("capture_lifecycle_fence_mismatch");
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

    /** Lifecycle preparation fence accepting its exact later captured state on replay. */
    private static final class CaptureFenceDetail implements PreparedOperationDetail {
        private final CompanionCaptureRequest capture;

        private CaptureFenceDetail(CompanionCaptureRequest capture) {
            this.capture = capture;
        }

        @Override
        public void prepare(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation
        ) {
            CompanionLifecycle current = transaction.lifecycles()
                    .findByProfile(capture.profileId())
                    .orElseThrow(() -> new IllegalStateException(
                            "capture_profile_lifecycle_missing"
                    ));
            requireCapturable(current, transaction);
            CompanionLifecycle fenced = new CompanionLifecycle(
                    current.profileId(),
                    current.ownerId(),
                    current.state(),
                    current.location(),
                    current.revision().next(),
                    operation.operationId(),
                    capture.requestedAtMs(),
                    current.lastReconciledGeneration(),
                    current.quarantineIncidentId(),
                    current.ownerWorldKey()
            );
            PersistenceMutationResult<CompanionLifecycle> result =
                    transaction.lifecycles().transition(new LifecycleTransition(
                            current.revision(),
                            null,
                            fenced
                    ));
            if (!result.applied()) {
                throw new IllegalStateException(
                        "capture_prepare_fence_"
                                + result.status().name().toLowerCase()
                );
            }
        }

        @Override
        public boolean matches(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation
        ) {
            CompanionLifecycle lifecycle = transaction.lifecycles()
                    .findByProfile(capture.profileId())
                    .orElse(null);
            if (lifecycle == null) {
                return false;
            }
            if (lifecycle.revision().equals(
                    capture.expectedLifecycleRevision().next()
            ) && operation.operationId().equals(lifecycle.activeOperationId())) {
                return true;
            }
            if (operation.phase() == OperationPhase.COMPENSATED
                    && SqliteCaptureCompensation.matchesCompleted(
                    transaction,
                    operation,
                    capture
            )) {
                return true;
            }
            return (operation.phase() == OperationPhase.DURABLE
                    || operation.phase() == OperationPhase.PUBLISHED)
                    && lifecycle.revision().equals(
                    capture.expectedLifecycleRevision().next().next()
            ) && lifecycle.activeOperationId() == null
                    && lifecycle.state() == LifecycleState.CAPTURED
                    && lifecycle.location().kind()
                    == LifecycleLocationKind.CAPTURE_ITEM
                    && capture.snapshot().snapshotId().toString().equals(
                    lifecycle.location().key()
            ) && transaction.snapshots()
                    .findById(capture.snapshot().snapshotId())
                    .filter(capture.snapshot()::equals)
                    .isPresent();
        }

        private void requireCapturable(
                CompanionLifecycle current,
                SqlitePersistenceTransactionContext transaction
        ) {
            CompanionAlias alias = transaction.identities()
                    .resolveAlias(capture.targetAlias())
                    .orElse(null);
            boolean exactLiveLocation = current.state() == LifecycleState.ACTIVE
                    && current.location().kind()
                    == LifecycleLocationKind.LIVE_ENTITY
                    && capture.targetAlias().toString().equals(
                    current.location().key()
            ) && capture.targetWorldKey().equals(
                    current.location().worldKey()
            );
            if (!current.revision().equals(
                    capture.expectedLifecycleRevision()
            ) || current.activeOperationId() != null || current.quarantined()
                    || !exactLiveLocation || alias == null
                    || !alias.profileId().equals(capture.profileId())
                    || alias.state() != CompanionAlias.State.CURRENT) {
                throw new IllegalStateException(
                        "capture_prepare_not_exact_live_profile"
                );
            }
        }
    }

    /** Writer admission for atomic preparation plus the eventual exact workflow result. */
    public record Submission(
            @Nonnull SqliteSingleWriter.WriteAcceptance acceptance,
            @Nonnull CompletionStage<OperationWorkflowResult> completion
    ) {
        public Submission {
            if (acceptance == null || completion == null) {
                throw new IllegalArgumentException("Companion capture submission is incomplete");
            }
        }
    }
}
