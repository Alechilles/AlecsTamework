package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.DurableCommitEvidence;
import com.alechilles.alecstamework.persistence.operation.DurableOperationWork;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/**
 * One reusable prepare/durable/project/publish workflow for operations with no external live step.
 *
 * <p>A repeated idempotent request resumes committed {@code DURABLE} evidence without executing
 * its canonical mutation again.</p>
 */
public final class SqliteDatabaseOperationCoordinator {
    private final SqliteOperationEngine operations;
    private final SqliteOperationPublisher publisher;
    private final LongSupplier clock;

    public SqliteDatabaseOperationCoordinator(
            @Nonnull SqliteOperationEngine operations,
            @Nonnull SqliteOperationEvidenceReader evidence,
            @Nonnull ProjectionCoordinator projections,
            @Nonnull LongSupplier clock
    ) {
        if (operations == null || evidence == null || projections == null || clock == null) {
            throw new IllegalArgumentException(
                    "Database operation coordinator dependencies are required"
            );
        }
        this.operations = operations;
        this.publisher = new SqliteOperationPublisher(
                operations,
                evidence,
                projections,
                clock
        );
        this.clock = clock;
    }

    /** Starts or resumes one exact database-only operation. */
    @Nonnull
    public <T> Submission execute(
            @Nonnull OperationDefinition<T> definition,
            @Nonnull OperationRequest<T> request,
            @Nonnull DurableOperationWork work,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        return execute(
                definition,
                request,
                PreparedOperationDetail.none(),
                work,
                requiredConsumers
        );
    }

    /**
     * Starts or resumes one exact database-only operation with atomic preparation validation.
     */
    @Nonnull
    public <T> Submission execute(
            @Nonnull OperationDefinition<T> definition,
            @Nonnull OperationRequest<T> request,
            @Nonnull PreparedOperationDetail detail,
            @Nonnull DurableOperationWork work,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        if (definition == null || request == null || work == null
                || detail == null || requiredConsumers == null) {
            throw new IllegalArgumentException("Complete database operation request is required");
        }
        List<ProjectionConsumer> consumers =
                publisher.validateConsumers(requiredConsumers);
        SqliteUnitOfWorkRunner.Submission<OperationEnvelope> prepared =
                operations.prepare(definition, request, detail);
        CompletionStage<OperationWorkflowResult> completion =
                prepared.completion().thenCompose(result -> {
                    if (!(result instanceof PersistenceTransactionResult.Committed<?> committed)
                            || !(committed.value() instanceof OperationEnvelope preparedOperation)) {
                        return SqliteOperationResults.completed(
                                SqliteOperationResults.failed(
                                OperationWorkflowResult.Status.PREPARE_FAILED,
                                null,
                                List.of(),
                                SqliteOperationResults.transactionFailure(
                                        result,
                                        "operation_prepare_failed"
                                )
                        ));
                    }
                    return continueFrom(preparedOperation, work, consumers);
                });
        return new Submission(prepared.acceptance(), completion);
    }

    private CompletionStage<OperationWorkflowResult> continueFrom(
            OperationEnvelope operation,
            DurableOperationWork work,
            List<ProjectionConsumer> consumers
    ) {
        return switch (operation.phase()) {
            case PUBLISHED, DURABLE -> publisher.resume(operation, consumers);
            case PREPARED, RETRYABLE -> commitAndPublish(operation, work, consumers);
            default -> SqliteOperationResults.completed(SqliteOperationResults.failed(
                    OperationWorkflowResult.Status.INVALID_PHASE,
                    operation,
                    List.of(),
                    new IllegalStateException(
                            "database_operation_phase_" + operation.phase().name().toLowerCase()
                    )
            ));
        };
    }

    private CompletionStage<OperationWorkflowResult> commitAndPublish(
            OperationEnvelope operation,
            DurableOperationWork work,
            List<ProjectionConsumer> consumers
    ) {
        return operations.commitDurable(operation, work, clock.getAsLong())
                .completion().thenCompose(result -> {
                    if (!(result instanceof PersistenceTransactionResult.Committed<?> committed)
                            || !(committed.value() instanceof DurableCommitEvidence evidence)) {
                        return SqliteOperationResults.completed(
                                SqliteOperationResults.failed(
                                OperationWorkflowResult.Status.DURABLE_COMMIT_FAILED,
                                operation,
                                List.of(),
                                SqliteOperationResults.transactionFailure(
                                        result,
                                        "operation_durable_commit_failed"
                                )
                        ));
                    }
                    return publisher.publish(evidence, consumers);
                });
    }

    /** Writer admission for preparation plus the eventual exact workflow result. */
    public record Submission(
            @Nonnull SqliteSingleWriter.WriteAcceptance acceptance,
            @Nonnull CompletionStage<OperationWorkflowResult> completion
    ) {
        public Submission {
            if (acceptance == null || completion == null) {
                throw new IllegalArgumentException("Database operation submission is incomplete");
            }
        }
    }
}
