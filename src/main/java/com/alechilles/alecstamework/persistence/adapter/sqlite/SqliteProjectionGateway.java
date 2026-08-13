package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadPriority;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.kernel.TransactionReplayPolicy;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.projection.ProjectionBatch;
import com.alechilles.alecstamework.persistence.projection.ProjectionCheckpoint;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumerId;
import com.alechilles.alecstamework.persistence.projection.ProjectionSequence;
import java.sql.Connection;
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

    public SqliteProjectionGateway(@Nonnull SqliteReadExecutor reads,
                                   @Nonnull SqliteUnitOfWorkRunner units) {
        this(reads, units, () -> { });
    }

    /** Test-only boundary injection for a concurrent outbox append. */
    SqliteProjectionGateway(@Nonnull SqliteReadExecutor reads,
                            @Nonnull SqliteUnitOfWorkRunner units,
                            @Nonnull Runnable afterHeadRead) {
        if (reads == null || units == null || afterHeadRead == null) {
            throw new IllegalArgumentException("Projection gateway dependencies are required");
        }
        this.reads = reads;
        this.units = units;
        this.afterHeadRead = afterHeadRead;
    }

    /** Loads an ordered consumer batch from its durable checkpoint. */
    @Nonnull
    public CompletionStage<PersistenceReadResult<ProjectionBatch>> load(
            @Nonnull ProjectionConsumerId consumerId,
            int limit
    ) {
        if (consumerId == null || limit <= 0 || limit > 10_000) {
            throw new IllegalArgumentException("Valid projection consumer and batch limit are required");
        }
        return reads.execute(new SqliteReadCommand<>(
                BATCH_READ,
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> loadBatch(connection, consumerId, limit)
        ));
    }

    private PersistenceReadResult<ProjectionBatch> loadBatch(
            Connection connection,
            ProjectionConsumerId consumerId,
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
            ProjectionSequence head = store.head();
            afterHeadRead.run();
            return PersistenceReadResult.found(
                    new ProjectionBatch(
                            checkpoint,
                            head,
                            store.readAfter(
                                    checkpoint.acknowledgedSequence(), limit
                            )
                    ),
                    head.value()
            );
        } finally {
            connection.rollback();
        }
    }

    /** Durably acknowledges one delivered event through exact unknown-commit readback. */
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
        return units.execute(new SqliteUnitOfWork<>(
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
    }
}
