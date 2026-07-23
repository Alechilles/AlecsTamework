package com.alechilles.alecstamework.persistence.recovery;

import com.alechilles.alecstamework.persistence.operation.DecodedOperationPayload;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import javax.annotation.Nonnull;

/** Leased, decoded recovery work item that is safe to dispatch exactly once per lease. */
public record OperationRecoveryClaim(@Nonnull OperationEnvelope operation,
                                     @Nonnull DecodedOperationPayload payload,
                                     @Nonnull OperationRecoveryAction action) {
    public OperationRecoveryClaim {
        if (operation == null || payload == null || action == null
                || operation.leaseOwner() == null || operation.phase().isTerminal()) {
            throw new IllegalArgumentException("Nonterminal leased recovery claim is required");
        }
        if (!operation.kind().equals(payload.definition().kind())) {
            throw new IllegalArgumentException("Recovery payload definition does not match operation");
        }
    }
}
