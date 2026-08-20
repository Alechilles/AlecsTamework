package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadPriority;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.kernel.TransactionReplayPolicy;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.projection.ProjectionBatch;
import com.alechilles.alecstamework.persistence.projection.ProjectionCheckpoint;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumerId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionSequence;
import com.alechilles.alecstamework.persistence.projection.ProjectionSubscription;
import com.alechilles.alecstamework.persistence.runtime.PersistenceThroughputMetrics;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Async read/ack gateway used only after canonical transactions have committed.
 *
 * <p>Checkpoint writes use exact readback after an unknown commit and therefore never blindly
 * retry an ambiguous acknowledgement.</p>
 */
public final class SqliteProjectionGateway {
    private static final OperationKind CHECKPOINT_KIND =
            new OperationKind("projection_checkpoint");
    private static final PersistenceReadKind BATCH_READ =
            new PersistenceReadKind("projection_batch");
    private static final PersistenceReadKind CHECKPOINT_READBACK =
            new PersistenceReadKind("projection_checkpoint_readback");

    private final SqliteReadExecutor reads;
    private final SqliteUnitOfWorkRunner units;
    private final Runnable afterHeadRead;
    private final PersistenceThroughputMetrics throughputMetrics;

    public SqliteProjectionGateway(@Nonnull SqliteReadExecutor reads,
                                   @Nonnull SqliteUnitOfWorkRunner units) {
        this(reads, units, () -> { }, PersistenceThroughputMetrics.NO_OP);
    }

    /** Test-only boundary injection for a concurrent outbox append. */
    SqliteProjectionGateway(@Nonnull SqliteReadExecutor reads,
                            @Nonnull SqliteUnitOfWorkRunner units,
                            @Nonnull Runnable afterHeadRead) {
        this(reads, units, afterHeadRead, PersistenceThroughputMetrics.NO_OP);
    }

    /** Builds a gateway with passive throughput evidence. */
    public SqliteProjectionGateway(
            @Nonnull SqliteReadExecutor reads,
            @Nonnull SqliteUnitOfWorkRunner units,
            @Nonnull PersistenceThroughputMetrics throughputMetrics
    ) {
        this(reads, units, () -> { }, throughputMetrics);
    }

    /** Test-only boundary with a concurrent outbox append and metrics. */
    SqliteProjectionGateway(
            @Nonnull SqliteReadExecutor reads,
            @Nonnull SqliteUnitOfWorkRunner units,
            @Nonnull Runnable afterHeadRead,
            @Nonnull PersistenceThroughputMetrics throughputMetrics
    ) {
        if (reads == null || units == null || afterHeadRead == null) {
            throw new IllegalArgumentException("Projection gateway dependencies are required");
        }
        if (throughputMetrics == null) {
            throw new IllegalArgumentException("Projection metrics are required");
        }
        this.reads = reads;
        this.units = units;
        this.afterHeadRead = afterHeadRead;
        this.throughputMetrics = throughputMetrics;
    }

    /** Loads an ordered consumer batch from its durable checkpoint. */
    @Nonnull
    public CompletionStage<PersistenceReadResult<ProjectionBatch>> load(
            @Nonnull ProjectionConsumerId consumerId,
            @Nonnull ProjectionSubscription subscription,
            @Nonnull ProjectionSequence target,
            int limit
    ) {
        if (consumerId == null || subscription == null || target == null
                || limit <= 0 || limit > 10_000) {
            throw new IllegalArgumentException("Valid projection consumer and batch limit are required");
        }
        return reads.execute(new SqliteReadCommand<>(
                BATCH_READ,
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> loadBatch(
                        connection, consumerId, subscription, target, limit
                )
        ));
    }

    /** Compatibility overload that snapshots the current head for wildcard delivery. */
    @Nonnull
    public CompletionStage<PersistenceReadResult<ProjectionBatch>> load(
            @Nonnull ProjectionConsumerId consumerId,
            int limit
    ) {
        return loadToHead(
                consumerId, ProjectionSubscription.allEvents(), limit
        );
    }

    /** Compatibility overload with the bounded target before the subscription. */
    @Nonnull
    public CompletionStage<PersistenceReadResult<ProjectionBatch>> load(
            @Nonnull ProjectionConsumerId consumerId,
            @Nonnull ProjectionSequence target,
            @Nonnull ProjectionSubscription subscription,
            int limit
    ) {
        return load(consumerId, subscription, target, limit);
    }

    /** Loads one startup snapshot and routes rows only for the consumer subscription. */
    @Nonnull
    public CompletionStage<PersistenceReadResult<ProjectionBatch>> loadToHead(
            @Nonnull ProjectionConsumerId consumerId,
            @Nonnull ProjectionSubscription subscription,
            int limit
    ) {
        if (consumerId == null || subscription == null
                || limit <= 0 || limit > 10_000) {
            throw new IllegalArgumentException("Valid projection consumer and batch limit are required");
        }
        return reads.execute(new SqliteReadCommand<>(
                BATCH_READ,
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> loadToHead(
                        connection, consumerId, subscription, limit
                )
        ));
    }

