package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Bounded, non-capability-bearing view of a durable vessel operation. */
public record BondedVesselOperationView(@Nonnull UUID operationId,
                                       @Nonnull String callerNamespace,
                                       @Nonnull String idempotencyKey,
                                       @Nonnull BondedVesselDurableOperationStatus status,
                                       @Nonnull String reason,
                                       @Nonnull UUID bindingId,
                                       @Nullable String profileId,
                                       @Nonnull BondedVesselTransition transition,
                                       long expectedGeneration,
                                       long candidateGeneration,
                                       long profileRevision,
                                       @Nullable Long cooldownUntilMs,
                                       boolean recovered,
                                       long updatedAtMs) {
    public BondedVesselOperationView {
        operationId = Objects.requireNonNull(operationId, "operationId");
        callerNamespace = requireText(callerNamespace, "callerNamespace");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        status = Objects.requireNonNull(status, "status");
        reason = requireText(reason, "reason");
        bindingId = Objects.requireNonNull(bindingId, "bindingId");
        profileId = profileId == null || profileId.isBlank() ? null : profileId.trim();
        transition = Objects.requireNonNull(transition, "transition");
        if (expectedGeneration < 0L || expectedGeneration == Long.MAX_VALUE
                || candidateGeneration != expectedGeneration + 1L
                || profileRevision < 0L || updatedAtMs < 0L) {
            throw new IllegalArgumentException("Vessel operation revisions and update timestamp cannot be negative.");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required.");
        return normalized;
    }
}
