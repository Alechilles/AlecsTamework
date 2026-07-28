package com.alechilles.alecstamework.persistence.operation;

import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Idempotent external mutation or resolution performed after durable preparation.
 *
 * <p>The boundary runs outside SQLite. Recovery invokes it with the same payload and operation,
 * so implementations must inspect positive external evidence before repeating a mutation.</p>
 */
@FunctionalInterface
public interface LiveOperationBoundary<T> {
    @Nonnull
    CompletionStage<LiveOperationResult> applyOrResolve(
            @Nonnull T payload,
            @Nonnull OperationEnvelope operation
    ) throws Exception;
}