    private PersistenceReadResult<ProjectionBatch> loadToHead(
            Connection connection,
            ProjectionConsumerId consumerId,
            ProjectionSubscription subscription,
            int limit
    ) throws Exception {
        connection.setAutoCommit(false);
        try {
            SqliteProjectionOutboxStore store =
                    new SqliteProjectionOutboxStore(connection);
            ProjectionCheckpoint checkpoint = store.findCheckpoint(consumerId)
                    .orElse(new ProjectionCheckpoint(
                            consumerId, ProjectionSequence.ORIGIN, 0
                    ));
            ProjectionSequence target = store.head();
            afterHeadRead.run();
            return foundBatch(
                    checkpoint,
                    target,
                    store.readSubscribedAfter(
                            checkpoint.acknowledgedSequence(),
                            target,
                            subscription,
                            limit
                    ),
                    limit
            );
        } finally {
            connection.rollback();
        }
    }

    private PersistenceReadResult<ProjectionBatch> loadBatch(
            Connection connection,
            ProjectionConsumerId consumerId,
            ProjectionSubscription subscription,
            ProjectionSequence target,
            int limit
    ) throws Exception {
        connection.setAutoCommit(false);
        try {
            SqliteProjectionOutboxStore store =
                    new SqliteProjectionOutboxStore(connection);
            ProjectionCheckpoint checkpoint = store.findCheckpoint(consumerId)
                    .orElse(new ProjectionCheckpoint(
                            consumerId, ProjectionSequence.ORIGIN, 0
                    ));
            return foundBatch(
                    checkpoint,
                    target,
                    store.readSubscribedAfter(
                            checkpoint.acknowledgedSequence(),
                            target,
                            subscription,
                            limit
                    ),
                    limit
            );
        } finally {
            connection.rollback();
        }
    }

    /** Durably acknowledges one delivered event through exact unknown-commit readback. */
    @Nonnull
    public SqliteUnitOfWorkRunner.Submission<ProjectionCheckpoint> acknowledge(
            @Nonnull ProjectionConsumerId consumerId,
            @Nonnull ProjectionSequence sequence,
            long acknowledgedAtMs
    ) {
        if (consumerId == null || sequence == null) {
            throw new IllegalArgumentException("Complete projection acknowledgement is required");
        }
        OperationId operationId = new OperationId(UUID.nameUUIDFromBytes(
                ("projection-checkpoint:v1:" + consumerId + ':' + sequence)
                        .getBytes(StandardCharsets.UTF_8)
        ));
        return acknowledge(
                consumerId, operationId, sequence, acknowledgedAtMs
        );
    }

    /**
     * Acknowledges one delivered boundary with a caller-supplied operation ID.
     *
     * <p>This overload remains for compatibility with older adapter callers;
     * coordinator delivery uses the deterministic boundary identity.</p>
     */
    @Nonnull
    public SqliteUnitOfWorkRunner.Submission<ProjectionCheckpoint> acknowledge(
            @Nonnull ProjectionConsumerId consumerId,
            @Nonnull OperationId operationId,
            @Nonnull ProjectionSequence sequence,
            long acknowledgedAtMs
    ) {
        if (consumerId == null || operationId == null || sequence == null) {
            throw new IllegalArgumentException("Complete projection acknowledgement is required");
        }
        SqliteTransactionCommand<ProjectionCheckpoint> command = new SqliteTransactionCommand<>(
                operationId,
                CHECKPOINT_KIND,
                TransactionReplayPolicy.SAFE_DATABASE_ONLY,
                connection -> {
                    PersistenceMutationResult<ProjectionCheckpoint> result =
                            new SqliteProjectionOutboxStore(connection).acknowledge(
                                    consumerId, sequence, acknowledgedAtMs
                            );
                    if (!result.applied()) {
                        throw new IllegalStateException(
                                "projection_checkpoint_" + result.status().name().toLowerCase()
                        );
                    }
                    return result.value();
                }
        );
        SqliteUnitOfWorkRunner.Submission<ProjectionCheckpoint> submission =
                units.execute(new SqliteUnitOfWork<>(
                command,
                CHECKPOINT_READBACK,
                connection -> {
                    ProjectionCheckpoint checkpoint =
                            new SqliteProjectionOutboxStore(connection)
                                    .findCheckpoint(consumerId)
                                    .orElse(null);
                    return checkpoint != null
                            && checkpoint.acknowledgedSequence().compareTo(sequence) >= 0
                            ? PersistenceReadResult.found(
                                    checkpoint,
                                    checkpoint.acknowledgedSequence().value()
                            )
                            : PersistenceReadResult.absent();
                }
        ));
        return new SqliteUnitOfWorkRunner.Submission<>(
                submission.acceptance(),
                submission.completion().thenApply(result -> {
                    if (result instanceof PersistenceTransactionResult.Committed<?>) {
                        safe(() -> throughputMetrics.projectionBatchAcknowledged());
                    }
                    return result;
                })
        );
    }

    private PersistenceReadResult<ProjectionBatch> foundBatch(
            ProjectionCheckpoint checkpoint,
            ProjectionSequence target,
            java.util.List<ProjectionEvent> events,
            int limit
    ) {
        safe(() -> throughputMetrics.projectionBatchLoaded(
                sequencePositions(checkpoint, target, events, limit),
                events.size()
        ));
        return PersistenceReadResult.found(
                new ProjectionBatch(checkpoint, target, events),
                target.value()
        );
    }

    private long sequencePositions(
            ProjectionCheckpoint checkpoint,
            ProjectionSequence target,
            java.util.List<ProjectionEvent> events,
            int limit
    ) {
        long end = events.size() >= limit
                ? events.getLast().sequence().value()
                : target.value();
        return Math.max(
                0L,
                end - checkpoint.acknowledgedSequence().value()
        );
    }

    private void safe(Runnable hook) {
        try {
            hook.run();
        } catch (Throwable ignored) {
            // Throughput measurements cannot change persistence outcomes.
        }
    }
}
