package com.alechilles.alecstamework.persistence.operation;

import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable shared durable operation envelope used by every persistence-backed feature.
 */
public record OperationEnvelope(@Nonnull OperationId operationId,
                                @Nonnull IdempotencyKey idempotencyKey,
                                @Nonnull OperationKind kind,
                                int payloadVersion,
                                @Nonnull String payloadJson,
                                @Nonnull OperationPhase phase,
                                @Nonnull String featureScope,
                                @Nullable LifecycleRevision expectedLifecycleRevision,
                                @Nullable String leaseOwner,
                                long leaseUntilMs,
                                int attemptCount,
                                @Nullable String failureKind,
                                @Nullable String failureCode,
                                long createdAtMs,
                                long updatedAtMs,
                                @Nullable Long durableAtMs,
                                @Nullable Long publishedAtMs,
                                @Nullable Long terminalAtMs,
                                @Nonnull List<OperationScope> participants) {
    public OperationEnvelope {
        if (operationId == null || idempotencyKey == null || kind == null || phase == null) {
            throw new IllegalArgumentException("Operation identity, kind, and phase are required");
        }
        if (payloadVersion <= 0 || payloadJson == null) {
            throw new IllegalArgumentException("Positive operation payload version and JSON are required");
        }
        featureScope = requireText(featureScope, "Feature scope");
        leaseOwner = normalize(leaseOwner);
        failureKind = normalize(failureKind);
        failureCode = normalize(failureCode);
        if ((leaseOwner == null) != (leaseUntilMs == 0)) {
            throw new IllegalArgumentException("Operation lease owner and nonzero expiry must appear together");
        }
        if (attemptCount < 0) {
            throw new IllegalArgumentException("Operation attempt count cannot be negative");
        }
        if ((failureKind == null) != (failureCode == null)) {
            throw new IllegalArgumentException("Operation failure kind and code must appear together");
        }
        boolean failurePhase = phase == OperationPhase.RETRYABLE
                || phase == OperationPhase.FAILED
                || phase == OperationPhase.UNKNOWN;
        if (failurePhase && failureKind == null) {
            throw new IllegalArgumentException("Failure-bearing operation phase requires evidence");
        }
        if ((phase == OperationPhase.DURABLE || phase == OperationPhase.PUBLISHED)
                && durableAtMs == null) {
            throw new IllegalArgumentException("Durable operation phase requires durable time");
        }
        if (phase == OperationPhase.PUBLISHED
                && (publishedAtMs == null || terminalAtMs == null)) {
            throw new IllegalArgumentException("Published operation requires publication and terminal time");
        }
        if ((phase == OperationPhase.COMPENSATED || phase == OperationPhase.FAILED)
                && terminalAtMs == null) {
            throw new IllegalArgumentException("Terminal operation phase requires terminal time");
        }
        participants = participants == null ? List.of() : sortedDistinct(participants);
        if (!participants.contains(OperationScope.operation(operationId))) {
            throw new IllegalArgumentException("Operation participant scope is required");
        }
    }

    /** Returns whether the durable lease is owned and unexpired at the supplied signed time. */
    public boolean leasedAt(long nowMs) {
        return leaseOwner != null && nowMs < leaseUntilMs;
    }

    private static List<OperationScope> sortedDistinct(List<OperationScope> scopes) {
        java.util.TreeSet<OperationScope> sorted = new java.util.TreeSet<>();
        for (OperationScope scope : scopes) {
            if (scope == null) {
                throw new IllegalArgumentException("Operation participant cannot be null");
            }
            sorted.add(scope);
        }
        return List.copyOf(sorted);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
