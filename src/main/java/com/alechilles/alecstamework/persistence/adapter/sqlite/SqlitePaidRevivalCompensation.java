package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.companion.revival.PaidRevivalLiveResult;
import com.alechilles.alecstamework.companion.revival.PaidRevivalReleaseBoundary;
import com.alechilles.alecstamework.companion.revival.PaidRevivalRequest;
import com.alechilles.alecstamework.persistence.compensation.PreparedCompensationDetail;
import com.alechilles.alecstamework.persistence.compensation.RefundClaim;
import com.alechilles.alecstamework.persistence.compensation.RefundDeliveryBoundary;
import com.alechilles.alecstamework.persistence.compensation.TimedCompensatedOperationWork;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;

/** Paid-revival economic disposition through the one shared compensation protocol. */
final class SqlitePaidRevivalCompensation {
    private final SqliteCompensationCoordinator coordinator;
    private final SqliteRefundClaimReader claims;
    private final RefundDeliveryBoundary refunds;

    SqlitePaidRevivalCompensation(
            SqliteOperationEngine operations,
            SqliteReadExecutor reads,
            LongSupplier clock,
            RefundDeliveryBoundary refunds
    ) {
        if (operations == null || reads == null || clock == null
                || refunds == null) {
            throw new IllegalArgumentException(
                    "Paid revival compensation dependencies are required"
            );
        }
        coordinator = new SqliteCompensationCoordinator(
                operations, clock
        );
        claims = new SqliteRefundClaimReader(reads);
        this.refunds = refunds;
    }

    CompletionStage<OperationWorkflowResult> resume(
            OperationEnvelope operation,
            PaidRevivalRequest request,
            PaidRevivalLiveResult disposition,
            PaidRevivalReleaseBoundary releases
    ) {
        if (operation == null || request == null || releases == null) {
            throw new IllegalArgumentException(
                    "Paid revival compensation request is required"
            );
        }
        return switch (operation.phase()) {
            case COMPENSATING, COMPENSATED ->
                    resumeDurableMode(operation, request, releases);
            default -> resumeDisposition(
                    operation, request, disposition, releases
            );
        };
    }

    private CompletionStage<OperationWorkflowResult> resumeDurableMode(
            OperationEnvelope operation,
            PaidRevivalRequest request,
            PaidRevivalReleaseBoundary releases
    ) {
        return claims.find(operation.operationId()).thenCompose(result -> {
            if (result instanceof PersistenceReadResult.Found<RefundClaim>
                    found) {
                return refund(operation, request, found.value());
            }
            if (result instanceof PersistenceReadResult.Absent<RefundClaim>) {
                return noCharge(operation, request, releases);
            }
            PersistenceReadResult.Failed<RefundClaim> failed =
                    (PersistenceReadResult.Failed<RefundClaim>) result;
            return completedFailure(
                    OperationWorkflowResult.Status.COMPENSATION_RETRYABLE,
                    operation,
                    new IllegalStateException(
                            failed.failure().code(),
                            failed.failure().cause()
                    )
            );
        });
    }

    private CompletionStage<OperationWorkflowResult> resumeDisposition(
            OperationEnvelope operation,
            PaidRevivalRequest request,
            PaidRevivalLiveResult disposition,
            PaidRevivalReleaseBoundary releases
    ) {
        if (disposition == null) {
            return completedFailure(
                    OperationWorkflowResult.Status
                            .COMPENSATION_PREPARE_FAILED,
                    operation,
                    new IllegalStateException(
                            "paid_revival_compensation_disposition_missing"
                    )
            );
        }
        return switch (disposition.status()) {
            case NO_CHARGE -> noCharge(
                    operation, request, releases
            );
            case REFUND_REQUIRED -> {
                try {
                    yield refund(
                            operation,
                            request,
                            SqlitePaidRevivalRefunds.claim(
                                    operation.operationId(), request
                            )
                    );
                } catch (RuntimeException invalid) {
                    yield completedFailure(
                            OperationWorkflowResult.Status
                                    .COMPENSATION_PREPARE_FAILED,
                            operation,
                            invalid
                    );
                }
            }
            default -> completedFailure(
                    OperationWorkflowResult.Status
                            .COMPENSATION_PREPARE_FAILED,
                    operation,
                    new IllegalStateException(
                            "paid_revival_invalid_compensation_disposition_"
                                    + disposition.status()
                                    .name().toLowerCase()
                    )
            );
        };
    }

    private CompletionStage<OperationWorkflowResult> noCharge(
            OperationEnvelope operation,
            PaidRevivalRequest request,
            PaidRevivalReleaseBoundary releases
    ) {
        return coordinator.resume(
                operation,
                request,
                new NoChargeDetail(),
                releases,
                new NoChargeWork(request),
                "paid_revival_no_charge"
        );
    }

