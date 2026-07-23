package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureDefinition;
import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureEventCodec;
import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureLiveBoundary;
import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureOutcome;
import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureRequest;
import com.alechilles.alecstamework.companion.coop.CoopOccupancy;
import com.alechilles.alecstamework.companion.coop.CoopResidency;
import com.alechilles.alecstamework.companion.coop.CoopResidencyProjectionChange;
import com.alechilles.alecstamework.companion.coop.CoopResidencyProjectionCodec;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChange;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/** One slot-reserved live-to-coop transition through the shared operation protocol. */
public final class SqliteCompanionCoopCaptureOperations {
    public static final String FEATURE_SCOPE = "companion_coop_capture";
    public static final ProjectionEventType EVENT_TYPE =
            new ProjectionEventType("companion_coop_captured");

    private final SqliteLiveOperationCoordinator workflow;
    private final SqliteCoopUnknownContainment containment;
    private final List<ProjectionConsumer> requiredConsumers;

    public SqliteCompanionCoopCaptureOperations(
            @Nonnull SqliteOperationEngine operations,
            @Nonnull SqliteOperationPublisher publisher,
            @Nonnull LongSupplier clock,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        if (operations == null || publisher == null || clock == null
                || requiredConsumers == null) {
            throw new IllegalArgumentException("Coop capture dependencies are required");
        }
        workflow = new SqliteLiveOperationCoordinator(operations, publisher, clock);
        containment = new SqliteCoopUnknownContainment(operations, clock);
        this.requiredConsumers = List.copyOf(requiredConsumers);
    }

    /** Starts or resumes one exact retirement-receipt-correlated coop capture. */
    @Nonnull
    public Submission submit(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull CompanionCoopCaptureRequest capture,
            @Nonnull CompanionCoopCaptureLiveBoundary liveBoundary
    ) {
        if (operationId == null || idempotencyKey == null
                || capture == null || liveBoundary == null) {
            throw new IllegalArgumentException("Complete coop capture is required");
        }
        OperationRequest<CompanionCoopCaptureRequest> request =
                new OperationRequest<>(
                        operationId,
                        idempotencyKey,
                        capture,
                        FEATURE_SCOPE,
                        capture.expectedLifecycleRevision(),
                        List.of(
                                OperationScope.profile(capture.profileId()),
                                OperationScope.coop(
                                        capture.targetSlot().toString()
                                )
                        ),
                        capture.requestedAtMs()
                );
        SqliteLiveOperationCoordinator.Submission submission =
                workflow.execute(
                        CompanionCoopCaptureDefinition.INSTANCE,
                        request,
                        new SqliteCompanionCoopCapturePreparation(capture),
                        liveBoundary,
                        this::commitCapture,
                        requiredConsumers,
                        "companion_coop_capture"
                );
        CompletionStage<OperationWorkflowResult> completion =
                submission.completion().thenCompose(result ->
                        containment.containIfUnknown(
                                result,
                                capture.profileId(),
                                capture.targetSlot(),
                                "Coop capture could not prove whether the exact "
                                        + "source entity retirement completed"
                        )
                );
        return new Submission(submission.acceptance(), completion);
    }

