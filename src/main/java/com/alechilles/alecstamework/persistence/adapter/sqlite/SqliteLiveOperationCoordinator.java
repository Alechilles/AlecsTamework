package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.operation.DurableCommitEvidence;
import com.alechilles.alecstamework.persistence.operation.DurableOperationCleanupBoundary;
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
import java.util.function.Supplier;
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
        return execute(
                definition,
                request,
                detail,
                liveBoundary,
                DurableOperationCleanupBoundary.notRequired(),
                durableWork,
                requiredConsumers,
                workflowCode
        );
    }

    /**
     * Starts or resumes an operation whose durable external cleanup must complete before
     * projection publication.
     */
    @Nonnull
    public <T> Submission execute(
            @Nonnull OperationDefinition<T> definition,
            @Nonnull OperationRequest<T> request,
            @Nonnull PreparedOperationDetail detail,
            @Nonnull LiveOperationBoundary<T> liveBoundary,
            @Nonnull DurableOperationCleanupBoundary<T> durableCleanup,
            @Nonnull TimedDurableOperationWork<T> durableWork,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers,
            @Nonnull String workflowCode
    ) {
        if (definition == null || request == null || detail == null
                || liveBoundary == null || durableCleanup == null
                || durableWork == null) {
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
                            durableCleanup,
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
            DurableOperationCleanupBoundary<T> durableCleanup,
            TimedDurableOperationWork<T> durableWork,
            List<ProjectionConsumer> consumers,
            String code
    ) {
        return switch (operation.phase()) {
            case PUBLISHED -> publisher.resume(operation, consumers);
            case DURABLE -> cleanupThenPublish(
                    operation,
                    payload,
                    durableCleanup,
                    List.of(),
                    () -> publisher.resume(operation, consumers),
                    code
            );
            case PREPARED, RETRYABLE -> transitionToLive(
                    operation, payload, liveBoundary, durableCleanup,
                    durableWork, consumers, code
            );
            case LIVE_APPLYING -> applyOrResolveLive(
                    operation, payload, liveBoundary, durableCleanup,
                    durableWork, consumers, code
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
            DurableOperationCleanupBoundary<T> durableCleanup,
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
                        applying, payload, liveBoundary, durableCleanup,
                        durableWork, consumers, code
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
            DurableOperationCleanupBoundary<T> durableCleanup,
            TimedDurableOperationWork<T> durableWork,
            List<ProjectionConsumer> consumers,
            String code
    ) {
        CompletionStage<LiveOperationResult> resolution;
        try {
            resolution = liveBoundary.applyOrResolve(payload, operation);
            if (resolution == null) {
                throw new IllegalStateException(code + "_live_boundary_returned_null");
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
                        durableCleanup,
                        durableWork,
                        consumers,
                        code,
                        requireLiveResult(live, code)
                ));
    }

    private <T> CompletionStage<OperationWorkflowResult> continueLiveResult(
            OperationEnvelope operation,
            T payload,
            DurableOperationCleanupBoundary<T> durableCleanup,
            TimedDurableOperationWork<T> durableWork,
            List<ProjectionConsumer> consumers,
            String code,
            LiveOperationResult live
    ) {
        return switch (live.status()) {
            case CONFIRMED -> commit(
                    operation, payload, durableCleanup,
                    durableWork, consumers, code
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

    private LiveOperationResult requireLiveResult(
            LiveOperationResult live,
            String code
    ) {
        return live == null
                ? LiveOperationResult.retryable(
                        code + "_live_boundary_returned_null",
                        new IllegalStateException(
                                code + "_live_boundary_returned_null"
                        )
                )
                : live;
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
            DurableOperationCleanupBoundary<T> durableCleanup,
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
                return cleanupThenPublish(
                        durable.operation(),
                        payload,
                        durableCleanup,
                        durable.events(),
                        () -> publisher.publish(durable, consumers),
                        code
                );
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

    private <T> CompletionStage<OperationWorkflowResult> cleanupThenPublish(
            OperationEnvelope durableOperation,
            T payload,
            DurableOperationCleanupBoundary<T> cleanup,
            List<com.alechilles.alecstamework.persistence.projection
                    .ProjectionEvent> events,
            Supplier<CompletionStage<OperationWorkflowResult>> publication,
            String code
    ) {
        CompletionStage<LiveOperationResult> resolution;
        try {
            resolution = cleanup.cleanupAfterDurable(
                    payload, durableOperation
            );
            if (resolution == null) {
                throw new IllegalStateException(
                        code + "_durable_cleanup_returned_null"
                );
            }
        } catch (Throwable failure) {
            resolution = LiveOperationResult.retryable(
                    code + "_durable_cleanup_failed", failure
            ).completed();
        }
        return resolution.handle((result, failure) ->
                failure == null && result != null
                        ? result
                        : LiveOperationResult.retryable(
                                code + "_durable_cleanup_failed",
                                failure
                        )
        ).thenCompose(result -> {
            if (result.status() == LiveOperationResult.Status.CONFIRMED) {
                try {
                    CompletionStage<OperationWorkflowResult> published =
                            publication.get();
                    return published == null
                            ? cleanupPending(
                                    durableOperation,
                                    events,
                                    code + "_publication_missing",
                                    null
                            )
                            : published;
                } catch (Throwable failure) {
                    return cleanupPending(
                            durableOperation,
                            events,
                            code + "_publication_failed",
                            failure
                    );
                }
            }
            return cleanupPending(
                    durableOperation,
                    events,
                    result.code(),
                    result.cause()
            );
        });
    }

    private CompletionStage<OperationWorkflowResult> cleanupPending(
            OperationEnvelope durableOperation,
            List<com.alechilles.alecstamework.persistence.projection
                    .ProjectionEvent> events,
            String detail,
            Throwable cause
    ) {
        Throwable failure = cause == null
                ? new IllegalStateException(detail)
                : cause;
        return SqliteOperationResults.completed(
                SqliteOperationResults.failed(
                        OperationWorkflowResult.Status.PUBLICATION_PENDING,
                        durableOperation,
                        events,
                        failure
                )
        );
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
