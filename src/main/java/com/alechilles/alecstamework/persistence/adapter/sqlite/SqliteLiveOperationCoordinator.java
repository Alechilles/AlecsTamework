package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.operation.DurableCommitEvidence;
import com.alechilles.alecstamework.persistence.operation.LiveOperationBoundary;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import com.alechilles.alecstamework.persistence.operation.TimedDurableOperationWork;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/**
 * Shared prepare/live/durable/project/publish workflow for one external mutation boundary.
 *
 * <p>Feature adapters provide typed detail and effects, never their own phase machine. Recovery
 * re-enters this coordinator with the same durable payload and an idempotent live resolver.</p>
 */
public final class SqliteLiveOperationCoordinator {
    private final SqliteOperationEngine operations;
    private final SqliteOperationPublisher publisher;
    private final LongSupplier clock;

    public SqliteLiveOperationCoordinator(
            @Nonnull SqliteOperationEngine operations,
            @Nonnull SqliteOperationPublisher publisher,
            @Nonnull LongSupplier clock
    ) {
        if (operations == null || publisher == null || clock == null) {
            throw new IllegalArgumentException("Live operation dependencies are required");
        }
        this.operations = operations;
        this.publisher = publisher;
        this.clock = clock;
    }

    /** Starts or resumes one typed operation with exactly one external live boundary. */
    @Nonnull
    public <T> Submission execute(
            @Nonnull OperationDefinition<T> definition,
            @Nonnull OperationRequest<T> request,
            @Nonnull PreparedOperationDetail detail,
            @Nonnull LiveOperationBoundary<T> liveBoundary,
            @Nonnull TimedDurableOperationWork<T> durableWork,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers,
            @Nonnull String workflowCode
    ) {
        if (definition == null || request == null || detail == null
                || liveBoundary == null || durableWork == null) {
            throw new IllegalArgumentException("Complete live operation request is required");
        }
        String code = requireCode(workflowCode);
        List<ProjectionConsumer> consumers =
                publisher.validateConsumers(requiredConsumers);
        SqliteUnitOfWorkRunner.Submission<OperationEnvelope> prepared =
                operations.prepare(definition, request, detail);
        CompletionStage<OperationWorkflowResult> completion =
                prepared.completion().thenCompose(result -> {
                    if (!(result instanceof PersistenceTransactionResult.Committed<?> committed)
                            || !(committed.value() instanceof OperationEnvelope operation)) {
                        return SqliteOperationResults.completed(
                                SqliteOperationResults.failed(
                                        OperationWorkflowResult.Status.PREPARE_FAILED,
                                        null,
                                        List.of(),
                                        SqliteOperationResults.transactionFailure(
                                                result,
                                                code + "_prepare_failed"
                                        )
                                )
                        );
                    }
                    return continueFrom(
                            operation,
                            request.payload(),
                            liveBoundary,
                            durableWork,
                            consumers,
                            code
                    );
                });
        return new Submission(prepared.acceptance(), completion);
    }

    private <T> CompletionStage<OperationWorkflowResult> continueFrom(
            OperationEnvelope operation,
            T payload,
            LiveOperationBoundary<T> liveBoundary,
            TimedDurableOperationWork<T> durableWork,
            List<ProjectionConsumer> consumers,
            String code
    ) {
        return switch (operation.phase()) {
            case PUBLISHED, DURABLE -> publisher.resume(operation, consumers);
            case PREPARED, RETRYABLE -> transitionToLive(
                    operation, payload, liveBoundary, durableWork, consumers, code
            );
            case LIVE_APPLYING -> applyOrResolveLive(
                    operation, payload, liveBoundary, durableWork, consumers, code
            );
            case UNKNOWN -> SqliteOperationResults.completed(
                    SqliteOperationResults.failed(
                            OperationWorkflowResult.Status.LIVE_UNKNOWN,
                            operation,
                            List.of(),
                            operationFailure(operation, code + "_live_outcome_unknown")
                    )
            );
            default -> SqliteOperationResults.completed(
                    SqliteOperationResults.failed(
                            OperationWorkflowResult.Status.INVALID_PHASE,
                            operation,
                            List.of(),
                            new IllegalStateException(
                                    code + "_phase_"
                                            + operation.phase().name().toLowerCase()
                            )
                    )
            );
        };
    }

    private <T> CompletionStage<OperationWorkflowResult> transitionToLive(
            OperationEnvelope operation,
            T payload,
            LiveOperationBoundary<T> liveBoundary,
            TimedDurableOperationWork<T> durableWork,
            List<ProjectionConsumer> consumers,
            String code
    ) {
        return operations.transition(
                operation,
                OperationPhase.LIVE_APPLYING,
                null,
                null,
                clock.getAsLong()
        ).completion().thenCompose(result -> {
            if (result instanceof PersistenceTransactionResult.Committed<?> committed
                    && committed.value() instanceof OperationEnvelope applying) {
                return applyOrResolveLive(
                        applying, payload, liveBoundary, durableWork, consumers, code
                );
            }
            return SqliteOperationResults.completed(
                    SqliteOperationResults.failed(
                            OperationWorkflowResult.Status.TRANSITION_FAILED,
                            operation,
                            List.of(),
                            SqliteOperationResults.transactionFailure(
                                    result,
                                    code + "_live_transition_failed"
                            )
                    )
            );
        });
    }

