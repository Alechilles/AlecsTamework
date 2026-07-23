package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadPriority;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.DurableCommitEvidence;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import java.util.List;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Async exact readback of one durable or published operation and its outbox evidence. */
public final class SqliteOperationEvidenceReader {
    private static final PersistenceReadKind READ_KIND =
            new PersistenceReadKind("operation_durable_evidence");

    private final SqliteReadExecutor reads;

    public SqliteOperationEvidenceReader(@Nonnull SqliteReadExecutor reads) {
        if (reads == null) {
            throw new IllegalArgumentException("Operation evidence read executor is required");
        }
        this.reads = reads;
    }

    @Nonnull
    public CompletionStage<PersistenceReadResult<DurableCommitEvidence>> find(
            @Nonnull OperationId operationId
    ) {
        if (operationId == null) {
            throw new IllegalArgumentException("Operation ID is required");
        }
        return reads.execute(new SqliteReadCommand<>(
                READ_KIND,
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> {
                    SqlitePersistenceTransactionContext transaction =
                            new SqlitePersistenceTransactionContext(connection);
                    OperationEnvelope operation = transaction.operations()
                            .find(operationId)
                            .orElse(null);
                    if (operation == null) {
                        return PersistenceReadResult.absent();
                    }
                    if (operation.phase() != OperationPhase.DURABLE
                            && operation.phase() != OperationPhase.PUBLISHED) {
                        return PersistenceReadResult.absent();
                    }
                    List<ProjectionEvent> events =
                            transaction.outbox().findByOperation(operationId);
                    if (events.isEmpty()) {
                        return PersistenceReadResult.absent();
                    }
                    return PersistenceReadResult.found(
                            new DurableCommitEvidence(operation, events),
                            events.getLast().sequence().value()
                    );
                }
        ));
    }
}
