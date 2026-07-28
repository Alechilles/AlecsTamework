package com.alechilles.alecstamework.persistence.compensation;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqlitePersistenceTransactionContext;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import javax.annotation.Nonnull;

/**
 * Canonical database work that finalizes one positively evidenced compensation.
 *
 * <p>Execution and verification run only inside the shared writer transaction. Live inventory,
 * ECS, filesystem, or network work is forbidden here.</p>
 */
public interface TimedCompensatedOperationWork<T> {
    void execute(
            @Nonnull SqlitePersistenceTransactionContext transaction,
            @Nonnull OperationEnvelope operation,
            @Nonnull T payload,
            @Nonnull String liveEvidence,
            long compensatedAtMs
    ) throws Exception;

    /** Proves the exact compensated state after an ambiguous commit return. */
    boolean matches(
            @Nonnull SqlitePersistenceTransactionContext transaction,
            @Nonnull OperationEnvelope operation,
            @Nonnull T payload
    ) throws Exception;
}
