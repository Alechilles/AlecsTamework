package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadPriority;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import java.util.List;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Async read surface for operation envelopes and any atomically committed outbox evidence. */
public final class SqliteOperationReader {
    private static final PersistenceReadKind BY_ID =
            new PersistenceReadKind("operation_by_id");
    private static final PersistenceReadKind BY_IDEMPOTENCY =
            new PersistenceReadKind("operation_by_idempotency");

    private final SqliteReadExecutor reads;

    public SqliteOperationReader(@Nonnull SqliteReadExecutor reads) {
        if (reads == null) {
            throw new IllegalArgumentException("Operation read executor is required");
        }
        this.reads = reads;
    }

    @Nonnull
    public CompletionStage<PersistenceReadResult<OperationReadModel>> find(
            @Nonnull OperationId operationId
    ) {
        if (operationId == null) {
            throw new IllegalArgumentException("Operation ID is required");
        }
        return reads.execute(new SqliteReadCommand<>(
                BY_ID,
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> {
                    SqlitePersistenceTransactionContext transaction =
                            new SqlitePersistenceTransactionContext(connection);
                    OperationEnvelope operation =
                            transaction.operations().find(operationId).orElse(null);
                    return result(transaction, operation);
                }
        ));
    }

    @Nonnull
    public CompletionStage<PersistenceReadResult<OperationReadModel>> findByIdempotency(
            @Nonnull OperationKind kind,
            @Nonnull IdempotencyKey idempotencyKey
    ) {
        if (kind == null || idempotencyKey == null) {
            throw new IllegalArgumentException(
                    "Operation kind and idempotency key are required"
            );
        }
        return reads.execute(new SqliteReadCommand<>(
                BY_IDEMPOTENCY,
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> {
                    SqlitePersistenceTransactionContext transaction =
                            new SqlitePersistenceTransactionContext(connection);
                    OperationEnvelope operation = transaction.operations()
                            .findByIdempotency(kind, idempotencyKey)
                            .orElse(null);
                    return result(transaction, operation);
                }
        ));
    }

    private PersistenceReadResult<OperationReadModel> result(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        if (operation == null) {
            return PersistenceReadResult.absent();
        }
        List<ProjectionEvent> events =
                transaction.outbox().findByOperation(operation.operationId());
        long revision = events.isEmpty()
                ? operation.attemptCount()
                : events.getLast().sequence().value();
        return PersistenceReadResult.found(
                new OperationReadModel(operation, events),
                revision
        );
    }

    /** One durable operation plus zero or more atomically committed outbox events. */
    public record OperationReadModel(
            @Nonnull OperationEnvelope operation,
            @Nonnull List<ProjectionEvent> events
    ) {
        public OperationReadModel {
            if (operation == null || events == null) {
                throw new IllegalArgumentException("Complete operation read model is required");
            }
            events = List.copyOf(events);
            if (events.stream().anyMatch(event ->
                    !operation.operationId().equals(event.operationId()))) {
                throw new IllegalArgumentException("Operation event identity mismatch");
            }
        }
    }
}
