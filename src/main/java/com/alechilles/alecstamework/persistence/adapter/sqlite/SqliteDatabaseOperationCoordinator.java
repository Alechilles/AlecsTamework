package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.operation.DatabaseOperationResult;
import com.alechilles.alecstamework.persistence.operation.DurableCommitEvidence;
import com.alechilles.alecstamework.persistence.operation.DurableOperationWork;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.projection.ProjectionCatchUpResult;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumerId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionSequence;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
    private static final int PROJECTION_BATCH_SIZE = 256;

    private final SqliteOperationEngine operations;
    private final SqliteOperationEvidenceReader evidence;
    private final ProjectionCoordinator projections;
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
        this.evidence = evidence;
        this.projections = projections;
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
        if (definition == null || request == null || work == null
                || requiredConsumers == null) {
            throw new IllegalArgumentException("Complete database operation request is required");
        }
        List<ProjectionConsumer> consumers = validateConsumers(requiredConsumers);
        SqliteUnitOfWorkRunner.Submission<OperationEnvelope> prepared =
                operations.prepare(definition, request);
        CompletionStage<DatabaseOperationResult> completion =
                prepared.completion().thenCompose(result -> {
                    if (!(result instanceof PersistenceTransactionResult.Committed<?> committed)
                            || !(committed.value() instanceof OperationEnvelope preparedOperation)) {
                        return completed(failed(
                                DatabaseOperationResult.Status.PREPARE_FAILED,
                                null,
                                List.of(),
                                transactionFailure(result, "operation_prepare_failed")
                        ));
                    }
                    return continueFrom(preparedOperation, work, consumers);
                });
        return new Submission(prepared.acceptance(), completion);
    }

    private CompletionStage<DatabaseOperationResult> continueFrom(
            OperationEnvelope operation,
            DurableOperationWork work,
            List<ProjectionConsumer> consumers
    ) {
        return switch (operation.phase()) {
            case PUBLISHED -> loadPublished(operation);
            case DURABLE -> loadAndPublish(operation, consumers);
            case PREPARED, RETRYABLE -> commitAndPublish(operation, work, consumers);
            default -> completed(failed(
                    DatabaseOperationResult.Status.INVALID_PHASE,
                    operation,
                    List.of(),
                    new IllegalStateException(
                            "database_operation_phase_" + operation.phase().name().toLowerCase()
                    )
            ));
        };
    }

    private CompletionStage<DatabaseOperationResult> commitAndPublish(
            OperationEnvelope operation,
            DurableOperationWork work,
            List<ProjectionConsumer> consumers
    ) {
        return operations.commitDurable(operation, work, clock.getAsLong())
                .completion().thenCompose(result -> {
                    if (!(result instanceof PersistenceTransactionResult.Committed<?> committed)
                            || !(committed.value() instanceof DurableCommitEvidence evidence)) {
                        return completed(failed(
                                DatabaseOperationResult.Status.DURABLE_COMMIT_FAILED,
                                operation,
                                List.of(),
                                transactionFailure(result, "operation_durable_commit_failed")
                        ));
                    }
                    return publish(evidence, consumers);
                });
    }

    private CompletionStage<DatabaseOperationResult> loadAndPublish(
            OperationEnvelope operation,
            List<ProjectionConsumer> consumers
    ) {
        return evidence.find(operation.operationId()).thenCompose(read -> {
            if (read instanceof PersistenceReadResult.Found<DurableCommitEvidence> found) {
                return publish(found.value(), consumers);
            }
            return completed(failed(
                    DatabaseOperationResult.Status.DURABLE_READ_FAILED,
                    operation,
                    List.of(),
                    readFailure(read)
            ));
        });
    }

    private CompletionStage<DatabaseOperationResult> loadPublished(
            OperationEnvelope operation
    ) {
        return evidence.find(operation.operationId()).thenApply(read -> {
            if (read instanceof PersistenceReadResult.Found<DurableCommitEvidence> found) {
                return new DatabaseOperationResult(
                        DatabaseOperationResult.Status.PUBLISHED,
                        operation,
                        found.value().events(),
                        null
                );
            }
            return failed(
                    DatabaseOperationResult.Status.DURABLE_READ_FAILED,
                    operation,
                    List.of(),
                    readFailure(read)
            );
        });
    }

    private CompletionStage<DatabaseOperationResult> publish(
            DurableCommitEvidence durable,
            List<ProjectionConsumer> consumers
    ) {
        ProjectionSequence target = durable.events().getLast().sequence();
        return publishNext(consumers, 0, target).thenCompose(publication -> {
            if (publication != null) {
                return completed(failed(
                        DatabaseOperationResult.Status.PUBLICATION_PENDING,
                        durable.operation(),
                        durable.events(),
                        publication
                ));
            }
            return operations.transition(
                    durable.operation(),
                    OperationPhase.PUBLISHED,
                    null,
                    null,
                    clock.getAsLong()
            ).completion().thenApply(result -> {
                if (result instanceof PersistenceTransactionResult.Committed<?> committed
                        && committed.value() instanceof OperationEnvelope publishedOperation) {
                    return new DatabaseOperationResult(
                            DatabaseOperationResult.Status.PUBLISHED,
                            publishedOperation,
                            durable.events(),
                            null
                    );
                }
                return failed(
                        DatabaseOperationResult.Status.TERMINALIZATION_FAILED,
                        durable.operation(),
                        durable.events(),
                        transactionFailure(result, "operation_publish_transition_failed")
                );
            });
        });
    }

    private CompletionStage<Throwable> publishNext(
            List<ProjectionConsumer> consumers,
            int index,
            ProjectionSequence target
    ) {
        if (index >= consumers.size()) {
            return CompletableFuture.completedFuture(null);
        }
        return projections.afterCommit(
                consumers.get(index),
                target,
                PROJECTION_BATCH_SIZE
        ).thenCompose(result -> {
            if (result.status() != ProjectionCatchUpResult.Status.CAUGHT_UP) {
                return CompletableFuture.completedFuture(
                        result.failure() == null
                                ? new IllegalStateException(
                                "projection_" + result.status().name().toLowerCase()
                        )
                                : result.failure()
                );
            }
            return publishNext(consumers, index + 1, target);
        });
    }

    private List<ProjectionConsumer> validateConsumers(
            List<? extends ProjectionConsumer> consumers
    ) {
        HashSet<ProjectionConsumerId> ids = new HashSet<>();
        java.util.ArrayList<ProjectionConsumer> copy = new java.util.ArrayList<>();
        for (ProjectionConsumer consumer : consumers) {
            if (consumer == null || consumer.consumerId() == null
                    || !ids.add(consumer.consumerId())) {
                throw new IllegalArgumentException(
                        "Required projection consumers must be complete and unique"
                );
            }
            copy.add(consumer);
        }
        return List.copyOf(copy);
    }

    private Throwable readFailure(
            PersistenceReadResult<DurableCommitEvidence> result
    ) {
        if (result instanceof PersistenceReadResult.Failed<DurableCommitEvidence> failed) {
            return failed.failure().cause() == null
                    ? new IllegalStateException(failed.failure().code())
                    : failed.failure().cause();
        }
        return new IllegalStateException("durable_operation_evidence_absent");
    }

    private Throwable transactionFailure(
            PersistenceTransactionResult<?> result,
            String fallback
    ) {
        if (result instanceof PersistenceTransactionResult.RolledBack<?> rolledBack) {
            return cause(rolledBack.failure().cause(), rolledBack.failure().code());
        }
        if (result instanceof PersistenceTransactionResult.Unknown<?> unknown) {
            return cause(unknown.failure().cause(), unknown.failure().code());
        }
        if (result instanceof PersistenceTransactionResult.Rejected<?> rejected) {
            return new IllegalStateException(rejected.reason().name().toLowerCase());
        }
        return new IllegalStateException(fallback);
    }

    private Throwable cause(Throwable cause, String code) {
        return cause == null ? new IllegalStateException(code) : cause;
    }

    private DatabaseOperationResult failed(
            DatabaseOperationResult.Status status,
            OperationEnvelope operation,
            List<ProjectionEvent> events,
            Throwable failure
    ) {
        return new DatabaseOperationResult(status, operation, events, failure);
    }

    private CompletionStage<DatabaseOperationResult> completed(
            DatabaseOperationResult result
    ) {
        return CompletableFuture.completedFuture(result);
    }

    /** Writer admission for preparation plus the eventual exact workflow result. */
    public record Submission(
            @Nonnull SqliteSingleWriter.WriteAcceptance acceptance,
            @Nonnull CompletionStage<DatabaseOperationResult> completion
    ) {
        public Submission {
            if (acceptance == null || completion == null) {
                throw new IllegalArgumentException("Database operation submission is incomplete");
            }
        }
    }
}
