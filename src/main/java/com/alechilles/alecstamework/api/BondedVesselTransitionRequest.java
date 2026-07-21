package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Compare-and-transition request for one bonded-vessel mutation. */
public record BondedVesselTransitionRequest(@Nonnull String callerNamespace,
                                            @Nonnull String idempotencyKey,
                                            @Nonnull UUID actorUuid,
                                            @Nonnull UUID bindingId,
                                            long expectedGeneration,
                                            long expectedProfileRevision,
                                            @Nonnull BondedVesselTransition transition,
                                            @Nonnull BondedVesselTransitionContext context) {
    public BondedVesselTransitionRequest {
        callerNamespace = requireText(callerNamespace, "callerNamespace");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
        bindingId = Objects.requireNonNull(bindingId, "bindingId");
        transition = Objects.requireNonNull(transition, "transition");
        context = Objects.requireNonNull(context, "context");
        if (expectedGeneration < 0L || expectedProfileRevision < 0L) {
            throw new IllegalArgumentException("Expected generation and profile revision cannot be negative.");
        }
        context.validateFor(transition);
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }
}
