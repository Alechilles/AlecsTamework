package com.alechilles.alecstamework.persistence.operation;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Exact phase-and-lease compare request for one shared operation transition.
 */
public record OperationTransition(@Nonnull OperationId operationId,
                                  @Nonnull OperationPhase expectedPhase,
                                  @Nonnull OperationPhase nextPhase,
                                  @Nullable String expectedLeaseOwner,
                                  @Nullable String failureKind,
                                  @Nullable String failureCode,
                                  long transitionedAtMs) {
    public OperationTransition {
        if (operationId == null || expectedPhase == null || nextPhase == null) {
            throw new IllegalArgumentException("Operation ID and phases are required");
        }
        expectedPhase.requireTransitionTo(nextPhase);
        expectedLeaseOwner = normalize(expectedLeaseOwner);
        failureKind = normalize(failureKind);
        failureCode = normalize(failureCode);
        if ((failureKind == null) != (failureCode == null)) {
            throw new IllegalArgumentException("Operation failure kind and code must appear together");
        }
        boolean failurePhase = nextPhase == OperationPhase.RETRYABLE
                || nextPhase == OperationPhase.FAILED
                || nextPhase == OperationPhase.UNKNOWN;
        if (failurePhase && failureKind == null) {
            throw new IllegalArgumentException("Failure-bearing phases require exact failure evidence");
        }
        if (failureKind != null && nextPhase != OperationPhase.RETRYABLE
                && nextPhase != OperationPhase.FAILED && nextPhase != OperationPhase.UNKNOWN) {
            throw new IllegalArgumentException("Failure evidence requires a failure-bearing phase");
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