    private <T> CompletionStage<OperationWorkflowResult> applyOrResolveLive(
            OperationEnvelope operation,
            T payload,
            LiveOperationBoundary<T> liveBoundary,
            TimedDurableOperationWork<T> durableWork,
            List<ProjectionConsumer> consumers,
            String code
    ) {
        LiveOperationResult live;
        try {
            live = liveBoundary.applyOrResolve(payload, operation);
            if (live == null) {
                throw new IllegalStateException(code + "_live_boundary_returned_null");
            }
        } catch (Throwable failure) {
            live = LiveOperationResult.retryable(
                    code + "_live_boundary_failed",
                    failure
            );
        }
        return switch (live.status()) {
            case CONFIRMED -> commit(
                    operation, payload, durableWork, consumers, code
            );
            case COMPENSATE -> SqliteOperationResults.completed(
                    SqliteOperationResults.failed(
                            OperationWorkflowResult.Status.COMPENSATION_REQUIRED,
                            operation,
                            List.of(),
                            live.cause() == null
                                    ? new IllegalStateException(live.code())
                                    : live.cause()
                    )
            );
            case RETRYABLE -> transitionLiveFailure(
                    operation,
                    OperationPhase.RETRYABLE,
                    OperationWorkflowResult.Status.LIVE_RETRYABLE,
                    live,
                    code
            );
            case UNKNOWN -> transitionLiveFailure(
                    operation,
                    OperationPhase.UNKNOWN,
                    OperationWorkflowResult.Status.LIVE_UNKNOWN,
                    live,
                    code
            );
        };
    }

    private CompletionStage<OperationWorkflowResult> transitionLiveFailure(
            OperationEnvelope operation,
            OperationPhase phase,
            OperationWorkflowResult.Status status,
            LiveOperationResult live,
            String code
    ) {
        return operations.transition(
                operation,
                phase,
                "live",
                live.code(),
                clock.getAsLong()
        ).completion().thenApply(result -> {
            if (result instanceof PersistenceTransactionResult.Committed<?> committed
                    && committed.value() instanceof OperationEnvelope transitioned) {
                return SqliteOperationResults.failed(
                        status,
                        transitioned,
                        List.of(),
                        live.cause() == null
                                ? new IllegalStateException(live.code())
                                : live.cause()
                );
            }
            return SqliteOperationResults.failed(
                    OperationWorkflowResult.Status.TRANSITION_FAILED,
                    operation,
                    List.of(),
                    SqliteOperationResults.transactionFailure(
                            result,
                            code + "_live_failure_transition_failed"
                    )
            );
        });
    }

    private <T> CompletionStage<OperationWorkflowResult> commit(
            OperationEnvelope operation,
            T payload,
            TimedDurableOperationWork<T> durableWork,
            List<ProjectionConsumer> consumers,
            String code
    ) {
        long committedAtMs = clock.getAsLong();
        return operations.commitDurable(
                operation,
                (transaction, current) -> durableWork.execute(
                        transaction,
                        current,
                        payload,
                        committedAtMs
                ),
                committedAtMs
        ).completion().thenCompose(result -> {
            if (result instanceof PersistenceTransactionResult.Committed<?> committed
                    && committed.value() instanceof DurableCommitEvidence durable) {
                return publisher.publish(durable, consumers);
            }
            return SqliteOperationResults.completed(
                    SqliteOperationResults.failed(
                            OperationWorkflowResult.Status.DURABLE_COMMIT_FAILED,
                            operation,
                            List.of(),
                            SqliteOperationResults.transactionFailure(
                                    result,
                                    code + "_durable_commit_failed"
                            )
                    )
            );
        });
    }

    private Throwable operationFailure(OperationEnvelope operation, String fallback) {
        return operation.failureCode() == null
                ? new IllegalStateException(fallback)
                : new IllegalStateException(operation.failureCode());
    }

    private String requireCode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Live operation workflow code is required");
        }
        return value.trim();
    }

    /** Writer admission for atomic preparation plus the eventual exact workflow result. */
    public record Submission(
            @Nonnull SqliteSingleWriter.WriteAcceptance acceptance,
            @Nonnull CompletionStage<OperationWorkflowResult> completion
    ) {
        public Submission {
            if (acceptance == null || completion == null) {
                throw new IllegalArgumentException("Live operation submission is incomplete");
            }
        }
    }
}
