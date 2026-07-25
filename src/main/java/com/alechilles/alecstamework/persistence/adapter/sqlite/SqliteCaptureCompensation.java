package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.persistence.compensation.PreparedCompensationDetail;
import com.alechilles.alecstamework.persistence.compensation.RefundClaim;
import com.alechilles.alecstamework.persistence.compensation.RefundDeliveryBoundary;
import com.alechilles.alecstamework.persistence.compensation.RefundItem;
import com.alechilles.alecstamework.persistence.compensation.TimedCompensatedOperationWork;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/**
 * Capture-specific claim and lifecycle cleanup plugged into the shared compensation workflow.
 *
 * <p>This collaborator owns no phase decisions. It only describes the deterministic refund and
 * restores the already-fenced lifecycle after the capture boundary proves the target stayed live.</p>
 */
final class SqliteCaptureCompensation {
    private static final String REASON = "capture_aborted";

    private final SqliteCompensationCoordinator coordinator;
    private final RefundDeliveryBoundary refunds;

    SqliteCaptureCompensation(
            @Nonnull SqliteOperationEngine operations,
            @Nonnull LongSupplier clock,
            @Nonnull RefundDeliveryBoundary refunds
    ) {
        if (operations == null || clock == null || refunds == null) {
            throw new IllegalArgumentException(
                    "Capture compensation dependencies are required"
            );
        }
        this.coordinator = new SqliteCompensationCoordinator(
                operations,
                clock
        );
        this.refunds = refunds;
    }

    @Nonnull
    CompletionStage<OperationWorkflowResult> resume(
            @Nonnull OperationEnvelope operation,
            @Nonnull CompanionCaptureRequest capture
    ) {
        RefundClaim claim = claim(operation.operationId(), capture);
        return coordinator.resume(
                operation,
                claim,
                new RefundDetail(claim),
                refunds,
                new CaptureCompensatedWork(capture),
                "companion_capture_refund"
        );
    }

    static boolean matchesCompleted(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            CompanionCaptureRequest capture
    ) {
        RefundClaim expected = claim(operation.operationId(), capture);
        RefundClaim actual = transaction.refunds()
                .findByOperation(operation.operationId())
                .orElse(null);
        CompanionLifecycle lifecycle = transaction.lifecycles()
                .findByProfile(capture.profileId())
                .orElse(null);
        if (actual == null
                || !actual.delivered()
                || !sameClaim(expected, actual)
                || lifecycle == null) {
            return false;
        }
        if (capture.failedAttempt()) {
            return lifecycle.revision().equals(
                    capture.expectedLifecycleRevision()
            )
                    && lifecycle.activeOperationId() == null
                    && lifecycle.state() == LifecycleState.ACTIVE
                    && lifecycle.location().equals(
                    LifecycleLocation.liveEntity(
                            capture.targetAlias().toString(),
                            capture.targetWorldKey()
                    )
            );
        }
        return actual != null
                && actual.delivered()
                && sameClaim(expected, actual)
                && lifecycle.state() == LifecycleState.ACTIVE
                && lifecycle.revision().equals(
                        capture.expectedLifecycleRevision().next().next()
                )
                && lifecycle.activeOperationId() == null
                && lifecycle.location().equals(LifecycleLocation.liveEntity(
                        capture.targetAlias().toString(),
                        capture.targetWorldKey()
                ))
                && transaction.snapshots()
                .findById(capture.snapshot().snapshotId())
                .isEmpty();
    }

    private static RefundClaim claim(
            OperationId operationId,
            CompanionCaptureRequest capture
    ) {
        return new RefundClaim(
                operationId,
                capture.source().actorUuid(),
                capture.source().worldKey(),
                List.of(new RefundItem(
                        capture.source().sourceItemId(),
                        capture.source().spentQuantity()
                )),
                REASON,
                "capture-refund:" + operationId,
                capture.requestedAtMs(),
                null,
                null
        );
    }

    private static boolean sameClaim(
            RefundClaim expected,
            RefundClaim actual
    ) {
        return expected.operationId().equals(actual.operationId())
                && expected.recipientUuid().equals(actual.recipientUuid())
                && expected.recipientWorldKey().equals(
                        actual.recipientWorldKey()
                )
                && expected.items().equals(actual.items())
                && expected.reasonCode().equals(actual.reasonCode())
                && expected.receiptKey().equals(actual.receiptKey())
                && expected.claimedAtMs() == actual.claimedAtMs();
    }

