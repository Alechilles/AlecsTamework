package com.alechilles.alecstamework.persistence.operation;

import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable application request for one registered persistence operation. */
public record OperationRequest<T>(@Nonnull OperationId operationId,
                                  @Nonnull IdempotencyKey idempotencyKey,
                                  @Nonnull T payload,
                                  @Nonnull String featureScope,
                                  @Nullable LifecycleRevision expectedLifecycleRevision,
                                  @Nonnull List<OperationScope> participants,
                                  long createdAtMs) {
    public OperationRequest {
        if (operationId == null || idempotencyKey == null || payload == null) {
            throw new IllegalArgumentException("Operation request identity and payload are required");
        }
        if (featureScope == null || featureScope.isBlank()) {
            throw new IllegalArgumentException("Operation request feature scope is required");
        }
        featureScope = featureScope.trim();
        participants = participants == null ? List.of() : List.copyOf(participants);
    }
}
