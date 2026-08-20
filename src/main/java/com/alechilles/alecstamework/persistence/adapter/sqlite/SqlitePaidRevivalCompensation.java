package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.revival.PaidRevivalLiveResult;
import com.alechilles.alecstamework.companion.revival.PaidRevivalReleaseBoundary;
import com.alechilles.alecstamework.companion.revival.PaidRevivalRequest;
import com.alechilles.alecstamework.persistence.compensation.PreparedCompensationDetail;
import com.alechilles.alecstamework.persistence.compensation.RefundClaim;
import com.alechilles.alecstamework.persistence.compensation.RefundDeliveryBoundary;
import com.alechilles.alecstamework.persistence.compensation.TimedCompensatedOperationWork;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation
        .DurableOperationCleanupBoundary;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
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
            PaidRevivalReleaseBoundary releases,
            DurableOperationCleanupBoundary<PaidRevivalRequest> cleanup
    ) {
        if (operation == null || request == null || releases == null
                || cleanup == null) {
            throw new IllegalArgumentException(
                    "Paid revival compensation request is required"
            );
        }
        return switch (operation.phase()) {
            case COMPENSATING, COMPENSATED ->
                    resumeDurableMode(
                            operation, request, releases, cleanup
                    );
            default -> resumeDisposition(
                    operation, request, disposition, releases, cleanup
            );
        };
    }

    private CompletionStage<OperationWorkflowResult> resumeDurableMode(
            OperationEnvelope operation,
            PaidRevivalRequest request,
            PaidRevivalReleaseBoundary releases,
            DurableOperationCleanupBoundary<PaidRevivalRequest> cleanup
    ) {
        return claims.find(operation.operationId()).thenCompose(result -> {
            if (result instanceof PersistenceReadResult.Found<RefundClaim>
                    found) {
                return refund(
                        operation, request, found.value(), cleanup
                );
            }
            if (result instanceof PersistenceReadResult.Absent<RefundClaim>) {
                return noCharge(
                        operation, request, releases, cleanup
                );
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
            PaidRevivalReleaseBoundary releases,
            DurableOperationCleanupBoundary<PaidRevivalRequest> cleanup
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
                    operation, request, releases, cleanup
            );
            case REFUND_REQUIRED -> {
                try {
                    yield refund(
                            operation,
                            request,
                            SqlitePaidRevivalRefunds.claim(
                                    operation.operationId(), request
                            ),
                            cleanup
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
            PaidRevivalReleaseBoundary releases,
            DurableOperationCleanupBoundary<PaidRevivalRequest> cleanup
    ) {
        SqliteManagedAdmissionParticipant managed =
                SqlitePaidRevivalCompensationCleanup.managed(
                operation, request
        );
        return coordinator.resume(
                operation,
                request,
                new NoChargeDetail(),
                releases,
                new NoChargeWork(request, managed),
                "paid_revival_no_charge"
        ).thenCompose(result ->
                cleanupCompensated(result, request, cleanup));
    }

    private CompletionStage<OperationWorkflowResult> refund(
            OperationEnvelope operation,
            PaidRevivalRequest request,
            RefundClaim claim,
            DurableOperationCleanupBoundary<PaidRevivalRequest> cleanup
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
        SqliteManagedAdmissionParticipant managed =
                SqlitePaidRevivalCompensationCleanup.managed(
                operation, request
        );
        return coordinator.resume(
                operation,
                expected,
                new RefundDetail(expected),
                refunds,
                new RefundWork(request, managed),
                "paid_revival_refund"
        ).thenCompose(result ->
                cleanupCompensated(result, request, cleanup));
    }

    private CompletionStage<OperationWorkflowResult> cleanupCompensated(
            OperationWorkflowResult result,
            PaidRevivalRequest request,
            DurableOperationCleanupBoundary<PaidRevivalRequest> cleanup
    ) {
        OperationEnvelope operation = result.operation();
        if (result.status() != OperationWorkflowResult.Status.COMPENSATED
                || operation == null
                || operation.phase() != OperationPhase.COMPENSATED) {
            return CompletableFuture.completedFuture(result);
        }
        CompletionStage<LiveOperationResult> resolution;
        try {
            resolution = cleanup.cleanupAfterDurable(
                    request, operation
            );
            if (resolution == null) {
                throw new IllegalStateException(
                        "paid_revival_compensated_cleanup_missing"
                );
            }
        } catch (Throwable failure) {
            resolution = LiveOperationResult.retryable(
                    "paid_revival_compensated_cleanup_failed", failure
            ).completed();
        }
        return resolution.handle((cleanupResult, failure) ->
                cleanupResult(result, cleanupResult, failure));
    }

    private OperationWorkflowResult cleanupResult(
            OperationWorkflowResult compensated,
            LiveOperationResult cleanup,
            Throwable failure
    ) {
        if (failure == null && cleanup != null
                && cleanup.status()
                == LiveOperationResult.Status.CONFIRMED) {
            return compensated;
        }
        LiveOperationResult resolved = failure == null && cleanup != null
                ? cleanup
                : LiveOperationResult.retryable(
                        "paid_revival_compensated_cleanup_failed",
                        failure
                );
        OperationWorkflowResult.Status status =
                resolved.status()
                == LiveOperationResult.Status.RETRYABLE
                        ? OperationWorkflowResult.Status
                        .COMPENSATION_RETRYABLE
                        : OperationWorkflowResult.Status
                        .COMPENSATION_UNKNOWN;
        Throwable cause = resolved.cause() == null
                ? new IllegalStateException(resolved.code())
                : resolved.cause();
        return new OperationWorkflowResult(
                status,
                compensated.operation(),
                compensated.events(),
                cause
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
        private final SqliteManagedAdmissionParticipant managed;

        private NoChargeWork(
                PaidRevivalRequest request,
                SqliteManagedAdmissionParticipant managed
        ) {
            this.request = request;
            this.managed = managed;
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
            SqlitePaidRevivalCompensationCleanup.cleanup(
                    transaction, operation, request, managed, compensatedAtMs
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
                    .compensatedMatches(transaction, operation)
                    && !SqlitePaidRevivalCompensationCleanup.rowsRemain(
                            transaction, operation, managed
                    );
        }
    }

    private static final class RefundWork
            implements TimedCompensatedOperationWork<RefundClaim> {
        private final PaidRevivalRequest request;
        private final SqliteManagedAdmissionParticipant managed;

        private RefundWork(
                PaidRevivalRequest request,
                SqliteManagedAdmissionParticipant managed
        ) {
            this.request = request;
            this.managed = managed;
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
            SqlitePaidRevivalCompensationCleanup.cleanup(
                    transaction, operation, request, managed, compensatedAtMs
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
                    .compensatedMatches(transaction, operation)
                    && !SqlitePaidRevivalCompensationCleanup.rowsRemain(
                            transaction, operation, managed
                    );
        }
    }
}