    private List<ProjectionEventDraft> commitCapture(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            CompanionCoopCaptureRequest capture,
            long capturedAtMs
    ) {
        CompanionLifecycle fenced = requireFencedLifecycle(
                transaction, operation, capture
        );
        CompanionProfileProjectionState before =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction, capture.profileId()
                );
        requireApplied(
                transaction.snapshots().replaceCurrent(capture.snapshot()),
                "coop_capture_snapshot"
        );
        CoopResidency residency = new CoopResidency(
                capture.targetSlot(),
                capture.profileId(),
                capture.source().sourceAlias(),
                capture.snapshot().snapshotId(),
                capturedAtMs,
                capturedAtMs
        );
        CoopOccupancy occupancy = requireApplied(
                transaction.coops().commitCapture(
                        residency, operation.operationId()
                ),
                "coop_capture_residency"
        );
        CompanionLifecycle cooped = new CompanionLifecycle(
                capture.profileId(),
                fenced.ownerId(),
                LifecycleState.COOP,
                LifecycleLocation.keyed(
                        com.alechilles.alecstamework.companion.lifecycle
                                .LifecycleLocationKind.COOP_SLOT,
                        capture.targetSlot().toString()
                ),
                fenced.revision().next(),
                null,
                capturedAtMs,
                fenced.lastReconciledGeneration(),
                fenced.quarantineIncidentId(),
                fenced.ownerWorldKey()
        );
        requireApplied(
                transaction.lifecycles().transition(new LifecycleTransition(
                        fenced.revision(), operation.operationId(), cooped
                )),
                "coop_capture_lifecycle"
        );
        CompanionProfileProjectionState after =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction, capture.profileId()
                );
        return events(
                operation, capture, cooped, occupancy, before, after, capturedAtMs
        );
    }

    private List<ProjectionEventDraft> events(
            OperationEnvelope operation,
            CompanionCoopCaptureRequest capture,
            CompanionLifecycle cooped,
            CoopOccupancy occupancy,
            CompanionProfileProjectionState before,
            CompanionProfileProjectionState after,
            long capturedAtMs
    ) {
        CompanionCoopCaptureOutcome outcome = new CompanionCoopCaptureOutcome(
                capture.profileId(),
                capture.targetSlot(),
                capture.snapshot().snapshotId(),
                cooped.revision(),
                occupancy.slot().residencyRevision(),
                capture.source().retirementReceiptKey(),
                capturedAtMs
        );
        CoopResidencyProjectionChange coopChange =
                new CoopResidencyProjectionChange(
                        capture.targetSlot(),
                        occupancy.slot().residencyRevision(),
                        null,
                        occupancy.residency(),
                        capturedAtMs
                );
        CompanionProfileProjectionChange profileChange =
                new CompanionProfileProjectionChange(
                        CompanionProfileProjectionChange.Source.LIFECYCLE,
                        capture.profileId(),
                        cooped.revision().value(),
                        before,
                        after,
                        capturedAtMs
                );
        return List.of(
                new ProjectionEventDraft(
                        operation.operationId(),
                        EVENT_TYPE,
                        "coop-capture-result:" + capture.profileId(),
                        cooped.revision().value(),
                        CompanionCoopCaptureEventCodec.VERSION,
                        CompanionCoopCaptureEventCodec.encode(outcome),
                        capturedAtMs
                ),
                new ProjectionEventDraft(
                        operation.operationId(),
                        CoopResidencyProjectionCodec.EVENT_TYPE,
                        CoopResidencyProjectionCodec.aggregateId(
                                capture.targetSlot()
                        ),
                        coopChange.slotRevision(),
                        CoopResidencyProjectionCodec.VERSION,
                        CoopResidencyProjectionCodec.encode(coopChange),
                        capturedAtMs
                ),
                SqliteCompanionProfileProjectionComposer.event(
                        operation.operationId(), profileChange
                )
        );
    }

    private CompanionLifecycle requireFencedLifecycle(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            CompanionCoopCaptureRequest capture
    ) {
        CompanionLifecycle lifecycle = transaction.lifecycles()
                .findByProfile(capture.profileId())
                .orElseThrow(() -> new IllegalStateException(
                        "coop_capture_lifecycle_missing"
                ));
        if (!lifecycle.revision().equals(
                capture.expectedLifecycleRevision().next()
        )
                || lifecycle.state() != LifecycleState.ACTIVE
                || !operation.operationId().equals(
                lifecycle.activeOperationId()
        )
                || lifecycle.quarantined()) {
            throw new IllegalStateException(
                    "coop_capture_lifecycle_fence_mismatch"
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
                throw new IllegalArgumentException("Coop capture submission is incomplete");
            }
        }
    }
}
