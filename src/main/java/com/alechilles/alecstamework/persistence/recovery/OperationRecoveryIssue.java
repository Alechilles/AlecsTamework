package com.alechilles.alecstamework.persistence.recovery;

import com.alechilles.alecstamework.persistence.operation.OperationId;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Explicit per-operation recovery issue; contained decode failures carry durable quarantine. */
public record OperationRecoveryIssue(@Nonnull OperationId operationId,
                                     @Nonnull String code,
                                     boolean contained,
                                     @Nullable Throwable failure) {
    public OperationRecoveryIssue {
        if (operationId == null || code == null || code.isBlank()) {
            throw new IllegalArgumentException("Recovery issue operation and code are required");
        }
        code = code.trim();
    }
}