    private CompletionStage<OperationWorkflowResult> refund(
            OperationEnvelope operation,
            PaidRevivalRequest request,
            RefundClaim claim
    ) {
        RefundClaim expected = SqlitePaidRevivalRefunds.claim(
                operation.operationId(), request
        );
        if (!SqlitePaidRevivalRefunds.same(expected, claim)) {
            return completedFailure(
                    OperationWorkflowResult.Status
                            .COMPENSATION_PREPARE_FAILED,
                    operation,
                    new IllegalStateException(
                            "paid_revival_refund_claim_mismatch"
                    )
            );
        }
        return coordinator.resume(
                operation,
                expected,
                new RefundDetail(expected),
                refunds,
                new RefundWork(request),
                "paid_revival_refund"
        );
    }

    private CompletionStage<OperationWorkflowResult> completedFailure(
            OperationWorkflowResult.Status status,
            OperationEnvelope operation,
            Throwable failure
    ) {
        return CompletableFuture.completedFuture(
                new OperationWorkflowResult(
                        status, operation, List.of(), failure
                )
        );
    }

    private static final class NoChargeDetail
            implements PreparedCompensationDetail {
        @Override
        public void prepare(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation,
                long preparedAtMs
        ) {
        }

        @Override
        public boolean matches(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation
        ) {
            return transaction.refunds()
                    .findByOperation(operation.operationId()).isEmpty();
        }
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
                        "paid_revival_refund_claim_rejected"
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
                    .filter(actual -> SqlitePaidRevivalRefunds.same(
                            claim, actual
                    ))
                    .isPresent();
        }
    }

    private static final class NoChargeWork
            implements TimedCompensatedOperationWork<PaidRevivalRequest> {
        private final PaidRevivalRequest request;

        private NoChargeWork(PaidRevivalRequest request) {
            this.request = request;
        }

        @Override
        public void execute(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation,
                PaidRevivalRequest payload,
                String liveEvidence,
                long compensatedAtMs
        ) {
            if (!request.equals(payload)
                    || transaction.refunds()
                    .findByOperation(operation.operationId()).isPresent()) {
                throw new IllegalStateException(
                        "paid_revival_no_charge_evidence_mismatch"
                );
            }
            cleanup(
                    transaction, operation, request, compensatedAtMs
            );
        }

        @Override
        public boolean matches(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation,
                PaidRevivalRequest payload
        ) {
            return request.equals(payload)
                    && transaction.refunds()
                    .findByOperation(operation.operationId()).isEmpty()
                    && new SqlitePaidRevivalPreparation(request)
                    .compensatedMatches(transaction, operation);
        }
    }

    private static final class RefundWork
            implements TimedCompensatedOperationWork<RefundClaim> {
        private final PaidRevivalRequest request;

        private RefundWork(PaidRevivalRequest request) {
            this.request = request;
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
                        "paid_revival_refund_completion_rejected"
                );
            }
            cleanup(
                    transaction, operation, request, compensatedAtMs
            );
        }

        @Override
        public boolean matches(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation,
                RefundClaim claim
        ) {
            return transaction.refunds()
                    .findByOperation(operation.operationId())
                    .filter(RefundClaim::delivered)
                    .filter(actual -> SqlitePaidRevivalRefunds.same(
                            claim, actual
                    ))
                    .isPresent()
                    && new SqlitePaidRevivalPreparation(request)
                    .compensatedMatches(transaction, operation);
        }
    }

    private static void cleanup(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            PaidRevivalRequest request,
            long compensatedAtMs
    ) {
        CompanionLifecycle source = request.groupAdmission().before();
        CompanionLifecycle fenced = transaction.lifecycles()
                .findByProfile(source.profileId())
                .orElseThrow(() -> new IllegalStateException(
                        "paid_revival_compensation_lifecycle_missing"
                ));
        CompanionAlias alias = transaction.identities()
                .resolveAlias(request.targetAlias()).orElse(null);
        if (!SqlitePaidRevivalSourceEvidence.fencedMatches(
                fenced, request, operation
        )
                || !SqlitePaidRevivalSourceEvidence.leaseMatches(
                        alias, request, operation
                )) {
            throw new IllegalStateException(
                    "paid_revival_compensation_fence_mismatch"
            );
        }
        new SqlitePopulationGroupTransitionParticipant(
                request.groupAdmission()
        ).retirePrepared(transaction, operation);
        if (!transaction.identities().retireAlias(
                request.targetAlias(), compensatedAtMs
        ).applied()) {
            throw new IllegalStateException(
                    "paid_revival_compensation_alias_rejected"
            );
        }
        CompanionLifecycle restored = new CompanionLifecycle(
                source.profileId(),
                source.ownerId(),
                source.state(),
                source.location(),
                fenced.revision().next(),
                null,
                compensatedAtMs,
                source.lastReconciledGeneration(),
                source.quarantineIncidentId(),
                source.ownerWorldKey()
        );
        if (!transaction.lifecycles().transition(
                new LifecycleTransition(
                        fenced.revision(),
                        operation.operationId(),
                        restored
                )
        ).applied()) {
            throw new IllegalStateException(
                    "paid_revival_compensation_lifecycle_rejected"
            );
        }
    }
}
