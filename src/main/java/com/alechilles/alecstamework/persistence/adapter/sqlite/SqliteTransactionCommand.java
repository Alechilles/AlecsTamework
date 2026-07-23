package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.TransactionReplayPolicy;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import javax.annotation.Nonnull;

/**
 * Complete low-level transaction command accepted only by the replacement SQLite adapter.
 *
 * @param operationId unique operation identity
 * @param kind registered operation kind
 * @param replayPolicy known-rollback busy replay policy
 * @param work all SQL participants in this one logical operation
 * @param <T> committed value type
 */
public record SqliteTransactionCommand<T>(@Nonnull OperationId operationId,
                                          @Nonnull OperationKind kind,
                                          @Nonnull TransactionReplayPolicy replayPolicy,
                                          @Nonnull SqliteTransactionWork<T> work) {
    public SqliteTransactionCommand {
        if (operationId == null || kind == null || replayPolicy == null || work == null) {
            throw new IllegalArgumentException("Complete SQLite transaction command is required");
        }
    }
}
