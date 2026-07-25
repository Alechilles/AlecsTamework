package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.capture.CaptureAttemptResolutionEventCodec;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureEventCodec;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureOutcome;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChangeCodec;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChange;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import java.util.ArrayList;
import java.util.List;

/** Atomic durable effects for each terminal variant of one capture operation. */
final class SqliteCompanionCaptureCommit {
    static final ProjectionEventType CAPTURED_EVENT_TYPE =
            new ProjectionEventType("companion_captured");
    static final ProjectionEventType ATTEMPT_EVENT_TYPE =
            CaptureAttemptResolutionEventCodec.EVENT_TYPE;
    private final SqliteCompanionCaptureTameCommit tame =
            new SqliteCompanionCaptureTameCommit();

    List<ProjectionEventDraft> commit(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            CompanionCaptureRequest capture,
        long committedAtMs
    ) {
        ArrayList<ProjectionEventDraft> events = new ArrayList<>();
        if (capture.failedAttempt()) {
            events.add(attemptEvent(operation, capture, committedAtMs));
            return List.copyOf(events);
        }
        if (capture.tameAndCommandLink()) {
            events.addAll(tame.commit(
                    transaction,
                    operation,
                    capture.tameAndLinkEvidence(),
                    committedAtMs
            ));
        } else {
            events.addAll(commitCaptured(
                    transaction, operation, capture, committedAtMs
            ));
        }
        events.add(attemptEvent(operation, capture, committedAtMs));
        return List.copyOf(events);
    }

    private List<ProjectionEventDraft> commitCaptured(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            CompanionCaptureRequest capture,
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
                transaction.snapshots().replaceCurrent(
                        capture.snapshot()
                ),
                "capture_snapshot"
        );
        CompanionLifecycle captured = capturedLifecycle(
                fenced, capture, capturedAtMs
        );
        requireApplied(
                transaction.lifecycles().transition(
                        new LifecycleTransition(
                                fenced.revision(),
                                operation.operationId(),
                                captured
                        )
                ),
                "capture_lifecycle"
        );
        CompanionProfileProjectionState after =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction, capture.profileId()
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
                capturedEvent(
                        operation, capture, captured, capturedAtMs
                ),
                SqliteCompanionProfileProjectionComposer.event(
                        operation.operationId(), change
                ),
                CompanionLifecycleProjectionChangeCodec.draft(
                        operation.operationId(),
                        fenced,
                        captured,
                        capturedAtMs
                )
        );
    }

    private CompanionLifecycle capturedLifecycle(
            CompanionLifecycle fenced,
            CompanionCaptureRequest capture,
            long capturedAtMs
    ) {
        return new CompanionLifecycle(
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
    }

    private ProjectionEventDraft attemptEvent(
            OperationEnvelope operation,
            CompanionCaptureRequest capture,
            long committedAtMs
    ) {
        return new ProjectionEventDraft(
                operation.operationId(),
                ATTEMPT_EVENT_TYPE,
                "capture-attempt:" + capture.resolution().attemptId(),
                1,
                CaptureAttemptResolutionEventCodec.VERSION,
                CaptureAttemptResolutionEventCodec.encode(
                        operation.operationId(),
                        operation.idempotencyKey(),
                        capture,
                        committedAtMs
                ),
                committedAtMs
        );
    }

    private ProjectionEventDraft capturedEvent(
            OperationEnvelope operation,
            CompanionCaptureRequest capture,
            CompanionLifecycle captured,
            long capturedAtMs
    ) {
        CompanionCaptureOutcome outcome = new CompanionCaptureOutcome(
                capture.profileId(),
                capture.snapshot().snapshotId(),
                captured.revision(),
                capture.source().receiptKey(),
                capturedAtMs
        );
        return new ProjectionEventDraft(
                operation.operationId(),
                CAPTURED_EVENT_TYPE,
                "capture-result:" + capture.profileId(),
                captured.revision().value(),
                CompanionCaptureEventCodec.VERSION,
                CompanionCaptureEventCodec.encode(outcome),
                capturedAtMs
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
        )
                || !operation.operationId().equals(
                lifecycle.activeOperationId()
        )
                || lifecycle.quarantined()) {
            throw new IllegalStateException(
                    "capture_lifecycle_fence_mismatch"
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
}
