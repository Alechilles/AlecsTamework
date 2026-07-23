package com.alechilles.alecstamework.persistence.projection;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteProjectionGateway;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/**
 * Delivers committed outbox events sequentially and checkpoints only after consumer success.
 *
 * <p>Consumer callbacks always run outside SQLite transactions. A crash after apply but before
 * acknowledgement intentionally redelivers the event, so consumers must compare aggregate
 * revision and return {@link ProjectionApplyOutcome#ALREADY_APPLIED} for duplicates.</p>
 */
public final class ProjectionCoordinator {
    private final SqliteProjectionGateway gateway;
    private final ProjectionRetryPolicy retryPolicy;
    private final LongSupplier clock;
    private final ConcurrentHashMap<ProjectionConsumerId, AtomicInteger> failures =
            new ConcurrentHashMap<>();

    public ProjectionCoordinator(@Nonnull SqliteProjectionGateway gateway,
                                 @Nonnull ProjectionRetryPolicy retryPolicy,
                                 @Nonnull LongSupplier clock) {
        if (gateway == null || retryPolicy == null || clock == null) {
            throw new IllegalArgumentException("Projection coordinator dependencies are required");
        }
        this.gateway = gateway;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
    }

    /** Catches a consumer up to the outbox head observed at startup. */
    @Nonnull
    public CompletionStage<ProjectionCatchUpResult> startupCatchUp(
            @Nonnull ProjectionConsumer consumer,
            int batchSize
    ) {
        requireConsumerAndBatch(consumer, batchSize);
        return gateway.load(consumer.consumerId(), batchSize)
                .thenCompose(read -> startFromRead(consumer, batchSize, read));
    }

    /** Projects through a sequence returned by an already committed canonical transaction. */
    @Nonnull
    public CompletionStage<ProjectionCatchUpResult> afterCommit(
            @Nonnull ProjectionConsumer consumer,
            @Nonnull ProjectionSequence committedSequence,
            int batchSize
    ) {
        requireConsumerAndBatch(consumer, batchSize);
        if (committedSequence == null) {
            throw new IllegalArgumentException("Committed projection sequence is required");
        }
        return catchUpThrough(consumer, committedSequence, batchSize, 0);
    }

    /** Compares a canonical rebuild with the currently published projection outside transactions. */
    @Nonnull
    public <T> ProjectionRebuildResult verifyRebuild(@Nonnull ProjectionRebuildProbe<T> probe) {
        if (probe == null) {
            throw new IllegalArgumentException("Projection rebuild probe is required");
        }
        try {
            T canonical = probe.rebuildCanonical();
            T projected = probe.readProjection();
            return new ProjectionRebuildResult(
                    probe.equivalent(canonical, projected)
                            ? ProjectionRebuildResult.Status.EQUIVALENT
                            : ProjectionRebuildResult.Status.MISMATCH,
                    null
            );
        } catch (Exception failure) {
            return new ProjectionRebuildResult(
                    ProjectionRebuildResult.Status.FAILED,
                    failure
            );
        }
    }

    private CompletionStage<ProjectionCatchUpResult> startFromRead(
            ProjectionConsumer consumer,
            int batchSize,
            PersistenceReadResult<ProjectionBatch> read
    ) {
        if (read instanceof PersistenceReadResult.Found<ProjectionBatch> found) {
            ProjectionBatch batch = found.value();
            if (batch.checkpoint().acknowledgedSequence().compareTo(batch.head()) >= 0) {
                return completedSuccess(
                        consumer.consumerId(),
                        batch.checkpoint().acknowledgedSequence(),
                        0
                );
            }
            return processBatch(consumer, batch, batch.head(), batchSize, 0);
        }
        Throwable failure = readFailure(read, "projection_startup_read_absent");
        return completedFailure(
                consumer.consumerId(),
                ProjectionCatchUpResult.Status.READ_FAILED,
                ProjectionSequence.ORIGIN,
                0,
                failure
        );
    }

    private CompletionStage<ProjectionCatchUpResult> catchUpThrough(
            ProjectionConsumer consumer,
            ProjectionSequence target,
            int batchSize,
            int delivered
    ) {
        return gateway.load(consumer.consumerId(), batchSize).thenCompose(read -> {
            if (!(read instanceof PersistenceReadResult.Found<ProjectionBatch> found)) {
                return completedFailure(
                        consumer.consumerId(),
                        ProjectionCatchUpResult.Status.READ_FAILED,
                        ProjectionSequence.ORIGIN,
                        delivered,
                        readFailure(read, "projection_catchup_read_absent")
                );
            }
            ProjectionBatch batch = found.value();
            ProjectionSequence acknowledged = batch.checkpoint().acknowledgedSequence();
            if (acknowledged.compareTo(target) >= 0) {
                return completedSuccess(consumer.consumerId(), acknowledged, delivered);
            }
            return processBatch(consumer, batch, target, batchSize, delivered);
        });
    }

