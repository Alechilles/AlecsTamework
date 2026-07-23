package com.alechilles.alecstamework.persistence.operation;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqlitePersistenceTransactionContext;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import java.util.List;
import javax.annotation.Nonnull;

/** Canonical durable work sharing one captured commit timestamp with its operation transition. */
@FunctionalInterface
public interface TimedDurableOperationWork<T> {
    @Nonnull
    List<ProjectionEventDraft> execute(
            @Nonnull SqlitePersistenceTransactionContext transaction,
            @Nonnull OperationEnvelope operation,
            @Nonnull T payload,
            long committedAtMs
    ) throws Exception;
}