    private static final class RefundDetail
            implements PreparedCompensationDetail {
        private final RefundClaim claim;

        private RefundDetail(RefundClaim claim) {
            this.claim = claim;
        }

        @Override
        public void prepare(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation,
                long preparedAtMs
        ) {
            if (!transaction.refunds().create(claim).applied()) {
                throw new IllegalStateException(
                        "capture_refund_claim_rejected"
                );
            }
        }

        @Override
        public boolean matches(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation
        ) {
            return transaction.refunds()
                    .findByOperation(operation.operationId())
                    .filter(actual -> sameClaim(claim, actual))
                    .isPresent();
        }
    }

    private static final class CaptureCompensatedWork
            implements TimedCompensatedOperationWork<RefundClaim> {
        private final CompanionCaptureRequest capture;

        private CaptureCompensatedWork(CompanionCaptureRequest capture) {
            this.capture = capture;
        }

        @Override
        public void execute(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation,
                RefundClaim claim,
                String liveEvidence,
                long compensatedAtMs
        ) {
            if (!transaction.refunds().complete(
                    operation.operationId(),
                    claim.receiptKey(),
                    liveEvidence,
                    compensatedAtMs
            ).applied()) {
                throw new IllegalStateException(
                        "capture_refund_completion_rejected"
                );
            }
            if (capture.failedAttempt()) {
                requireUnchanged(transaction);
                return;
            }
            CompanionLifecycle fenced = requireFenced(
                    transaction,
                    operation
            );
            CompanionLifecycle restored = new CompanionLifecycle(
                    fenced.profileId(),
                    fenced.ownerId(),
                    LifecycleState.ACTIVE,
                    LifecycleLocation.liveEntity(
                            capture.targetAlias().toString(),
                            capture.targetWorldKey()
                    ),
                    fenced.revision().next(),
                    null,
                    compensatedAtMs,
                    fenced.lastReconciledGeneration(),
                    fenced.quarantineIncidentId(),
                    fenced.ownerId() == null ? null : capture.targetWorldKey()
            );
            if (!transaction.lifecycles().transition(new LifecycleTransition(
                    fenced.revision(),
                    operation.operationId(),
                    restored
            )).applied()) {
                throw new IllegalStateException(
                        "capture_compensation_lifecycle_rejected"
                );
            }
        }

        @Override
        public boolean matches(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation,
                RefundClaim claim
        ) {
            return matchesCompleted(
                    transaction,
                    operation,
                    capture
            );
        }

        private void requireUnchanged(
                SqlitePersistenceTransactionContext transaction
        ) {
            CompanionLifecycle lifecycle = transaction.lifecycles()
                    .findByProfile(capture.profileId())
                    .orElseThrow(() -> new IllegalStateException(
                            "capture_compensation_lifecycle_missing"
                    ));
            CompanionAlias alias = transaction.identities()
                    .resolveAlias(capture.targetAlias())
                    .orElse(null);
            if (!lifecycle.revision().equals(
                    capture.expectedLifecycleRevision()
            )
                    || lifecycle.activeOperationId() != null
                    || lifecycle.quarantined()
                    || lifecycle.state() != LifecycleState.ACTIVE
                    || !lifecycle.location().equals(
                    LifecycleLocation.liveEntity(
                            capture.targetAlias().toString(),
                            capture.targetWorldKey()
                    )
            )
                    || alias == null
                    || alias.state() != CompanionAlias.State.CURRENT
                    || !alias.profileId().equals(capture.profileId())) {
                throw new IllegalStateException(
                        "capture_compensation_unchanged_lifecycle_mismatch"
                );
            }
        }

        private CompanionLifecycle requireFenced(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation
        ) {
            CompanionLifecycle lifecycle = transaction.lifecycles()
                    .findByProfile(capture.profileId())
                    .orElseThrow(() -> new IllegalStateException(
                            "capture_compensation_lifecycle_missing"
                    ));
            CompanionAlias alias = transaction.identities()
                    .resolveAlias(capture.targetAlias())
                    .orElse(null);
            boolean exactLocation = lifecycle.location().kind()
                    == LifecycleLocationKind.LIVE_ENTITY
                    && lifecycle.location().equals(LifecycleLocation.liveEntity(
                            capture.targetAlias().toString(),
                            capture.targetWorldKey()
                    ));
            if (lifecycle.state() != LifecycleState.ACTIVE
                    || !lifecycle.revision().equals(
                    capture.expectedLifecycleRevision().next()
            ) || !operation.operationId().equals(
                    lifecycle.activeOperationId()
            ) || lifecycle.quarantined() || !exactLocation
                    || alias == null
                    || alias.state() != CompanionAlias.State.CURRENT
                    || !alias.profileId().equals(capture.profileId())
                    || transaction.snapshots()
                    .findById(capture.snapshot().snapshotId())
                    .isPresent()) {
                throw new IllegalStateException(
                        "capture_compensation_fence_mismatch"
                );
            }
            return lifecycle;
        }
    }
}