    private CompletionStage<ProjectionCatchUpResult> processBatch(
            ProjectionConsumer consumer,
            ProjectionBatch batch,
            ProjectionSequence target,
            int batchSize,
            int delivered
    ) {
        List<ProjectionEvent> eligible = batch.events().stream()
                .filter(event -> event.sequence().compareTo(target) <= 0)
                .toList();
        if (eligible.isEmpty()) {
            return completedFailure(
                    consumer.consumerId(),
                    ProjectionCatchUpResult.Status.READ_FAILED,
                    batch.checkpoint().acknowledgedSequence(),
                    delivered,
                    new IllegalStateException("projection_sequence_gap")
            );
        }
        return applySequentially(
                consumer,
                eligible,
                0,
                batch.checkpoint().acknowledgedSequence(),
                delivered
        ).thenCompose(result -> {
            if (result.status() != ProjectionCatchUpResult.Status.CAUGHT_UP) {
                return CompletableFuture.completedFuture(result);
            }
            if (result.acknowledged().compareTo(target) >= 0) {
                return completedSuccess(
                        consumer.consumerId(), result.acknowledged(), result.deliveredCount()
                );
            }
            return catchUpThrough(
                    consumer, target, batchSize, result.deliveredCount()
            );
        });
    }

    private CompletionStage<ProjectionCatchUpResult> applySequentially(
            ProjectionConsumer consumer,
            List<ProjectionEvent> events,
            int index,
            ProjectionSequence acknowledged,
            int delivered
    ) {
        if (index >= events.size()) {
            return completedSuccess(consumer.consumerId(), acknowledged, delivered);
        }
        ProjectionEvent event = events.get(index);
        try {
            ProjectionApplyOutcome outcome = consumer.apply(event);
            if (outcome == null) {
                throw new IllegalStateException("projection_consumer_returned_null");
            }
        } catch (Exception failure) {
            return completedFailure(
                    consumer.consumerId(),
                    ProjectionCatchUpResult.Status.CONSUMER_FAILED,
                    acknowledged,
                    delivered,
                    failure
            );
        }
        return gateway.acknowledge(
                consumer.consumerId(),
                event.operationId(),
                event.sequence(),
                clock.getAsLong()
        ).completion().thenCompose(result -> {
            if (!(result instanceof PersistenceTransactionResult.Committed<?> committed)
                    || !(committed.value() instanceof ProjectionCheckpoint checkpoint)) {
                return completedFailure(
                        consumer.consumerId(),
                        ProjectionCatchUpResult.Status.CHECKPOINT_FAILED,
                        acknowledged,
                        delivered + 1,
                        checkpointFailure(result)
                );
            }
            return applySequentially(
                    consumer,
                    events,
                    index + 1,
                    checkpoint.acknowledgedSequence(),
                    delivered + 1
            );
        });
    }

    private CompletionStage<ProjectionCatchUpResult> completedSuccess(
            ProjectionConsumerId consumerId,
            ProjectionSequence acknowledged,
            int delivered
    ) {
        failures.remove(consumerId);
        return CompletableFuture.completedFuture(new ProjectionCatchUpResult(
                ProjectionCatchUpResult.Status.CAUGHT_UP,
                acknowledged,
                delivered,
                0,
                null
        ));
    }

    private CompletionStage<ProjectionCatchUpResult> completedFailure(
            ProjectionConsumerId consumerId,
            ProjectionCatchUpResult.Status status,
            ProjectionSequence acknowledged,
            int delivered,
            Throwable failure
    ) {
        int count = failures.computeIfAbsent(consumerId, ignored -> new AtomicInteger())
                .incrementAndGet();
        return CompletableFuture.completedFuture(new ProjectionCatchUpResult(
                status,
                acknowledged,
                delivered,
                retryPolicy.delayMs(count),
                failure
        ));
    }

    private Throwable readFailure(PersistenceReadResult<ProjectionBatch> read, String absentCode) {
        if (read instanceof PersistenceReadResult.Failed<ProjectionBatch> failed) {
            return failed.failure().cause() == null
                    ? new IllegalStateException(failed.failure().code())
                    : failed.failure().cause();
        }
        return new IllegalStateException(absentCode);
    }

    private Throwable checkpointFailure(PersistenceTransactionResult<?> result) {
        if (result instanceof PersistenceTransactionResult.RolledBack<?> rolledBack) {
            return failureCause(rolledBack.failure().cause(), rolledBack.failure().code());
        }
        if (result instanceof PersistenceTransactionResult.Unknown<?> unknown) {
            return failureCause(unknown.failure().cause(), unknown.failure().code());
        }
        return new IllegalStateException("projection_checkpoint_rejected");
    }

    private Throwable failureCause(Throwable cause, String code) {
        return cause == null ? new IllegalStateException(code) : cause;
    }

    private void requireConsumerAndBatch(ProjectionConsumer consumer, int batchSize) {
        if (consumer == null || consumer.consumerId() == null
                || batchSize <= 0 || batchSize > 10_000) {
            throw new IllegalArgumentException("Valid projection consumer and batch size are required");
        }
    }
}
