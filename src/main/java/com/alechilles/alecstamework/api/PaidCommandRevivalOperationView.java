package com.alechilles.alecstamework.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Restart-visible paid-revival operation state. */
public record PaidCommandRevivalOperationView(@Nonnull UUID operationId,
                                              @Nonnull String callerNamespace,
                                              @Nonnull String idempotencyKey,
                                              @Nonnull UUID ownerUuid,
                                              @Nonnull String profileId,
                                              @Nonnull State state,
                                              @Nonnull List<ItemCostComponentView> exactCost,
                                              @Nullable String reason,
                                              long updatedAtMs) {
    public PaidCommandRevivalOperationView {
        operationId = Objects.requireNonNull(operationId, "operationId");
        callerNamespace = requireText(callerNamespace, "callerNamespace");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        profileId = requireText(profileId, "profileId");
        state = Objects.requireNonNull(state, "state");
        exactCost = List.copyOf(Objects.requireNonNull(exactCost, "exactCost"));
        reason = reason == null || reason.isBlank() ? null : reason.trim();
    }

    public enum State {
        PREPARED, RESERVED, COST_CONSUMED, APPLYING, SUCCEEDED, CANCELED,
        REFUND_REQUIRED, REFUNDED, QUARANTINED
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
