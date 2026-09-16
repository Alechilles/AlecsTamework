package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.kernel.TransactionReplayPolicy;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Publication-driven maintenance on the existing writer, with no timer or extra executor.
 * Every 16 internal checkpoint publications examine at most 64 events. The cursor wraps
 * so recent/unacknowledged events are revisited; idle databases perform no maintenance.
 * Writer shutdown owns draining accepted batches. A failed batch retries on a later cadence.
 */
final class SqliteCheckpointHistoryMaintenance {
    static final int PUBLICATION_INTERVAL = 16;
    private final SqliteUnitOfWorkRunner units;
    private int publications;
    private long afterSequence;
    private boolean pending;

    SqliteCheckpointHistoryMaintenance(SqliteUnitOfWorkRunner units) {
        this.units = units;
    }

    synchronized void published(OperationEnvelope operation) {
        if (!operation.kind().value().equals("profile_extension_mutation")
                || !operation.idempotencyKey().value().startsWith(SqliteCheckpointReceipt.IDEMPOTENCY_PREFIX)
                || pending || ++publications < PUBLICATION_INTERVAL) {
            return;
        }
        publications = 0;
        pending = true;
        long cursor = afterSequence;
        AtomicReference<SqliteCheckpointHistoryStore.Batch> selected = new AtomicReference<>();
        SqliteTransactionCommand<SqliteCheckpointHistoryStore.Batch> command = new SqliteTransactionCommand<>(
                new OperationId(UUID.randomUUID()), operation.kind(),
                TransactionReplayPolicy.SAFE_DATABASE_ONLY, connection -> {
                    SqliteCheckpointHistoryStore store = new SqliteCheckpointHistoryStore(connection);
                    var batch = store.select(cursor, operation.publishedAtMs());
                    selected.set(batch);
                    store.compact(batch);
                    return batch;
                });
        units.execute(new SqliteUnitOfWork<>(command,
                new PersistenceReadKind("checkpoint_history_compaction"), connection -> {
                    var batch = selected.get();
                    return batch != null && new SqliteCheckpointHistoryStore(connection).matches(batch)
                            ? PersistenceReadResult.found(batch, 0)
                            : PersistenceReadResult.absent();
                })).completion().whenComplete((result, failure) -> {
                    synchronized (this) {
                        if (result instanceof PersistenceTransactionResult.Committed<
                                SqliteCheckpointHistoryStore.Batch> committed) {
                            afterSequence = committed.value().nextSequence();
                        }
                        pending = false;
                    }
                });
    }
}
