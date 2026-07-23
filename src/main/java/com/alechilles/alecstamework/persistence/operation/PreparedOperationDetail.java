package com.alechilles.alecstamework.persistence.operation;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqlitePersistenceTransactionContext;
import javax.annotation.Nonnull;

/**
 * Typed detail that must commit atomically with an operation's durable preparation.
 *
 * <p>Implementations may use only the supplied transaction context. Verification must accept
 * later valid evolution of its evidence, such as a leased alias becoming current.</p>
 */
public interface PreparedOperationDetail {
    /** Writes idempotent preparation evidence inside the operation preparation transaction. */
    void prepare(
            @Nonnull SqlitePersistenceTransactionContext transaction,
            @Nonnull OperationEnvelope operation
    ) throws Exception;

    /** Proves the exact operation owns its required preparation evidence. */
    boolean matches(
            @Nonnull SqlitePersistenceTransactionContext transaction,
            @Nonnull OperationEnvelope operation
    ) throws Exception;

    /** Returns a no-detail contract for ordinary database-only operations. */
    @Nonnull
    static PreparedOperationDetail none() {
        return NoDetail.INSTANCE;
    }

    enum NoDetail implements PreparedOperationDetail {
        INSTANCE;

        @Override
        public void prepare(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation
        ) {
        }

        @Override
        public boolean matches(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation
        ) {
            return true;
        }
    }
}
