package com.alechilles.alecstamework.persistence.operation;

import javax.annotation.Nonnull;

/**
 * Exact recovery lease claim for one nonterminal operation.
 */
public record OperationLeaseRequest(@Nonnull OperationId operationId,
                                    @Nonnull String leaseOwner,
                                    long nowMs,
                                    long leaseUntilMs) {
    public OperationLeaseRequest {
        if (operationId == null || leaseOwner == null || leaseOwner.isBlank()) {
            throw new IllegalArgumentException("Operation ID and lease owner are required");
        }
        leaseOwner = leaseOwner.trim();
        if (leaseUntilMs == 0 || leaseUntilMs <= nowMs) {
            throw new IllegalArgumentException("Operation lease must expire after the claim time");
        }
    }
}
