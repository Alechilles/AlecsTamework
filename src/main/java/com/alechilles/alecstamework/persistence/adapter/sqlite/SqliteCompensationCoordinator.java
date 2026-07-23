package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.compensation.PreparedCompensationDetail;
import com.alechilles.alecstamework.persistence.compensation.TimedCompensatedOperationWork;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.operation.LiveOperationBoundary;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/**
 * Shared typed compensation workflow for external mutations that cannot be rolled back.
 *
 * <p>Feature adapters provide only their claim detail, idempotent live resolver, and canonical
 * cleanup. Phase transitions and exact transaction readback remain shared.</p>
 */
public final class SqliteCompensationCoordinator {
    private final SqliteOperationEngine operations;
    private final LongSupplier clock;

    public SqliteCompensationCoordinator(
            @Nonnull SqliteOperationEngine operations,
            @Nonnull LongSupplier clock
    ) {
        if (operations == null || clock == null) {
            throw new IllegalArgumentException(
                    "Compensation coordinator dependencies are required"
            );
        }
        this.operations = operations;
        this.clock = clock;
    }

    /** Starts or resumes one typed compensation from its durable shared phase. */
    @Nonnull
    public <T> CompletionStage<OperationWorkflowResult> resume(
            @Nonnull OperationEnvelope operation,
            @Nonnull T payload,
            @Nonnull PreparedCompensationDetail detail,
            @Nonnull LiveOperationBoundary<T> liveBoundary,
            @Nonnull TimedCompensatedOperationWork<T> compensatedWork,
            @Nonnull String workflowCode
    ) {
        if (operation == null || payload == null || detail == null
                || liveBoundary == null || compensatedWork == null) {
            throw new IllegalArgumentException(
                    "Complete compensation workflow is required"
            );
        }
        String code = requireCode(workflowCode);
        return continueFrom(
                operation,
                payload,
                detail,
                liveBoundary,
                compensatedWork,
                code
        );
    }

    private <T> CompletionStage<OperationWorkflowResult> continueFrom(
            OperationEnvelope operation,
            T payload,
            PreparedCompensationDetail detail,
            LiveOperationBoundary<T> liveBoundary,
            TimedCompensatedOperationWork<T> compensatedWork,
            String code
    ) {
        return switch (operation.phase()) {
            case LIVE_APPLYING, RETRYABLE, UNKNOWN -> begin(
                    operation,
                    payload,
                    detail,
                    liveBoundary,
                    compensatedWork,
                    code
            );
            case COMPENSATING -> applyOrResolve(
                    operation,
                    payload,
                    liveBoundary,
                    compensatedWork,
                    code
            );
            case COMPENSATED -> SqliteOperationResults.completed(
                    new OperationWorkflowResult(
                            OperationWorkflowResult.Status.COMPENSATED,
                            operation,
                            List.of(),
                            null
                    )
            );
            default -> SqliteOperationResults.completed(
                    SqliteOperationResults.failed(
                            OperationWorkflowResult.Status.INVALID_PHASE,
                            operation,
                            List.of(),
                            new IllegalStateException(
                                    code + "_phase_"
                                            + operation.phase()
                                            .name()
                                            .toLowerCase()
                            )
                    )
            );
        };
    }

    private <T> CompletionStage<OperationWorkflowResult> begin(
            OperationEnvelope operation,
            T payload,
            PreparedCompensationDetail detail,
            LiveOperationBoundary<T> liveBoundary,
            TimedCompensatedOperationWork<T> compensatedWork,
            String code
    ) {
        return operations.beginCompensation(
                operation,
                detail,
                clock.getAsLong()
        ).completion().thenCompose(result -> {
            if (result instanceof PersistenceTransactionResult.Committed<?> committed
                    && committed.value() instanceof OperationEnvelope compensating) {
                return applyOrResolve(
                        compensating,
                        payload,
                        liveBoundary,
                        compensatedWork,
                        code
                );
            }
            return SqliteOperationResults.completed(
                    SqliteOperationResults.failed(
                            OperationWorkflowResult.Status.COMPENSATION_PREPARE_FAILED,
                            operation,
                            List.of(),
                            SqliteOperationResults.transactionFailure(
                                    result,
                                    code + "_prepare_failed"
                            )
                    )
            );
        });
    }

    private <T> CompletionStage<OperationWorkflowResult> applyOrResolve(
            OperationEnvelope operation,
            T payload,
            LiveOperationBoundary<T> liveBoundary,
            TimedCompensatedOperationWork<T> compensatedWork,
            String code
    ) {
        CompletionStage<LiveOperationResult> resolution;
        try {
            resolution = liveBoundary.applyOrResolve(payload, operation);
            if (resolution == null) {
                throw new IllegalStateException(
                        code + "_live_boundary_returned_null"
                );
            }
        } catch (Throwable failure) {
            resolution = LiveOperationResult.retryable(
                    code + "_live_boundary_failed",
                    failure
            ).completed();
        }
        return resolution.handle((live, failure) -> failure == null
                        ? live
                        : LiveOperationResult.retryable(
                                code + "_live_boundary_failed",
                                failure
                        ))
                .thenCompose(live -> continueLiveResult(
                        operation,
                        payload,
                        compensatedWork,
                        code,
                        live == null
                                ? LiveOperationResult.retryable(
                                        code + "_live_boundary_returned_null",
                                        null
                                )
                                : live
                ));
    }

    private <T> CompletionStage<OperationWorkflowResult> continueLiveResult(
            OperationEnvelope operation,
            T payload,
            TimedCompensatedOperationWork<T> compensatedWork,
            String code,
            LiveOperationResult live
    ) {
        return switch (live.status()) {
            case CONFIRMED -> commit(
                    operation,
                    payload,
                    live.code(),
                    compensatedWork,
                    code
            );
            case RETRYABLE -> transitionFailure(
                    operation,
                    OperationWorkflowResult.Status.COMPENSATION_RETRYABLE,
                    live
            );
            case UNKNOWN, COMPENSATE -> transitionFailure(
                    operation,
                    OperationWorkflowResult.Status.COMPENSATION_UNKNOWN,
                    live
            );
        };
    }

    private CompletionStage<OperationWorkflowResult> transitionFailure(
            OperationEnvelope operation,
            OperationWorkflowResult.Status status,
            LiveOperationResult live
    ) {
        return SqliteOperationResults.completed(
                SqliteOperationResults.failed(
                        status,
                        operation,
                        List.of(),
                        live.cause() == null
                                ? new IllegalStateException(live.code())
                                : live.cause()
                )
        );
    }

    private <T> CompletionStage<OperationWorkflowResult> commit(
            OperationEnvelope operation,
            T payload,
            String liveEvidence,
            TimedCompensatedOperationWork<T> compensatedWork,
            String code
    ) {
        return operations.commitCompensated(
                operation,
                payload,
                liveEvidence,
                compensatedWork,
                clock.getAsLong()
        ).completion().thenApply(result -> {
            if (result instanceof PersistenceTransactionResult.Committed<?> committed
                    && committed.value() instanceof OperationEnvelope compensated) {
                return new OperationWorkflowResult(
                        OperationWorkflowResult.Status.COMPENSATED,
                        compensated,
                        List.of(),
                        null
                );
            }
            return SqliteOperationResults.failed(
                    OperationWorkflowResult.Status.COMPENSATION_COMMIT_FAILED,
                    operation,
                    List.of(),
                    SqliteOperationResults.transactionFailure(
                            result,
                            code + "_commit_failed"
                    )
            );
        });
    }

    private String requireCode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Compensation workflow code is required"
            );
        }
        return value.trim();
    }
}
