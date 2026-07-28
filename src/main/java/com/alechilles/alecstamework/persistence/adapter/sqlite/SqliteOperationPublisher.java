package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.DurableCommitEvidence;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.projection.ProjectionCatchUpResult;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumerId;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionSequence;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/**
 * Shared durable-evidence projection and terminal publication workflow.
 *
 * <p>Database-only and live-boundary operations use this same implementation after their
 * canonical durable commit.</p>
 */
public final class SqliteOperationPublisher {
    private static final int PROJECTION_BATCH_SIZE = 256;

    private final SqliteOperationEngine operations;
    private final SqliteOperationEvidenceReader evidence;
    private final ProjectionCoordinator projections;
    private final LongSupplier clock;

    public SqliteOperationPublisher(
            @Nonnull SqliteOperationEngine operations,
            @Nonnull SqliteOperationEvidenceReader evidence,
            @Nonnull ProjectionCoordinator projections,
            @Nonnull LongSupplier clock
    ) {
        if (operations == null || evidence == null || projections == null || clock == null) {
            throw new IllegalArgumentException("Operation publisher dependencies are required");
        }
        this.operations = operations;
        this.evidence = evidence;
        this.projections = projections;
        this.clock = clock;
    }

    /** Resumes durable publication or returns immutable evidence for a published replay. */
    @Nonnull
    public CompletionStage<OperationWorkflowResult> resume(
            @Nonnull OperationEnvelope operation,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        if (operation == null) {
            throw new IllegalArgumentException("Operation publication envelope is required");
        }
        List<ProjectionConsumer> consumers = validateConsumers(requiredConsumers);
        return switch (operation.phase()) {
            case DURABLE -> loadAndPublish(operation, consumers);
            case PUBLISHED -> loadPublished(operation);
            default -> SqliteOperationResults.completed(SqliteOperationResults.failed(
                    OperationWorkflowResult.Status.INVALID_PHASE,
                    operation,
                    List.of(),
                    new IllegalStateException(
                            "operation_publish_phase_"
                                    + operation.phase().name().toLowerCase()
                    )
            ));
        };
    }

    /** Projects one exact durable commit and transitions it to published. */
    @Nonnull
    public CompletionStage<OperationWorkflowResult> publish(
            @Nonnull DurableCommitEvidence durable,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        if (durable == null || durable.operation().phase() != OperationPhase.DURABLE) {
            throw new IllegalArgumentException("Durable publication evidence is required");
        }
        List<ProjectionConsumer> consumers = validateConsumers(requiredConsumers);
        ProjectionSequence target = durable.outboxHead();
        return publishNext(consumers, 0, target).thenCompose(publication -> {
            if (publication != null) {
                return SqliteOperationResults.completed(SqliteOperationResults.failed(
                        OperationWorkflowResult.Status.PUBLICATION_PENDING,
                        durable.operation(),
                        durable.events(),
                        publication
                ));
            }
            return transitionPublished(durable);
        });
    }

    /** Validates and snapshots a required consumer set before any canonical mutation. */
    @Nonnull
    List<ProjectionConsumer> validateConsumers(
            @Nonnull List<? extends ProjectionConsumer> consumers
    ) {
        if (consumers == null) {
            throw new IllegalArgumentException("Required projection consumers are required");
        }
        HashSet<ProjectionConsumerId> ids = new HashSet<>();
        ArrayList<ProjectionConsumer> copy = new ArrayList<>();
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

    private CompletionStage<OperationWorkflowResult> loadAndPublish(
            OperationEnvelope operation,
            List<ProjectionConsumer> consumers
    ) {
        return evidence.find(operation.operationId()).thenCompose(read -> {
            if (read instanceof PersistenceReadResult.Found<DurableCommitEvidence> found) {
                return publish(found.value(), consumers);
            }
            return SqliteOperationResults.completed(SqliteOperationResults.failed(
                    OperationWorkflowResult.Status.DURABLE_READ_FAILED,
                    operation,
                    List.of(),
                    readFailure(read)
            ));
        });
    }

    private CompletionStage<OperationWorkflowResult> loadPublished(
            OperationEnvelope operation
    ) {
        return evidence.find(operation.operationId()).thenApply(read -> {
            if (read instanceof PersistenceReadResult.Found<DurableCommitEvidence> found) {
                return new OperationWorkflowResult(
                        OperationWorkflowResult.Status.PUBLISHED,
                        operation,
                        found.value().events(),
                        null
                );
            }
            return SqliteOperationResults.failed(
                    OperationWorkflowResult.Status.DURABLE_READ_FAILED,
                    operation,
                    List.of(),
                    readFailure(read)
            );
        });
    }

    private CompletionStage<OperationWorkflowResult> transitionPublished(
            DurableCommitEvidence durable
    ) {
        return operations.transition(
                durable.operation(),
                OperationPhase.PUBLISHED,
                null,
                null,
                clock.getAsLong()
        ).completion().thenApply(result -> {
            if (result instanceof PersistenceTransactionResult.Committed<?> committed
                    && committed.value() instanceof OperationEnvelope published) {
                return new OperationWorkflowResult(
                        OperationWorkflowResult.Status.PUBLISHED,
                        published,
                        durable.events(),
                        null
                );
            }
            return SqliteOperationResults.failed(
                    OperationWorkflowResult.Status.TERMINALIZATION_FAILED,
                    durable.operation(),
                    durable.events(),
                    SqliteOperationResults.transactionFailure(
                            result,
                            "operation_publish_transition_failed"
                    )
            );
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

}
