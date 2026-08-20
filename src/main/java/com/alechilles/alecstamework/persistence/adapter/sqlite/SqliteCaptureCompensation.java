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
        SqliteManagedAdmissionParticipant managed =
                capture.admissionEvidence() != null
                        && capture.admissionEvidence().status()
                        == com.alechilles.alecstamework.persistence.runtime
                        .LifecycleAdmissionEvidence.Status.MANAGED
                        ? SqliteManagedAdmissionParticipant.from(
                        operation.operationId(),
                        capture.admissionEvidence()
                ) : null;
        return coordinator.resume(
                operation,
                claim,
                new RefundDetail(claim),
                refunds,
                new CaptureCompensatedWork(capture, managed),
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
                || lifecycle == null
                || managedRowsRemain(transaction, operation, capture)) {
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
        if (capture.tameAndCommandLink()) {
            return matchesTameCompleted(
                    transaction,
                    operation.operationId(),
                    capture,
                    lifecycle
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

    private static boolean matchesTameCompleted(
            SqlitePersistenceTransactionContext transaction,
            OperationId operationId,
            CompanionCaptureRequest capture,
            CompanionLifecycle lifecycle
    ) {
        var evidence = capture.tameAndLinkEvidence();
        CompanionLifecycle source = evidence.expectedLifecycle();
        CompanionAlias alias = transaction.identities()
                .resolveAlias(capture.targetAlias())
                .orElse(null);
        return lifecycle.profileId().equals(source.profileId())
                && java.util.Objects.equals(
                lifecycle.ownerId(), source.ownerId()
        )
                && lifecycle.state() == source.state()
                && lifecycle.location().equals(source.location())
                && lifecycle.revision().equals(
                source.revision().next().next()
        )
                && lifecycle.activeOperationId() == null
                && !lifecycle.quarantined()
                && lifecycle.lastReconciledGeneration().equals(
                source.lastReconciledGeneration()
        )
                && java.util.Objects.equals(
                lifecycle.ownerWorldKey(), source.ownerWorldKey()
        )
                && transaction.identities()
                .findProfile(source.profileId())
                .filter(evidence.expectedIdentity()::equals)
                .isPresent()
                && alias != null
                && alias.state() == CompanionAlias.State.CURRENT
                && alias.profileId().equals(source.profileId())
                && java.util.Objects.equals(
                evidence.populationGroups().expectedAssignment(),
                transaction.populationGroups()
                        .findAssignment(source.profileId()).orElse(null)
        )
                && transaction.commandRosters()
                .findByProfile(source.profileId()).isEmpty()
                && transaction.timedSummons()
                .find(source.profileId()).isEmpty()
                && transaction.population()
                .findByOperation(operationId).isEmpty()
                && transaction.populationGroups()
                .findReservations(operationId).isEmpty();
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
        private final SqliteManagedAdmissionParticipant managed;

        private CaptureCompensatedWork(
                CompanionCaptureRequest capture,
                SqliteManagedAdmissionParticipant managed
        ) {
            this.capture = capture;
            this.managed = managed;
        }

        @Override
        public void execute(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation,
                RefundClaim claim,
                String liveEvidence,
                long compensatedAtMs
        ) throws Exception {
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
            if (capture.tameAndCommandLink()) {
                requireTameUncommitted(transaction, operation);
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
            if (managed != null) {
                managed.retirePrepared(transaction, operation);
            } else if (capture.tameAndCommandLink()) {
                retireLegacyTameReservations(transaction, operation);
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
            boolean exactFence = capture.tameAndCommandLink()
                    ? SqliteCompanionCapturePreparation.matchesFence(
                    lifecycle,
                    operation,
                    capture.tameAndLinkEvidence().expectedLifecycle(),
                    capture.requestedAtMs()
            )
                    : lifecycle.state() == LifecycleState.ACTIVE
                    && lifecycle.revision().equals(
                    capture.expectedLifecycleRevision().next()
            )
                    && operation.operationId().equals(
                    lifecycle.activeOperationId()
            )
                    && !lifecycle.quarantined()
                    && lifecycle.location().kind()
                    == LifecycleLocationKind.LIVE_ENTITY
                    && lifecycle.location().equals(
                    LifecycleLocation.liveEntity(
                            capture.targetAlias().toString(),
                            capture.targetWorldKey()
                    )
            );
            if (!exactFence
                    || alias == null
                    || alias.state() != CompanionAlias.State.CURRENT
                    || !alias.profileId().equals(capture.profileId())
                    || (capture.capturedItem()
                    && transaction.snapshots()
                            .findById(capture.snapshot().snapshotId())
                            .isPresent())) {
                throw new IllegalStateException(
                        "capture_compensation_fence_mismatch"
                );
            }
            return lifecycle;
        }

        private void requireTameUncommitted(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation
        ) throws Exception {
            var evidence = capture.tameAndLinkEvidence();
            boolean reservationsMatch = managed != null
                    ? managed.matches(transaction, operation)
                    : transaction.population()
                    .findByOperation(operation.operationId()).equals(
                            new SqliteOwnerPopulationParticipant(
                                    evidence.ownerPopulation()
                            ).reservations(operation)
                    )
                    && transaction.populationGroups()
                    .findReservations(operation.operationId()).equals(
                            evidence.populationGroups()
                                    .targetPlan().reservations()
                    );
            if (transaction.identities()
                    .findProfile(evidence.expectedIdentity().profileId())
                    .filter(evidence.expectedIdentity()::equals)
                    .isEmpty()
                    || !java.util.Objects.equals(
                    evidence.populationGroups().expectedAssignment(),
                    transaction.populationGroups().findAssignment(
                            capture.profileId()
                    ).orElse(null)
            )
                    || transaction.commandRosters()
                    .findByProfile(capture.profileId()).isPresent()
                    || transaction.timedSummons()
                    .find(capture.profileId()).isPresent()
                    || !reservationsMatch) {
                throw new IllegalStateException(
                        "capture_tame_compensation_source_mismatch"
                );
            }
        }

        private void retireLegacyTameReservations(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation
        ) {
            var evidence = capture.tameAndLinkEvidence();
            if (!transaction.population().retireExact(
                    operation.operationId(),
                    evidence.ownerPopulation().increases().size()
            )
                    || !transaction.populationGroups().retireExact(
                    operation.operationId(),
                    evidence.populationGroups()
                            .targetPlan().reservations().size()
            )) {
                throw new IllegalStateException(
                        "capture_tame_compensation_reservation_retirement_failed"
                );
            }
        }
    }

    private static boolean managedRowsRemain(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            CompanionCaptureRequest capture
    ) {
        if (capture.admissionEvidence() == null
                || capture.admissionEvidence().status()
                != com.alechilles.alecstamework.persistence.runtime
                .LifecycleAdmissionEvidence.Status.MANAGED) {
            return false;
        }
        return !transaction.populationDomains()
                .findByOperation(operation.operationId()).isEmpty()
                || !transaction.population()
                .findByOperation(operation.operationId()).isEmpty()
                || !transaction.populationGroups()
                .findReservations(operation.operationId()).isEmpty();
    }
}
