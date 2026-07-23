package com.alechilles.alecstamework.persistence.compensation;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqlitePersistenceTransactionContext;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import javax.annotation.Nonnull;

/**
 * Typed compensation evidence committed atomically with the shared compensating phase.
 *
 * <p>Implementations may create domain detail such as a refund claim, but may not introduce
 * another operation phase graph.</p>
 */
public interface PreparedCompensationDetail {
    /** Writes the idempotent compensation evidence inside the phase transition transaction. */
    void prepare(
            @Nonnull SqlitePersistenceTransactionContext transaction,
            @Nonnull OperationEnvelope operation,
            long preparedAtMs
    ) throws Exception;

    /** Proves the exact operation owns its required compensation evidence. */
    boolean matches(
            @Nonnull SqlitePersistenceTransactionContext transaction,
            @Nonnull OperationEnvelope operation
    ) throws Exception;
}
