package com.alechilles.alecstamework.persistence.operation;

import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * Transaction-local store for the one shared persistence operation protocol.
 *
 * <p>Implementations must not open connections, commit transactions, or invoke live effects.</p>
 */
public interface OperationStore {
    @Nonnull
    Optional<OperationEnvelope> find(@Nonnull OperationId operationId);

    @Nonnull
    Optional<OperationEnvelope> findByIdempotency(
            @Nonnull OperationKind kind,
            @Nonnull IdempotencyKey idempotencyKey
    );

    @Nonnull
    PersistenceMutationResult<OperationEnvelope> prepare(@Nonnull PreparedOperation operation);

    @Nonnull
    PersistenceMutationResult<OperationEnvelope> transition(
            @Nonnull OperationTransition transition
    );

    @Nonnull
    PersistenceMutationResult<OperationEnvelope> acquireLease(
            @Nonnull OperationLeaseRequest request
    );

    @Nonnull
    List<OperationEnvelope> findRecoverable(long nowMs, int limit);
}
