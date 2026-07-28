package com.alechilles.alecstamework.persistence.operation;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqlitePersistenceTransactionContext;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * Canonical database mutation executed inside one operation transaction.
 *
 * <p>Implementations may use only the supplied transaction context. They must not perform live
 * ECS, network, filesystem, cache, or projection callbacks.</p>
 */
@FunctionalInterface
public interface DurableOperationWork {
    @Nonnull
    List<ProjectionEventDraft> execute(
            @Nonnull SqlitePersistenceTransactionContext transaction,
            @Nonnull OperationEnvelope operation
    ) throws Exception;
}
