package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import javax.annotation.Nonnull;

/**
 * One replacement transaction plus the exact query that resolves an unknown commit outcome.
 *
 * <p>The readback must query operation-correlated durable evidence: found means committed, absent
 * means the operation did not commit, and failed means the outcome remains unknown.</p>
 *
 * @param transaction one-operation transaction command
 * @param readbackKind stable readback identifier
 * @param unknownCommitReadback exact operation-correlated query
 * @param <T> committed result type
 */
public record SqliteUnitOfWork<T>(@Nonnull SqliteTransactionCommand<T> transaction,
                                  @Nonnull PersistenceReadKind readbackKind,
                                  @Nonnull SqliteReadWork<T> unknownCommitReadback) {
    public SqliteUnitOfWork {
        if (transaction == null || readbackKind == null || unknownCommitReadback == null) {
            throw new IllegalArgumentException("Transaction and exact unknown-commit readback are required");
        }
    }
}
